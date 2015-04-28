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

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.location.Location;
import android.net.Uri;
import android.os.Handler;
import android.view.Surface;

import java.io.File;

/**
 * FocusCamera provides a unified interface to the {@link android.hardware.camera2}
 * and (possibly) the {@link android.hardware.Camera} APIs.
 */
public interface FocusCamera {

    /** Which way the camera is facing. */
    enum Facing {
        FRONT, BACK
    }

    /**
     * Auto focus system status; 1:1 mapping from camera2 AF_STATE.
     * <ul>
     * <li>{@link #INACTIVE}</li>
     * <li>{@link #ACTIVE_SCAN}</li>
     * <li>{@link #ACTIVE_FOCUSED}</li>
     * <li>{@link #ACTIVE_UNFOCUSED}</li>
     * <li>{@link #PASSIVE_SCAN}</li>
     * <li>{@link #PASSIVE_FOCUSED}</li>
     * <li>{@link #PASSIVE_UNFOCUSED}</li>
     * </ul>
     */
    enum AutoFocusState {
        /** Indicates AF system is inactive for some reason (could be an error). */
        INACTIVE,
        /** Indicates active scan in progress. */
        ACTIVE_SCAN,
        /** Indicates active scan success (in focus). */
        ACTIVE_FOCUSED,
        /** Indicates active scan failure (not in focus). */
        ACTIVE_UNFOCUSED,
        /** Indicates passive scan in progress. */
        PASSIVE_SCAN,
        /** Indicates passive scan success (in focus). */
        PASSIVE_FOCUSED,
        /** Indicates passive scan failure (not in focus). */
        PASSIVE_UNFOCUSED
    }

    /**
     * Auto focus system mode.
     * <ul>
     * <li>{@link #CONTINUOUS_PICTURE}</li>
     * <li>{@link #AUTO}</li>
     * </ul>
     */
    enum AutoFocusMode {
        /** System is continuously focusing. */
        CONTINUOUS_PICTURE,
        /** System is running a triggered scan. */
        AUTO
    }

    /**
     * Classes implementing this interface will be called when the camera was
     * opened or failed to open.
     */
    interface OpenCallback {
        /**
         * Called when the camera was opened successfully.
         *
         * @param camera the camera instance that was successfully opened
         */
        void onCameraOpened(FocusCamera camera);

        /**
         * Called if opening the camera failed.
         */
        void onFailure();

        /**
         * Called if the camera is closed or disconnected while attempting to
         * open.
         */
        void onCameraClosed();
    }

    /**
     * Classes implementing this interface will be called when the camera was
     * closed.
     */
    interface CloseCallback {
        /** Called when the camera was fully closed. */
        void onCameraClosed();
    }

    /**
     * Classes implementing this interface can be informed when we're ready to
     * take a picture of if setting up the capture pipeline failed.
     */
    interface CaptureReadyCallback {
        /** After this is called, the system is ready for capture requests. */
        void onReadyForCapture();

        /**
         * Indicates that something went wrong during setup and the system is
         * not ready for capture requests.
         */
        void onSetupFailed();
    }

    /**
     * Classes implementing this interface can be informed when the state of
     * capture changes.
     */
    interface ReadyStateChangedListener {
        /**
         * Called when the camera is either ready or not ready to take a picture
         * right now.
         */
        void onReadyStateChanged(boolean readyForCapture);
    }

    /**
     * A class implementing this interface can be passed into the call to take a
     * picture in order to receive the resulting image or updated about the
     * progress.
     */
    interface PictureCallback {
        /**
         * Called near the the when an image is being exposed for cameras which
         * are exposing a single frame, so that a UI can be presented for the
         * capture.
         */
        void onQuickExpose();

        /**
         * Called when a thumbnail image is provided before the final image is
         * finished.
         */
        void onThumbnailResult(byte[] jpegData);

        /**
         * Called when the final picture is done taking
         *
         * @param session the capture session
         */
        void onPictureTaken(CaptureSession session);

        /**
         * Called when the picture has been saved to disk.
         *
         * @param uri the URI of the stored data.
         */
        void onPictureSaved(Uri uri);

        /**
         * Called when picture taking failed.
         */
        void onPictureTakenFailed();

