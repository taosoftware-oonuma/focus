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

import static com.obviousengine.android.focus.CaptureSession.OnImageSavedListener;
import static com.obviousengine.android.focus.FocusCamera.PhotoCaptureParameters.Flash;

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
import android.hardware.camera2.CaptureResult.Key;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.CameraProfile;
import android.media.Image;
import android.media.ImageReader;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;

import android.support.v4.util.Pools;
import android.view.Surface;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.obviousengine.android.focus.debug.Log;
import com.obviousengine.android.focus.exif.ExifInterface;
import com.obviousengine.android.focus.exif.ExifTag;
import com.obviousengine.android.focus.exif.Rational;

/**
 * {@link FocusCamera} implementation directly on top of the Camera2 API with zero
 * shutter lag.<br>
 * TODO: Determine what the maximum number of full YUV capture frames is.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class ZslFocusCamera extends AbstractFocusCamera {

    private static final Log.Tag TAG = new Log.Tag("ZslFocusCamera");

    /** Default JPEG encoding quality. */
    private static final int JPEG_QUALITY = CameraProfile.getJpegEncodingQualityParameter(
            CameraProfile.QUALITY_HIGH);
    /**
     * The maximum number of images to store in the full-size ZSL ring buffer.
     * <br>
     * TODO: Determine this number dynamically based on available memory and the
     * size of frames.
     */
    private static final int MAX_CAPTURE_IMAGES = 10;
    /**
     * True if zero-shutter-lag images should be captured. Some devices produce
     * lower-quality images for the high-frequency stream, so we may wish to
     * disable ZSL in that case.
     */
    private static final boolean ZSL_ENABLED = false;

    /**
     * Tags which may be used in CaptureRequests.
     */
    private enum RequestTag {
        /**
         * Indicates that the request was explicitly sent for a single
         * high-quality still capture. Unlike other requests, such as the
         * repeating (ZSL) stream and AF/AE triggers, requests with this tag
         * should always be saved.
         */
        EXPLICIT_CAPTURE
    }

    /**
     * Set to {@link ImageFormat#JPEG} to use the hardware encoder, or
     * {@link ImageFormat#YUV_420_888} to use the software encoder. No other image
     * formats are supported.
     */
    private static final int CAPTURE_IMAGE_FORMAT = ImageFormat.JPEG;
    /**
     * Token for callbacks posted to {@link #cameraHandler} to resume
     * continuous AF.
     */
    private static final String FOCUS_RESUME_CALLBACK_TOKEN = "RESUME_CONTINUOUS_AF";

    /** Zero weight 3A region, to reset regions per API. */
    MeteringRectangle[] ZERO_WEIGHT_3A_REGION = AutoFocusHelper.getZeroWeightRegion();

    /**
     * Thread on which high-priority camera operations, such as grabbing preview
     * frames for the viewfinder, are running.
     */
    private final HandlerThread cameraThread;
    /** Handler of the {@link #cameraThread}. */
    private final Handler cameraHandler;

    /** Thread on which low-priority camera listeners are running. */
    private final HandlerThread cameraListenerThread;
    private final Handler cameraListenerHandler;

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
    /** Whether closing of this device has been requested. */
    private volatile boolean isClosed = false;
    /** A callback that is called when the device is fully closed. */
    private CloseCallback closeCallback = null;

    /** Receives the normal captured images. */
    private final ImageReader captureImageReader;

    /**
     * Maintains a buffer of images and their associated {@link CaptureResult}s.
     */
    private ImageCaptureManager captureManager;

    /**
     * The sensor timestamp (which may not be relative to the system time) of
     * the most recently captured image.
     */
    private final AtomicLong lastCapturedImageTimestamp = new AtomicLong(0);

    /** Thread pool for performing slow jpeg encoding and saving tasks. */
    private final ThreadPoolExecutor imageSaverThreadPool;

    /** Pool of native byte buffers on which to store jpeg-encoded images. */
    private final Pools.SynchronizedPool<ByteBuffer> jpegByteBufferPool = new
            Pools.SynchronizedPool<>(64);

    /** Current zoom value. 1.0 is no zoom. */
    private float zoomValue = 1f;
    /** Current crop region: set from zoomValue. */
    private Rect cropRegion;
    /** Current AE, AF, and AWB regions */
    private MeteringRectangle[] aFRegions = ZERO_WEIGHT_3A_REGION;
    private MeteringRectangle[] aERegions = ZERO_WEIGHT_3A_REGION;

    private MediaActionSound mediaActionSound = new MediaActionSound();

    /**
     * Ready state (typically displayed by the UI shutter-button) depends on two
     * things:<br>
     * <ol>
     * <li>{@link #captureManager} must be ready.</li>
     * <li>We must not be in the process of capturing a single, high-quality,
     * image.</li>
     * </ol>
     * See {@link ConjunctionListenerMux} and {@link #readyStateManager} for
     * details of how this is managed.
     */
    private enum ReadyStateRequirement {
        CAPTURE_MANAGER_READY,
        CAPTURE_NOT_IN_PROGRESS
    }

    /**
     * Handles the thread-safe logic of dispatching whenever the logical AND of
     * these constraints changes.
     */
    private final ConjunctionListenerMux<ReadyStateRequirement>
            readyStateManager = new ConjunctionListenerMux<>(
                    ReadyStateRequirement.class, new ConjunctionListenerMux.OutputChangeListener() {
                            @Override
                        public void onOutputChange(boolean state) {
                            broadcastReadyState(state);
                        }
                    });

    /**
     * An {@link ImageCaptureManager.ImageCaptureListener}
     * which will compress and save an image to disk.
     */
    private class ImageCaptureTask implements ImageCaptureManager.ImageCaptureListener {
        private final PhotoCaptureParameters parameters;
        private final CaptureSession session;

        public ImageCaptureTask(PhotoCaptureParameters parameters, CaptureSession session) {
            this.parameters = parameters;
            this.session = session;
        }

        @Override
        public void onImageCaptured(Image image, TotalCaptureResult captureResult) {
            long timestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP);

            // We should only capture the image if it's more recent than the
            // latest one. Synchronization is necessary since this method is
            // called on {@link #imageSaverThreadPool}.
            synchronized (lastCapturedImageTimestamp) {
                if (timestamp > lastCapturedImageTimestamp.get()) {
                    lastCapturedImageTimestamp.set(timestamp);
                } else {
                    // There was a more recent (or identical) image which has
                    // begun being saved, so abort.
                    return;
                }
            }

            readyStateManager.setInput(
                    ReadyStateRequirement.CAPTURE_NOT_IN_PROGRESS, true);

            session.startEmpty();
            savePicture(image, parameters, session);
            parameters.callback.onPictureTaken(session);
            Log.v(TAG, "Image saved.  Frame number = " + captureResult.getFrameNumber());
        }
    }

    /**
     * Instantiates a new camera based on Camera 2 API.
     *
     * @param device The underlying Camera 2 device.
     * @param characteristics The device's characteristics.
     * @param pictureSize the size of the final image to be taken.
     */
    ZslFocusCamera(CameraDevice device, CameraCharacteristics characteristics, Size pictureSize) {
        Log.v(TAG, "Creating new ZslFocusCamera");

        this.device = device;
        this.characteristics = characteristics;
        fullSizeAspectRatio = calculateFullSizeAspectRatio(characteristics);

        cameraThread = new HandlerThread("FocusCamera");
        // If this thread stalls, it will delay viewfinder frames.
        cameraThread.setPriority(Thread.MAX_PRIORITY);
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        cameraListenerThread = new HandlerThread("FocusCamera-Listener");
        cameraListenerThread.start();
        cameraListenerHandler = new Handler(cameraListenerThread.getLooper());

        // TODO: Encoding on multiple cores results in preview jank due to
        // excessive GC.
        int numEncodingCores = Utils.getNumCpuCores();
        imageSaverThreadPool = new ThreadPoolExecutor(numEncodingCores, numEncodingCores, 10,
                TimeUnit.SECONDS, new LinkedBlockingQueue<Runnable>());

        captureManager = new ImageCaptureManager(MAX_CAPTURE_IMAGES, cameraListenerHandler,
                imageSaverThreadPool);
        captureManager.setCaptureReadyListener(new ImageCaptureManager.CaptureReadyListener() {
            @Override
            public void onReadyStateChange(boolean capturePossible) {
                readyStateManager.setInput(ReadyStateRequirement.CAPTURE_MANAGER_READY,
                        capturePossible);
            }
        });

        // Listen for changes to auto focus state and dispatch to
        // focusStateListener.
        captureManager.addMetadataChangeListener(CaptureResult.CONTROL_AF_STATE,
                new ImageCaptureManager.MetadataChangeListener() {
                    @Override
                    public void onImageMetadataChange(Key<?> key, Object oldValue, Object newValue,
                                                      CaptureResult result) {
                        if (focusStateListener == null) {
                            return;
                        }
                        focusStateListener.onFocusStatusUpdate(
                                AutoFocusHelper.stateFromCamera2State(
                                        result.get(CaptureResult.CONTROL_AF_STATE)),
                                result.getFrameNumber());
                    }
                });

        // Allocate the image reader to store all images received from the
        // camera.
        if (pictureSize == null) {
            // TODO The default should be selected by the caller, and
            // pictureSize should never be null.
            pictureSize = getDefaultPictureSize();
        }
        captureImageReader = ImageReader.newInstance(
                pictureSize.getWidth(),
                pictureSize.getHeight(),
                CAPTURE_IMAGE_FORMAT, MAX_CAPTURE_IMAGES);

        captureImageReader.setOnImageAvailableListener(captureManager, cameraHandler);
        mediaActionSound.load(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * @return The largest supported picture size.
     */
    public Size getDefaultPictureSize() {
        StreamConfigurationMap configs = characteristics.get(
                CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        android.util.Size[] supportedSizes = configs.getOutputSizes(CAPTURE_IMAGE_FORMAT);

        // Find the largest supported size.
        android.util.Size largestSupportedSize = supportedSizes[0];
        long largestSupportedSizePixels = largestSupportedSize.getWidth()
                * largestSupportedSize.getHeight();
        for (int i = 0; i < supportedSizes.length; i++) {
            long numPixels = supportedSizes[i].getWidth() * supportedSizes[i].getHeight();
            if (numPixels > largestSupportedSizePixels) {
                largestSupportedSize = supportedSizes[i];
                largestSupportedSizePixels = numPixels;
            }
        }

        return new Size(largestSupportedSize.getWidth(),
                largestSupportedSize.getHeight());
    }

    private void onShutterInvokeUI(final PhotoCaptureParameters params) {
        // Tell CaptureModule shutter has occurred so it can flash the screen.
        params.callback.onQuickExpose();
        // Play shutter click sound.
        mediaActionSound.play(MediaActionSound.SHUTTER_CLICK);
    }

    /**
     * Take a picture.
     */
    @Override
    public void takePicture(final PhotoCaptureParameters params, final CaptureSession session) {
        params.checkSanity();

        readyStateManager.setInput(
                ReadyStateRequirement.CAPTURE_NOT_IN_PROGRESS, false);

        boolean useZSL = ZSL_ENABLED;

        // We will only capture images from the zsl ring-buffer which satisfy
        // this constraint.
        ArrayList<ImageCaptureManager.CapturedImageConstraint> zslConstraints = new ArrayList<>();
        zslConstraints.add(new ImageCaptureManager.CapturedImageConstraint() {
            @Override
            public boolean satisfiesConstraint(TotalCaptureResult captureResult) {
                Long timestamp = captureResult.get(CaptureResult.SENSOR_TIMESTAMP);
                Integer lensState = captureResult.get(CaptureResult.LENS_STATE);
                Integer flashState = captureResult.get(CaptureResult.FLASH_STATE);
                Integer flashMode = captureResult.get(CaptureResult.FLASH_MODE);
                Integer aeState = captureResult.get(CaptureResult.CONTROL_AE_STATE);
                Integer afState = captureResult.get(CaptureResult.CONTROL_AF_STATE);
                Integer awbState = captureResult.get(CaptureResult.CONTROL_AWB_STATE);

                if (timestamp <= lastCapturedImageTimestamp.get()) {
                    // Don't save frames older than the most
                    // recently-captured frame.
                    // TODO This technically has a race condition in which
                    // duplicate frames may be saved, but if a user is
                    // tapping at >30Hz, duplicate images may be what they
                    // expect.
                    return false;
                }

                if (lensState == CaptureResult.LENS_STATE_MOVING) {
                    // If we know the lens was moving, don't use this image.
                    return false;
                }

                if (aeState == CaptureResult.CONTROL_AE_STATE_SEARCHING
                        || aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                    return false;
                }
                switch (params.flashMode) {
                    case OFF:
                        break;
                    case ON:
                        if (flashState != CaptureResult.FLASH_STATE_FIRED
                                || flashMode != CaptureResult.FLASH_MODE_SINGLE) {
                            return false;
                        }
                        break;
                    case AUTO:
                        if (aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                                && flashState != CaptureResult.FLASH_STATE_FIRED) {
                            return false;
                        }
                        break;
                }

                if (afState == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN
                        || afState == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN) {
                    return false;
                }

                if (awbState == CaptureResult.CONTROL_AWB_STATE_SEARCHING) {
                    return false;
                }

                return true;
            }
        });
        // This constraint lets us capture images which have been explicitly
        // requested. See {@link RequestTag.EXPLICIT_CAPTURE}.
        ArrayList<ImageCaptureManager.CapturedImageConstraint> singleCaptureConstraint
                = new ArrayList<>();
        singleCaptureConstraint.add(new ImageCaptureManager.CapturedImageConstraint() {
            @Override
            public boolean satisfiesConstraint(TotalCaptureResult captureResult) {
                Object tag = captureResult.getRequest().getTag();
                return tag == RequestTag.EXPLICIT_CAPTURE;
            }
        });

        // If we can use ZSL, try to save a previously-captured frame, if an
        // acceptable one exists in the buffer.
        if (useZSL) {
            boolean capturedPreviousFrame = captureManager.tryCaptureExistingImage(
                    new ImageCaptureTask(params, session), zslConstraints);
            if (capturedPreviousFrame) {
                Log.v(TAG, "Saving previous frame");
                onShutterInvokeUI(params);
            } else {
                Log.v(TAG, "No good image Available.  Capturing next available good image.");
                // If there was no good frame available in the ring buffer
                // already, capture the next good image.
                // TODO Disable the shutter button until this image is captured.

                if (params.flashMode == Flash.ON || params.flashMode == Flash.AUTO) {
                    // We must issue a request for a single capture using the
                    // flash, including an AE precapture trigger.

                    // The following sets up a sequence of events which will
                    // occur in reverse order to the associated method
                    // calls:
                    // 1. Send a request to trigger the Auto Exposure Precapture
                    // 2. Wait for the AE_STATE to leave the PRECAPTURE state,
                    // and then send a request for a single image, with the
                    // appropriate flash settings.
                    // 3. Capture the next appropriate image, which should be
                    // the one we requested in (2).

                    captureManager.captureNextImage(new ImageCaptureTask(params, session),
                            singleCaptureConstraint);

                    captureManager.addMetadataChangeListener(CaptureResult.CONTROL_AE_STATE,
                            new ImageCaptureManager.MetadataChangeListener() {
                                @Override
                                public void onImageMetadataChange(Key<?> key, Object oldValue,
                                                                  Object newValue, CaptureResult result) {
                                    Log.v(TAG, "AE State Changed");
                                    if (oldValue.equals(
                                            Integer.valueOf(
                                                    CaptureResult.CONTROL_AE_STATE_PRECAPTURE))) {
                                        captureManager.removeMetadataChangeListener(key, this);
                                        sendSingleRequest(params);
                                        // TODO: Delay this until onCaptureStarted().
                                        onShutterInvokeUI(params);
                                    }
                                }
                            });

                    sendAutoExposureTriggerRequest(params.flashMode);
                } else {
                    // We may get here if, for example, the auto focus is in the
                    // middle of a scan.
                    // If the flash is off, we should just wait for the next
                    // image that arrives. This will have minimal delay since we
                    // do not need to send a new capture request.
                    captureManager.captureNextImage(new ImageCaptureTask(params, session),
                            zslConstraints);
                }
            }
        } else {
            // TODO If we can't save a previous frame, create a new capture
            // request to do what we need (e.g. flash) and call
            // captureNextImage().
            throw new UnsupportedOperationException("Non-ZSL capture not yet supported");
        }
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
    public void setPreviewFrameListener(PreviewFrameListener listener, Handler handler) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void setViewfinderSize(int width, int height) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isFlashSupported(boolean enhanced) {
        throw new RuntimeException("Not implemented yet.");
    }

    @Override
    public boolean isSupportingEnhancedMode() {
        throw new RuntimeException("Not implemented yet.");
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
        captureManager.close();
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

    private void savePicture(Image image, final PhotoCaptureParameters captureParams,
            CaptureSession session) {
        int heading = captureParams.heading;

        int width = image.getWidth();
        int height = image.getHeight();
        int rotation = 0;
        ExifInterface exif = null;

        exif = new ExifInterface();
        // TODO: Add more exif tags here.

        exif.setTag(exif.buildTag(ExifInterface.TAG_PIXEL_X_DIMENSION, width));
        exif.setTag(exif.buildTag(ExifInterface.TAG_PIXEL_Y_DIMENSION, height));

        // TODO: Handle rotation correctly.

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

        session.saveAndFinish(acquireJpegBytes(image), width, height, rotation, exif,
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
                    boolean success = sendRepeatingCaptureRequest();
                    if (success) {
                        readyStateManager.setInput(ReadyStateRequirement.CAPTURE_NOT_IN_PROGRESS,
                                true);
                        readyStateManager.notifyListeners();
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

    private void addRegionsToCaptureRequestBuilder(CaptureRequest.Builder builder) {
        builder.set(CaptureRequest.CONTROL_AE_REGIONS, aERegions);
        builder.set(CaptureRequest.CONTROL_AF_REGIONS, aFRegions);
        builder.set(CaptureRequest.SCALER_CROP_REGION, cropRegion);
    }

    private void addFlashToCaptureRequestBuilder(CaptureRequest.Builder builder, Flash flashMode) {
        switch (flashMode) {
            case ON:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_SINGLE);
                break;
            case OFF:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON);
                builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);
                break;
            case AUTO:
                builder.set(CaptureRequest.CONTROL_AE_MODE,
                        CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH);
                break;
        }
    }

    /**
     * Request a stream of images.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendRepeatingCaptureRequest() {
        Log.v(TAG, "sendRepeatingCaptureRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = device.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(previewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(captureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AF_MODE,
                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF);

            addRegionsToCaptureRequestBuilder(builder);

            captureSession.setRepeatingRequest(builder.build(), captureManager,
                    cameraHandler);
            return true;
        } catch (CameraAccessException e) {
            if (ZSL_ENABLED) {
                Log.v(TAG, "Could not execute zero-shutter-lag repeating request.", e);
            } else {
                Log.v(TAG, "Could not execute preview request.", e);
            }
            return false;
        }
    }

    /**
     * Request a single image.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendSingleRequest(FocusCamera.PhotoCaptureParameters params) {
        Log.v(TAG, "sendSingleRequest()");
        try {
            CaptureRequest.Builder builder;
            builder = device.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            builder.addTarget(previewSurface);

            // Always add this surface for single image capture requests.
            builder.addTarget(captureImageReader.getSurface());

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            addFlashToCaptureRequestBuilder(builder, params.flashMode);
            addRegionsToCaptureRequestBuilder(builder);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            // Tag this as a special request which should be saved.
            builder.setTag(RequestTag.EXPLICIT_CAPTURE);

            if (CAPTURE_IMAGE_FORMAT == ImageFormat.JPEG) {
                builder.set(CaptureRequest.JPEG_QUALITY, (byte) (JPEG_QUALITY));
                builder.set(CaptureRequest.JPEG_ORIENTATION,
                        Utils.getJpegRotation(params.orientation, characteristics));
            }

            captureSession.capture(builder.build(), captureManager,
                    cameraHandler);
            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute single still capture request.", e);
            return false;
        }
    }

    private boolean sendAutoExposureTriggerRequest(Flash flashMode) {
        Log.v(TAG, "sendAutoExposureTriggerRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = device.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(previewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(captureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            addRegionsToCaptureRequestBuilder(builder);
            addFlashToCaptureRequestBuilder(builder, flashMode);

            captureSession.capture(builder.build(), captureManager,
                    cameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto exposure trigger request.", e);
            return false;
        }
    }

    /**
     */
    private boolean sendAutoFocusTriggerRequest() {
        Log.v(TAG, "sendAutoFocusTriggerRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = device.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(previewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(captureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO);

            addRegionsToCaptureRequestBuilder(builder);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START);

            captureSession.capture(builder.build(), captureManager, cameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto focus trigger request.", e);
            return false;
        }
    }

    /**
     * Like {@link #sendRepeatingCaptureRequest()}, but with the focus held
     * constant.
     *
     * @return true if successful, false if there was an error submitting the
     *         capture request.
     */
    private boolean sendAutoFocusHoldRequest() {
        Log.v(TAG, "sendAutoFocusHoldRequest()");
        try {
            CaptureRequest.Builder builder;
            if (ZSL_ENABLED) {
                builder = device.
                        createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
            } else {
                builder = device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }

            builder.addTarget(previewSurface);

            if (ZSL_ENABLED) {
                builder.addTarget(captureImageReader.getSurface());
            }

            builder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

            builder.set(CaptureRequest.CONTROL_AF_MODE, CameraMetadata.CONTROL_AF_MODE_AUTO);
            builder.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE);

            addRegionsToCaptureRequestBuilder(builder);
            // TODO: This should fire the torch, if appropriate.

            captureSession.setRepeatingRequest(builder.build(), captureManager, cameraHandler);

            return true;
        } catch (CameraAccessException e) {
            Log.v(TAG, "Could not execute auto focus hold request.", e);
            return false;
        }
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
        return ((float) activeArraySize.width()) / activeArraySize.height();
    }

    /**
     * Given an image reader, extracts the JPEG image bytes and then closes the
     * reader.
     *
     * @param img the image from which to extract jpeg bytes or compress to
     *            jpeg.
     * @return The bytes of the JPEG image. Newly allocated.
     */
    private byte[] acquireJpegBytes(Image img) {
        ByteBuffer buffer;

        if (img.getFormat() == ImageFormat.JPEG) {
            Image.Plane plane0 = img.getPlanes()[0];
            buffer = plane0.getBuffer();

            byte[] imageBytes = new byte[buffer.remaining()];
            buffer.get(imageBytes);
            buffer.rewind();
            return imageBytes;
        } else {
            throw new RuntimeException("Unsupported image format.");
        }
    }

    private void startAFCycle() {
        // Clean up any existing AF cycle's pending callbacks.
        cameraHandler.removeCallbacksAndMessages(FOCUS_RESUME_CALLBACK_TOKEN);

        // Send a single CONTROL_AF_TRIGGER_START capture request.
        sendAutoFocusTriggerRequest();

        // Immediately send a request for a regular preview stream, but with
        // CONTROL_AF_MODE_AUTO set so that the focus remains constant after the
        // AF cycle completes.
        sendAutoFocusHoldRequest();

        // Waits Settings3A.getFocusHoldMillis() milliseconds before sending
        // a request for a regular preview stream to resume.
        cameraHandler.postAtTime(new Runnable() {
                                      @Override
                                      public void run() {
                                          aERegions = ZERO_WEIGHT_3A_REGION;
                                          aFRegions = ZERO_WEIGHT_3A_REGION;
                                          sendRepeatingCaptureRequest();
                                      }
                                  }, FOCUS_RESUME_CALLBACK_TOKEN,
                SystemClock.uptimeMillis() + Settings3A.getFocusHoldMillis());
    }

    /**
     * @see com.obviousengine.android.focus.FocusCamera#triggerFocusAndMeterAtPoint(float, float)
     */
    @Override
    public void triggerFocusAndMeterAtPoint(float nx, float ny) {
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        aERegions = AutoFocusHelper.aeRegionsForNormalizedCoord(
                nx, ny, cropRegion, sensorOrientation);
        aFRegions = AutoFocusHelper.afRegionsForNormalizedCoord(
                nx, ny, cropRegion, sensorOrientation);

        startAFCycle();
    }

    @Override
    public Size pickPreviewSize(Size pictureSize, Context context) {
        if (pictureSize == null) {
            // TODO The default should be selected by the caller, and
            // pictureSize should never be null.
            pictureSize = getDefaultPictureSize();
        }
        float pictureAspectRatio = pictureSize.getWidth() / (float) pictureSize.getHeight();
        return Utils.getOptimalPreviewSize(context, getSupportedSizes(), pictureAspectRatio);
    }

    @Override
    public float getMaxZoom() {
        return characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
    }

    @Override
    public void setZoom(float zoom) {
        zoomValue = zoom;
        cropRegion = cropRegionForZoom(zoom);
        sendRepeatingCaptureRequest();
    }

    private Rect cropRegionForZoom(float zoom) {
        return AutoFocusHelper.cropRegionForZoom(characteristics, zoom);
    }

    @Override
    public int getSensorOrientation() {
        return characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
    }

    @Override
    public float getHorizontalFov() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public float getVerticalFov() {
        throw new UnsupportedOperationException("Not implemented yet.");
    }
}
