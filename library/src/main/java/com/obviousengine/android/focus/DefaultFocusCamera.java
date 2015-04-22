/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.obviousengine.android.focus;

import static com.obviousengine.android.focus.debug.Log.Tag;
import static com.obviousengine.android.focus.CaptureSession.OnImageSavedListener;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import com.obviousengine.android.focus.debug.Log;
import com.obviousengine.android.focus.exif.ExifInterface;
import com.obviousengine.android.focus.exif.ExifTag;
import com.obviousengine.android.focus.exif.Rational;

/**
 * {@link FocusCamera} implementation directly on top of the Camera2 API.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class DefaultFocusCamera extends AbstractFocusCamera {

    /** Captures that are requested but haven't completed yet. */
    private static class InFlightCapture {
        final PhotoCaptureParameters parameters;
        final CaptureSession session;

        public InFlightCapture(PhotoCaptureParameters parameters,
                CaptureSession session) {
            this.parameters = parameters;
            this.session = session;
        }
    }

    private static final Tag TAG = new Tag("DefaultFocusCamera");

    /** If true, will write data about each capture request to disk. */
    private static final boolean DEBUG_WRITE_CAPTURE_DATA = false;
    /** If true, will log per-frame AF info. */
    private static final boolean DEBUG_FOCUS_LOG = false;

    /** Default JPEG encoding quality. */
    private static final Byte JPEG_QUALITY = 90;

    /**
     * Set to ImageFormat.JPEG, to use the hardware encoder, or
     * ImageFormat.YUV_420_888 to use the software encoder. No other image
     * formats are supported.
     */
    private static final int CAPTURE_IMAGE_FORMAT = ImageFormat.YUV_420_888;

    /** Duration to hold after manual focus tap. */
    private static final int FOCUS_HOLD_MILLIS = Settings3A.getFocusHoldMillis();
    /** Zero weight 3A region, to reset regions per API. */
    MeteringRectangle[] ZERO_WEIGHT_3A_REGION = AutoFocusHelper.getZeroWeightRegion();

    /**
     * CaptureRequest tags.
     * <ul>
     * <li>{@link #PRESHOT_TRIGGERED_AF}</li>
     * <li>{@link #CAPTURE}</li>
     * </ul>
     */
    public enum RequestTag {
        /** Request that is part of a pre shot trigger. */
        PRESHOT_TRIGGERED_AF,
        /** Capture request (purely for logging). */
        CAPTURE,
        /** Tap to focus (purely for logging). */
        TAP_TO_FOCUS
    }

    /** Current CONTROL_AF_MODE request to Camera2 API. */
    private int controlAFMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
    /** Last FocusCamera.AutoFocusState reported. */
    private AutoFocusState lastResultAFState = AutoFocusState.INACTIVE;
    /** Flag to take a picture when the lens is stopped. */
    private boolean takePictureWhenLensIsStopped = false;
    /** Takes a (delayed) picture with appropriate parameters. */
    private Runnable takePictureRunnable;
    /** Keep PictureCallback for last requested capture. */
    private PictureCallback lastPictureCallback = null;
    /** Last time takePicture() was called in uptimeMillis. */
    private long takePictureStartMillis;
    /** Runnable that returns to CONTROL_AF_MODE = AF_CONTINUOUS_PICTURE. */
    private final Runnable returnToContinuousAFRunnable = new Runnable() {
        @Override
        public void run() {
            aFRegions = ZERO_WEIGHT_3A_REGION;
            aERegions = ZERO_WEIGHT_3A_REGION;
            controlAFMode = CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
            repeatingPreview(null);
        }
    };

    /** Current zoom value. 1.0 is no zoom. */
    private float zoomValue = 1f;
    /** Current crop region: set from zoomValue. */
    private Rect cropRegion;
    /** Current AF and AE regions */
    private MeteringRectangle[] aFRegions = ZERO_WEIGHT_3A_REGION;
    private MeteringRectangle[] aERegions = ZERO_WEIGHT_3A_REGION;
    /** Last frame for which CONTROL_AF_STATE was received. */
    private long lastControlAfStateFrameNumber = 0;

    /**
     * Common listener for preview frame metadata.
     */
    private final CameraCaptureSession.CaptureCallback mAutoFocusStateListener = new
            CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureStarted(CameraCaptureSession session, CaptureRequest request,
                                             long timestamp, long frameNumber) {
                    if (request.getTag() == RequestTag.CAPTURE && lastPictureCallback != null) {
                        lastPictureCallback.onQuickExpose();
                    }
                }

                // AF state information is sometimes available 1 frame before
                // onCaptureCompleted(), so we take advantage of that.
                @Override
                public void onCaptureProgressed(CameraCaptureSession session,
                        CaptureRequest request,
                        CaptureResult partialResult) {
                    autofocusStateChangeDispatcher(partialResult);
                    super.onCaptureProgressed(session, request, partialResult);
                }

                @Override
                public void onCaptureCompleted(CameraCaptureSession session,
                        CaptureRequest request,
                        TotalCaptureResult result) {
                    autofocusStateChangeDispatcher(result);
                    // This checks for a HAL implementation error where TotalCaptureResult
                    // is missing CONTROL_AF_STATE.  This should not happen.
                    if (result.get(CaptureResult.CONTROL_AF_STATE) == null) {
                        AutoFocusHelper.checkControlAfState(result);
                    }
                    if (DEBUG_FOCUS_LOG) {
                        AutoFocusHelper.logExtraFocusInfo(result);
                    }
                    super.onCaptureCompleted(session, request, result);
                }
            };
    /** Thread on which the camera operations are running. */
    private final HandlerThread cameraThread;
    /** Handler of the {@link #cameraThread}. */
    private final Handler cameraHandler;
    /** The characteristics of this camera. */
    private final CameraCharacteristics characteristics;
    /** The underlying Camera2 API camera device. */
    private final CameraDevice device;

    /**
     * The aspect ratio (getWidth/getHeight) of the full resolution for this camera.
     * Usually the native aspect ratio of this camera.
     */
    private final float fullSizeAspectRatio;
    /** The Camera2 API capture session currently active. */
    private CameraCaptureSession captureSession;
    /** The surface onto which to render the preview. */
    private Surface previewSurface;
    /**
     * A queue of capture requests that have been requested but are not done
     * yet.
     */
    private final LinkedList<InFlightCapture> captureQueue = new LinkedList<>();
    /** Whether closing of this device has been requested. */
    private volatile boolean isClosed = false;
    /** A callback that is called when the device is fully closed. */
    private CloseCallback closeCallback = null;

    /** Receives the normal captured images. */
    private final ImageReader captureImageReader;
    ImageReader.OnImageAvailableListener captureImageListener =
            new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader reader) {
                    InFlightCapture capture = captureQueue.remove();

                    // Since this is not an HDR+ session, we will just save the
                    // result.
                    capture.session.startEmpty();
                    byte[] imageBytes = acquireJpegBytesAndClose(reader);
                    // TODO: The savePicture call here seems to block UI thread.
                    savePicture(imageBytes, capture.parameters, capture.session);
                    broadcastReadyState(true);
                    capture.parameters.callback.onPictureTaken(capture.session);
                }
            };

    /**
     * Instantiates a new camera based on Camera 2 API.
     *
     * @param device The underlying Camera 2 device.
     * @param characteristics The device's characteristics.
     * @param pictureSize the size of the final image to be taken.
     */
    DefaultFocusCamera(CameraDevice device, CameraCharacteristics characteristics, Size pictureSize) {
        this.device = device;
        this.characteristics = characteristics;
        fullSizeAspectRatio = calculateFullSizeAspectRatio(characteristics);

        cameraThread = new HandlerThread("FocusCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        captureImageReader = ImageReader.newInstance(
                pictureSize.getWidth(),
                pictureSize.getHeight(),
                CAPTURE_IMAGE_FORMAT, 2);
        captureImageReader.setOnImageAvailableListener(captureImageListener, cameraHandler);
        Log.d(TAG, "New Camera2 based DefaultFocusCamera created.");
    }

    /**
     * Take picture, initiating an auto focus scan if needed.
     */
    @Override
    public void takePicture(final PhotoCaptureParameters params, final CaptureSession session) {
        // Do not do anything when a picture is already requested.
        if (takePictureWhenLensIsStopped) {
            return;
        }

        // Not ready until the picture comes back.
        broadcastReadyState(false);

        takePictureRunnable = new Runnable() {
            @Override
            public void run() {
                takePictureNow(params, session);
            }
        };
        lastPictureCallback = params.callback;
        takePictureStartMillis = SystemClock.uptimeMillis();

        // This class implements a very simple version of AF, which
        // only delays capture if the lens is scanning.
        if (lastResultAFState == AutoFocusState.ACTIVE_SCAN) {
            Log.v(TAG, "Waiting until scan is done before taking shot.");
            takePictureWhenLensIsStopped = true;
        } else {
            // We could do CONTROL_AF_TRIGGER_START and wait until lens locks,
            // but this would slow down the capture.
            takePictureNow(params, session);
        }
    }

    /**
     * Take picture immediately. Parameters passed through from takePicture().
     */
    public void takePictureNow(PhotoCaptureParameters params, CaptureSession session) {
        long dt = SystemClock.uptimeMillis() - takePictureStartMillis;
        Log.v(TAG, "Taking shot with extra AF delay of " + dt + " ms.");
        // This will throw a RuntimeException, if parameters are not sane.
        params.checkSanity();
        try {
            // JPEG capture.
            CaptureRequest.Builder builder = device
                    .createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            builder.setTag(RequestTag.CAPTURE);
            addBaselineCaptureKeysToRequest(builder);

            if (CAPTURE_IMAGE_FORMAT == ImageFormat.JPEG) {
                builder.set(CaptureRequest.JPEG_QUALITY, JPEG_QUALITY);
                builder.set(CaptureRequest.JPEG_ORIENTATION,
                        Utils.getJpegRotation(params.orientation, characteristics));
            }

            builder.addTarget(previewSurface);
            builder.addTarget(captureImageReader.getSurface());
            CaptureRequest request = builder.build();

            if (DEBUG_WRITE_CAPTURE_DATA) {
                final String debugDataDir = makeDebugDir(params.debugDataFolder,
                        "normal_capture_debug");
                Log.i(TAG, "Writing capture data to: " + debugDataDir);
                CaptureDataSerializer.toFile("Normal Capture", request, new File(debugDataDir,
                        "capture.txt"));
            }

            captureSession.capture(request, mAutoFocusStateListener, cameraHandler);
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not access camera for still image capture.");
            broadcastReadyState(true);
            params.callback.onPictureTakenFailed();
            return;
        }
        captureQueue.add(new InFlightCapture(params, session));
    }

    @Override
    public void startPreview(SurfaceTexture surfaceTexture, CaptureReadyCallback listener) {
        startPreview(new Surface(surfaceTexture), listener);
    }

    @Override
    public void startPreview(Surface previewSurface, CaptureReadyCallback listener) {
        this.previewSurface = previewSurface;
        setupAsync(this.previewSurface, listener);
    }

    @Override
    public void setViewfinderSize(int width, int height) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public boolean isFlashSupported(boolean enhanced) {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public boolean isSupportingEnhancedMode() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public void close(CloseCallback closeCallback) {
        if (isClosed) {
            Log.w(TAG, "Camera is already closed.");
            return;
        }
        try {
            captureSession.abortCaptures();
        } catch (CameraAccessException e) {
            Log.e(TAG, "Could not abort captures in progress.");
        }
        isClosed = true;
        this.closeCallback = closeCallback;
        cameraThread.quitSafely();
        device.close();
    }

    @Override
    public Size[] getSupportedSizes() {
        StreamConfigurationMap config = characteristics
                .get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        return Size.buildArrayFromAndroidSizes(config.getOutputSizes(CAPTURE_IMAGE_FORMAT));
    }

    @Override
    public float getFullSizeAspectRatio() {
        return fullSizeAspectRatio;
    }

    @Override
    public boolean isFrontFacing() {
        return characteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_FRONT;
    }

    @Override
    public boolean isBackFacing() {
        return characteristics.get(CameraCharacteristics.LENS_FACING)
                == CameraMetadata.LENS_FACING_BACK;
    }

    private void savePicture(byte[] jpegData, final PhotoCaptureParameters captureParams,
            CaptureSession session) {
        int heading = captureParams.heading;
        int width = 0;
        int height = 0;
        int rotation = 0;
        ExifInterface exif = null;
        try {
            exif = new ExifInterface();
            exif.readExif(jpegData);

            Integer w = exif.getTagIntValue(ExifInterface.TAG_PIXEL_X_DIMENSION);
            width = (w == null) ? width : w;
            Integer h = exif.getTagIntValue(ExifInterface.TAG_PIXEL_Y_DIMENSION);
            height = (h == null) ? height : h;

            // Get image rotation from EXIF.
            rotation = Utils.getOrientation(exif);

            // Set GPS heading direction based on sensor, if location is on.
            if (heading >= 0) {
                ExifTag directionRefTag = exif.buildTag(
                        ExifInterface.TAG_GPS_IMG_DIRECTION_REF,
                        ExifInterface.GpsTrackRef.MAGNETIC_DIRECTION);
                ExifTag directionTag = exif.buildTag(
                        ExifInterface.TAG_GPS_IMG_DIRECTION,
                        new Rational(heading, 1));
                exif.setTag(directionRefTag);
                exif.setTag(directionTag);
            }
        } catch (IOException e) {
            Log.w(TAG, "Could not read exif from gcam jpeg", e);
            exif = null;
        }
        session.saveAndFinish(jpegData, width, height, rotation, exif,
                new OnImageSavedListener() {
                @Override
                public void onImageSaved(Uri uri) {
                    captureParams.callback.onPictureSaved(uri);
                }
            });
    }

    /**
     * Asynchronously sets up the capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when setup is completed.
     */
    private void setupAsync(final Surface previewSurface, final CaptureReadyCallback listener) {
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                setup(previewSurface, listener);
            }
        });
    }

    /**
     * Configures and attempts to create a capture session.
     *
     * @param previewSurface the surface onto which the preview should be
     *            rendered.
     * @param listener called when the setup is completed.
     */
    private void setup(Surface previewSurface, final CaptureReadyCallback listener) {
        try {
            if (captureSession != null) {
                captureSession.abortCaptures();
                captureSession = null;
            }
            List<Surface> outputSurfaces = new ArrayList<>(2);
            outputSurfaces.add(previewSurface);
            outputSurfaces.add(captureImageReader.getSurface());

            device.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigureFailed(CameraCaptureSession session) {
                    listener.onSetupFailed();
                }

                @Override
                public void onConfigured(CameraCaptureSession session) {
                    captureSession = session;
                    aFRegions = ZERO_WEIGHT_3A_REGION;
                    aERegions = ZERO_WEIGHT_3A_REGION;
                    zoomValue = 1f;
                    cropRegion = cropRegionForZoom(zoomValue);
                    boolean success = repeatingPreview(null);
                    if (success) {
                        listener.onReadyForCapture();
                    } else {
                        listener.onSetupFailed();
                    }
                }

                @Override
                public void onClosed(CameraCaptureSession session) {
                    super.onClosed(session);
                    if (closeCallback != null) {
                        closeCallback.onCameraClosed();
                    }
                }
            }, cameraHandler);
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not set up capture session", ex);
            listener.onSetupFailed();
        }
    }

    /**
     * Adds current regions to CaptureRequest and base AF mode + AF_TRIGGER_IDLE.
     *
     * @param builder Build for the CaptureRequest
     */
    private void addBaselineCaptureKeysToRequest(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, aFRegions);
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, aERegions);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
        builder.set(CaptureRequest.CONTROL_AF_MODE, controlAFMode);
        builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
        // Enable face detection
        builder.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE,
                CaptureRequest.STATISTICS_FACE_DETECT_MODE_FULL);
        builder.set(CaptureRequest.CONTROL_SCENE_MODE,
                CaptureRequest.CONTROL_SCENE_MODE_FACE_PRIORITY);
    }

    /**
     * Request preview capture stream with AF_MODE_CONTINUOUS_PICTURE.
     *
     * @return true if request was build and sent successfully.
     * @param tag
     */
    private boolean repeatingPreview(Object tag) {
        try {
            CaptureRequest.Builder builder = device.
                    createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            addBaselineCaptureKeysToRequest(builder);
            captureSession.setRepeatingRequest(builder.build(), mAutoFocusStateListener,
                    cameraHandler);
            Log.v(TAG, String.format("Sent repeating Preview request, zoom = %.2f", zoomValue));
            return true;
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not access camera setting up preview.", ex);
            return false;
        }
    }

    /**
     * Request preview capture stream with auto focus trigger cycle.
     */
    private void sendAutoFocusTriggerCaptureRequest(Object tag) {
        try {
            // Step 1: Request single frame CONTROL_AF_TRIGGER_START.
            CaptureRequest.Builder builder;
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            builder.addTarget(previewSurface);
            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
            controlAFMode = CameraMetadata.CONTROL_AF_MODE_AUTO;
            addBaselineCaptureKeysToRequest(builder);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);
            builder.setTag(tag);
            captureSession.capture(builder.build(), mAutoFocusStateListener, cameraHandler);

            // Step 2: Call repeatingPreview to update controlAFMode.
            repeatingPreview(tag);
            resumeContinuousAFAfterDelay(FOCUS_HOLD_MILLIS);
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not execute preview request.", ex);
        }
    }

    /**
     * Resume AF_MODE_CONTINUOUS_PICTURE after FOCUS_HOLD_MILLIS.
     */
    private void resumeContinuousAFAfterDelay(int millis) {
        cameraHandler.removeCallbacks(returnToContinuousAFRunnable);
        cameraHandler.postDelayed(returnToContinuousAFRunnable, millis);
    }

    /**
     * This method takes appropriate action if camera2 AF state changes.
     * <ol>
     * <li>Reports changes in camera2 AF state to FocusCamera.FocusStateListener.</li>
     * <li>Take picture after AF scan if takePictureWhenLensIsStopped true.</li>
     * </ol>
     */
    private void autofocusStateChangeDispatcher(CaptureResult result) {
        if (result.getFrameNumber() < lastControlAfStateFrameNumber ||
                result.get(CaptureResult.CONTROL_AF_STATE) == null) {
            return;
        }
        lastControlAfStateFrameNumber = result.getFrameNumber();

        // Convert to FocusCamera mode and state.
        AutoFocusState resultAFState = AutoFocusHelper.
                stateFromCamera2State(result.get(CaptureResult.CONTROL_AF_STATE));

        // TODO: Consider using LENS_STATE.
        boolean lensIsStopped = resultAFState == AutoFocusState.ACTIVE_FOCUSED ||
                resultAFState == AutoFocusState.ACTIVE_UNFOCUSED ||
                resultAFState == AutoFocusState.PASSIVE_FOCUSED ||
                resultAFState == AutoFocusState.PASSIVE_UNFOCUSED;

        if (takePictureWhenLensIsStopped && lensIsStopped) {
            // Take the shot.
            cameraHandler.post(takePictureRunnable);
            takePictureWhenLensIsStopped = false;
        }

        // Report state change when AF state has changed.
        if (resultAFState != lastResultAFState && focusStateListener != null) {
            focusStateListener.onFocusStatusUpdate(resultAFState, result.getFrameNumber());
        }
        lastResultAFState = resultAFState;
    }

    @Override
    public void triggerFocusAndMeterAtPoint(float nx, float ny) {
        int sensorOrientation = characteristics.get(
            CameraCharacteristics.SENSOR_ORIENTATION);
        aERegions = AutoFocusHelper.aeRegionsForNormalizedCoord(nx, ny, cropRegion, sensorOrientation);
        aFRegions = AutoFocusHelper.afRegionsForNormalizedCoord(nx, ny, cropRegion, sensorOrientation);

        sendAutoFocusTriggerCaptureRequest(RequestTag.TAP_TO_FOCUS);
    }

    @Override
    public float getMaxZoom() {
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    }

    @Override
    public void setZoom(float zoom) {
        zoomValue = zoom;
        cropRegion = cropRegionForZoom(zoom);
        repeatingPreview(null);
    }

    @Override
    public Size pickPreviewSize(Size pictureSize, Context context) {
        float pictureAspectRatio = pictureSize.getWidth() / (float) pictureSize.getHeight();
        return Utils.getOptimalPreviewSize(context, getSupportedSizes(), pictureAspectRatio);
    }

    private Rect cropRegionForZoom(float zoom) {
        return AutoFocusHelper.cropRegionForZoom(characteristics, zoom);
    }

    /**
     * Calculate the aspect ratio of the full size capture on this device.
     *
     * @param characteristics the characteristics of the camera device.
     * @return The aspect ration, in terms of getWidth/getHeight of the full capture
     *         size.
     */
    private static float calculateFullSizeAspectRatio(CameraCharacteristics characteristics) {
        Rect activeArraySize =
                characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        return ((float)(activeArraySize.width())) / activeArraySize.height();
    }

    /**
     * Given an image reader, extracts the JPEG image bytes and then closes the
     * reader.
     *
     * @param reader the reader to read the JPEG data from.
     * @return The bytes of the JPEG image. Newly allocated.
     */
    private static byte[] acquireJpegBytesAndClose(ImageReader reader) {
        Image img = reader.acquireLatestImage();

        ByteBuffer buffer;

        if (img.getFormat() == ImageFormat.JPEG) {
            Image.Plane plane0 = img.getPlanes()[0];
            buffer = plane0.getBuffer();
        } else {
            throw new RuntimeException("Unsupported image format.");
        }

        byte[] imageBytes = new byte[buffer.remaining()];
        buffer.get(imageBytes);
        buffer.rewind();
        img.close();
        return imageBytes;
    }
}