        /**
         * Called when capture session is reporting a processing update. This
         * should only be called by capture sessions that require the user to
         * hold still for a while.
         *
         * @param progress a value from 0...1, indicating the current processing
         *            progress.
         */
        void onTakePictureProgress(float progress);
    }

    /**
     * Classes implementing this interface will be called when
     * preview receives a new camera frame.
     */
    interface PreviewFrameListener {

        /**
         * Called when preview receives a new camera frame.
         *
         * @param frame the preview frame in the size and format of the preview
         */
        void onPreviewFrame(PreviewFrame frame);
    }

    /**
     * Immutable preview frame data container.
     */
    final class PreviewFrame {

        private final int format;
        private final byte[] data;
        private final int[] strides;
        private final int width;
        private final int height;

        private PreviewFrame(byte[] data, int format, int width, int height, int[] strides) {
            this.format = format;
            this.data = data;
            this.width = width;
            this.height = height;
            this.strides = strides;
        }

        /**
         * Construct an instance from {@link YuvImage};
         *
         * @param yuvImage the image to borrow values from.
         * @throws NullPointerException if {@link YuvImage} is null
         */
         static PreviewFrame valueOf(YuvImage yuvImage) {
            if (yuvImage == null) {
                throw new NullPointerException("yuvImage cannot be null");
            }
            return new PreviewFrame(
                    yuvImage.getYuvData(),
                    yuvImage.getYuvFormat(),
                    yuvImage.getWidth(),
                    yuvImage.getHeight(),
                    yuvImage.getStrides()
            );
        }

        /**
         * @return The YUV format as defined in {@link ImageFormat}.
         */
        public int getFormat() {
            return format;
        }

        /**
         * In the case of more than one image plane, the image planes are
         * concatenated into a single byte array.
         *
         * @return The raw YUV data.
         */
        public byte[] getData() {
            return data;
        }

        /**
         * @return The number of row bytes in each image plane.
         */
        public int[] getStrides() {
            return strides;
        }

        /**
         * @return The width of the image.
         */
        public int getWidth() {
            return width;
        }

        /**
         * @return The height of the the image.
         */
        public int getHeight() {
            return height;
        }
    }

    /**
     * Classes implementing this interface will be called whenever the camera
     * encountered an error.
     */
    interface CameraErrorListener {
        /** Called when the camera encountered an error. */
        void onCameraError();
    }

    /**
     * Classes implementing this interface will be called when the state of the
     * focus changes. Guaranteed not to stay stuck in scanning state past some
     * reasonable timeout even if Camera API is stuck.
     */
    interface FocusStateListener {
        /**
         * Called when state of auto focus system changes.
         *
         * @param state Current auto focus state.
         * @param frameNumber Frame number if available.
         */
        void onFocusStatusUpdate(AutoFocusState state, long frameNumber);
    }

    /**
     * Parameters to be given to photo capture requests.
     */
    final class PhotoCaptureParameters {
        /**
         * Flash modes.
         * <p>
         * Has to be in sync with R.arrays.pref_camera_flashmode_entryvalues.
         */
        public enum Flash {
            AUTO, OFF, ON
        }

        /** The title/filename (without suffix) for this capture. */
        public String title = null;
        /** Called when the capture is completed or failed. */
        public PictureCallback callback = null;
        /** The device orientation so we can compute the right JPEG rotation. */
        public int orientation = Integer.MIN_VALUE;
        /** The heading of the device at time of capture. In degrees. */
        public int heading = Integer.MIN_VALUE;
        /** Flash mode for this capture. */
        public Flash flashMode = Flash.AUTO;
        /** The location of this capture. */
        public Location location = null;
        /** Zoom value. */
        public float zoom = 1f;
        /** Timer duration in seconds or null for no timer. */
        public Float timerSeconds = null;

        /** Set this to provide a debug folder for this capture. */
        public File debugDataFolder;

        /**
         * Checks whether all required values are set. If one is missing, it
         * throws a {@link RuntimeException}.
         */
        public void checkSanity() {
            checkRequired(title);
            checkRequired(callback);
            checkRequired(orientation);
            checkRequired(heading);
        }

        private void checkRequired(int num) {
            if (num == Integer.MIN_VALUE) {
                throw new RuntimeException("Photo capture parameter missing.");
            }
        }

        private void checkRequired(Object obj) {
            if (obj == null) {
                throw new RuntimeException("Photo capture parameter missing.");
            }
        }
    }

    /**
     * Meters and triggers auto focus scan with ROI around tap point.
     * <p/>
     * Normalized coordinates are referenced to portrait preview window with
     * (0, 0) top left and (1, 1) bottom right. Rotation has no effect.
     *
     * @param nx normalized x coordinate.
     * @param ny normalized y coordinate.
     */
    void triggerFocusAndMeterAtPoint(float nx, float ny);

    /**
     * Call this to take a picture.
     *
     * @param params parameters for taking pictures.
     * @param session the capture session for this picture.
     */
    void takePicture(PhotoCaptureParameters params, CaptureSession session);

    /**
     * Sets or replaces a listener that is called whenever the camera encounters
     * an error.
     */
    void setCameraErrorListener(CameraErrorListener listener);

    /**
     * Sets or replaces a listener that is called whenever the focus state of
     * the camera changes.
     */
    void setFocusStateListener(FocusStateListener listener);

    /**
     * Sets or replaces a listener that is called whenever the state of the
     * camera changes to be either ready or not ready to take another picture.
     */
    void setReadyStateChangedListener(ReadyStateChangedListener listener);

    /**
     * Sets or replaces a listener that is called whenever the camera preview
     * receives a new frame. Callbacks are invoked on the provided {@link Handler}
     */
    void setPreviewFrameListener(PreviewFrameListener listener, Handler handler);

    /**
     * Starts a preview stream and renders it to the given surface texture.
     */
    void startPreview(SurfaceTexture surfaceTexture, CaptureReadyCallback listener);

    /**
     * Starts a preview stream and renders it to the given surface.
     */
    void startPreview(Surface surface, CaptureReadyCallback listener);

    /**
     * Sets the size of the viewfinder.
     * <p>
     * The preview size requested from the camera device will depend on this as
     * well as the requested photo/video aspect ratio.
     */
    void setViewfinderSize(int width, int height);

    /**
     * @return Whether this camera supports flash.
     * @param enhanced, if true, returns whether flash is supported in enhanced mode. If
     *        false, whether flash is supported in normal capture mode.
     */
    boolean isFlashSupported(boolean enhanced);

    /**
     * @return Whether this camera supports enhanced mode, such as HDR.
     */
    boolean isSupportingEnhancedMode();

    /**
     * Closes the camera.
     *
     * @param closeCallback Optional. Called as soon as the camera is fully closed.
     */
    void close(CloseCallback closeCallback);

    /**
     * @return A list of all supported resolutions.
     */
    Size[] getSupportedSizes();

    /**
     * @return The aspect ratio of the full size capture (usually the native
     *         resolution of the camera).
     */
    float getFullSizeAspectRatio();

    /**
     * @return Whether this camera is facing to the back.
     */
    boolean isBackFacing();

    /**
     * @return Whether this camera is facing to the front.
     */
    boolean isFrontFacing();

    /**
     * Get the maximum zoom value.
     *
     * @return A float number to represent the maximum zoom value(>= 1.0).
     */
    float getMaxZoom();

    /**
     * This function sets the current zoom ratio value.
     * <p>
     * The zoom range must be [1.0, maxZoom]. The maxZoom can be queried by
     * {@link #getMaxZoom}.
     *
     * @param zoom Zoom ratio value passed to scaler.
     */
    void setZoom(float zoom);

    /**
     * Clockwise angle through which the output image needs to be rotated
     * to be upright on the device screen in its native orientation.
     *
     * @return Degrees of clockwise rotation; always a multiple of 90
     */
    int getSensorOrientation();

    /**
     * Gets the horizontal field of view in degrees.
     */
    float getHorizontalFov();

    /**
     * Gets the vertical field of view in degrees.
     */
    float getVerticalFov();

    /**
     * Based on the selected picture size, this returns the best preview size.
     *
     * @param pictureSize the picture size as selected by the user. A camera
     *            might choose not to obey these and therefore the returned
     *            preview size might not match the aspect ratio of the given
     *            size.
     * @param context the android application context
     * @return The preview size that best matches the picture aspect ratio that
     *         will be taken.
     */
    Size pickPreviewSize(Size pictureSize, Context context);
}
