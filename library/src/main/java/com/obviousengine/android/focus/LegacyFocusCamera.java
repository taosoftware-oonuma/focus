package com.obviousengine.android.focus;

import static com.android.ex.camera2.portability.CameraAgent.CameraProxy;
import static com.android.ex.camera2.portability.CameraDeviceInfo.Characteristics;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.media.CameraProfile;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.view.Surface;

import java.util.List;

import com.android.ex.camera2.portability.CameraAgent;
import com.android.ex.camera2.portability.CameraCapabilities;
import com.android.ex.camera2.portability.CameraSettings;
import com.obviousengine.android.focus.debug.Log;

final class LegacyFocusCamera extends AbstractFocusCamera {

    private static final Log.Tag TAG = new Log.Tag("LegacyFocusCam");

    /** Default JPEG encoding quality. */
    private static final int JPEG_QUALITY = CameraProfile.getJpegEncodingQualityParameter(
            CameraProfile.QUALITY_HIGH);

    private static final int UPDATE_PARAM_INITIALIZE = 1;
    private static final int UPDATE_PARAM_ZOOM = 2;
    private static final int UPDATE_PARAM_PREFERENCE = 4;
    private static final int UPDATE_PARAM_ALL = -1;

    /** Number of buffers to provide for capturing preview stream.
     * TODO(eleventigers): number should be decided based on available memory
     * and the size of preview frames
     */
    private static final int NUM_PREVIEW_BUFFERS = 3;

    private final Context context;
    private final CameraAgent cameraAgent;
    private final CameraProxy cameraProxy;
    private final Characteristics characteristics;
    private final Size pictureSize;

    private CameraCapabilities cameraCapabilities;
    private CameraSettings cameraSettings;

    private boolean zoomSupported;
    private boolean focusAreaSupported;
    private boolean meteringAreaSupported;
    private boolean aeLockSupported;
    private boolean awbLockSupported;
    private boolean continuousFocusSupported;

    private float horizontalFov;
    private float verticalFov;

    /** Current zoom value. 1.0 is no zoom. */
    private float zoomValue = 1f;

    /** The surface texture onto which to render the preview. */
    private SurfaceTexture surfaceTexture;

    /** Whether closing of this device has been requested. */
    private volatile boolean isClosed = false;

    /**
     * Thread on which high-priority camera operations, such as grabbing preview
     * frames for the viewfinder, are running.
     */
    private final HandlerThread cameraThread;
    /** Handler of the {@link #cameraThread}. */
    private final Handler cameraHandler;

    private final Object autoFocusMoveCallback = Utils.HAS_AUTO_FOCUS_MOVE_CALLBACK ?
            new AutoFocusMoveCallback() : null;

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private final class AutoFocusMoveCallback implements CameraAgent.CameraAFMoveCallback {
        @Override
        public void onAutoFocusMoving(boolean moving, CameraProxy camera) {
            // TODO(eleventigers) broadcast
        }
    }

    /**
     * Camera preview data proxy. Passes camera frame buffers to
     * {@link #previewFrameListener} via a dispatcher call.
     */
    private final CameraAgent.CameraPreviewDataCallback previewDataCallback =
            new CameraAgent.CameraPreviewDataCallback() {
                @Override
                public void onPreviewFrame(byte[] data, CameraProxy camera) {
                    previewFrameDispatcher(data);
                    addPreviewCallbackBuffer(data);
                }
            };

    private PreviewFrameListener previewFrameListener;

    LegacyFocusCamera(Context context, CameraAgent cameraAgent, CameraProxy cameraProxy,
                      Characteristics characteristics, Size pictureSize) {
        Log.v(TAG, "Creating new LegacyFocusCamera");

        this.context = context.getApplicationContext();
        this.cameraAgent = cameraAgent;
        this.cameraProxy = cameraProxy;
        this.characteristics = characteristics;
        this.pictureSize = pictureSize;

        cameraThread = new HandlerThread("FocusCamera");
        cameraThread.start();
        cameraHandler = new Handler(cameraThread.getLooper());

        initializeCapabilities();
        initializeFovParameters();

        zoomValue = 1f;
        cameraSettings = this.cameraProxy.getSettings();

        setCameraParameters(UPDATE_PARAM_ALL);
    }

    @Override
    public void triggerFocusAndMeterAtPoint(float nx, float ny) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void takePicture(PhotoCaptureParameters params, CaptureSession session) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void startPreview(SurfaceTexture surfaceTexture, CaptureReadyCallback listener) {
        this.surfaceTexture = surfaceTexture;
        setupAsync(this.surfaceTexture, listener);
    }

    @Override
    public void startPreview(Surface surface, CaptureReadyCallback listener) {
       throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public void setPreviewFrameListener(PreviewFrameListener listener, Handler handler) {
        previewFrameListener = listener;
        cameraProxy.setPreviewDataCallbackWithBuffer(handler, previewDataCallback);
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
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    public void close(CloseCallback closeCallback) {
        if (isClosed) {
            Log.w(TAG, "Camera is already closed.");
            return;
        }
        cameraProxy.stopPreview();
        surfaceTexture = null;
        isClosed = true;
        cameraAgent.closeCamera(cameraProxy, true);
        cameraProxy.getCameraHandler().removeCallbacksAndMessages(null);
        if (Utils.isJellyBeanMr2OrHigher()) {
            cameraThread.quitSafely();
        } else {
            cameraThread.quit();
        }
        if (closeCallback != null) {
            closeCallback.onCameraClosed();
        }
    }

    @Override
    public Size[] getSupportedSizes() {
        List<Size> sizes = Size.buildListFromPortabilitySizes(
                cameraCapabilities.getSupportedPreviewSizes());
        return sizes.toArray(new Size[sizes.size()]);
    }

    @Override
    public float getFullSizeAspectRatio() {
        throw new UnsupportedOperationException("Not implemented yet");
    }

    @Override
    public boolean isBackFacing() {
        return characteristics.isFacingBack();
    }

    @Override
    public boolean isFrontFacing() {
        return characteristics.isFacingFront();
    }

    @Override
    public Size pickPreviewSize(Size pictureSize, Context context) {
        float pictureAspectRatio = pictureSize.getWidth() / (float) pictureSize.getHeight();
        return Utils.getOptimalPreviewSize(context, getSupportedSizes(), pictureAspectRatio);
    }

    @Override
    public float getMaxZoom() {
        return cameraCapabilities.getMaxZoomRatio();
    }

    @Override
    public int getSensorOrientation() {
        return characteristics.getSensorOrientation();
    }

    @Override
    public float getHorizontalFov() {
        return horizontalFov;
    }

    @Override
    public float getVerticalFov() {
        return verticalFov;
    }

    /**
     * Asynchronously sets up the capture session.
     *
     * @param previewTexture the surface texture onto which the preview should be
     *        rendered.
     * @param listener called when setup is completed.
     */
    private void setupAsync(final SurfaceTexture previewTexture, final CaptureReadyCallback listener) {
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                setup(previewTexture, listener);
            }
        });
    }

    /**
     * Configures and attempts to create a capture session.
     *
     * @param previewTexture the surface texture onto which the preview should be
     *        rendered.
     * @param listener called when the setup is completed.
     */
    private void setup(SurfaceTexture previewTexture, final CaptureReadyCallback listener) {
        if (cameraSettings.getCurrentFocusMode() ==
                CameraCapabilities.FocusMode.CONTINUOUS_PICTURE) {
            cameraProxy.cancelAutoFocus();
        }

        updateParametersPictureSize();
        setCameraParameters(UPDATE_PARAM_ALL);
        addPreviewCallbackBuffers(NUM_PREVIEW_BUFFERS);

        cameraProxy.setPreviewTexture(previewTexture);
        cameraProxy.startPreviewWithCallback(cameraHandler,
                new CameraAgent.CameraStartPreviewCallback() {
                    @Override
                    public void onPreviewStarted() {
                        listener.onReadyForCapture();
                    }
                });
    }

    private void addPreviewCallbackBuffer(byte[] buffer) {
        if (!isClosed) {
            cameraProxy.addCallbackBuffer(buffer);
        }
    }

    private void addPreviewCallbackBuffers(int numBuffers) {
        Size previewSize = new Size(cameraSettings.getCurrentPreviewSize());
        int previewFormat = cameraSettings.getCurrentPreviewFormat();
        int bufferNumBytes = (ImageFormat.getBitsPerPixel(previewFormat) *
                previewSize.getWidth() * previewSize.getHeight()) / 8;
        for (int i = 0; i < numBuffers; i++) {
            addPreviewCallbackBuffer(new byte[bufferNumBytes]);
        }
    }

    private void previewFrameDispatcher(byte[] data) {
        if (!isClosed && previewFrameListener != null) {
            com.android.ex.camera2.portability.Size previewSize =
                    cameraSettings.getCurrentPreviewSize();
            int width = previewSize.width();
            int height = previewSize.height();
            int format = cameraSettings.getCurrentPreviewFormat();
            previewFrameListener.onPreviewFrame(PreviewFrame.valueOf(
                    new YuvImage(data, format, width, height, null)));
        }
    }

    private void initializeCapabilities() {
        synchronized (cameraProxy) {
            cameraCapabilities = cameraProxy.getCapabilities();
            zoomSupported = cameraCapabilities
                    .supports(CameraCapabilities.Feature.ZOOM);
            focusAreaSupported = cameraCapabilities
                    .supports(CameraCapabilities.Feature.FOCUS_AREA);
            meteringAreaSupported = cameraCapabilities
                    .supports(CameraCapabilities.Feature.METERING_AREA);
            aeLockSupported = cameraCapabilities
                    .supports(CameraCapabilities.Feature.AUTO_EXPOSURE_LOCK);
            awbLockSupported = cameraCapabilities
                    .supports(CameraCapabilities.Feature.AUTO_WHITE_BALANCE_LOCK);
            continuousFocusSupported = cameraCapabilities
                    .supports(CameraCapabilities.FocusMode.CONTINUOUS_PICTURE);
        }
    }

    private void initializeFovParameters() {
        synchronized (cameraProxy) {
            try {
                Camera camera = cameraProxy.getCamera();
                Camera.Parameters parameters = camera.getParameters();
                horizontalFov = parameters.getHorizontalViewAngle();
                verticalFov = parameters.getVerticalViewAngle();
            } catch (RuntimeException e) {
              Log.e(TAG, "RuntimeException reading legacy camera FOV parameters");
            }
        }
    }

    /**
     * This method sets picture size parameters. Size parameters should only be
     * set when the preview is stopped, and so this method is only invoked in
     * {@link #setup(SurfaceTexture, CaptureReadyCallback)} just before starting the preview.
     */
    private void updateParametersPictureSize() {
        // Set a preview size that is closest to the viewfinder height and has
        // the right aspect ratio.
        Size optimalSize = pickPreviewSize(pictureSize, context);
        Size original = new Size(cameraSettings.getCurrentPreviewSize());
        if (!optimalSize.equals(original)) {
            Log.v(TAG, "setting preview size. optimal: " + optimalSize + "original: " + original);
            cameraSettings.setPreviewSize(new com.android.ex.camera2.portability.Size(
                    optimalSize.getWidth(), optimalSize.getHeight()));
            cameraProxy.applySettings(cameraSettings);
            cameraSettings = cameraProxy.getSettings();
        }

        if (optimalSize.getWidth() != 0 && optimalSize.getHeight() != 0) {
//            Log.v(TAG, "updating aspect ratio");
            // TODO(eleventigers) update aspect ratio

        }
        Log.d(TAG, "Preview size is " + optimalSize);
    }

    private void updateCameraParametersInitialize() {
        // Reset preview frame rate to the maximum because it may be lowered by
        // video camera application.
        int[] fpsRange = Utils.getPhotoPreviewFpsRange(cameraCapabilities);
        if (fpsRange != null && fpsRange.length > 0) {
            cameraSettings.setPreviewFpsRange(fpsRange[0], fpsRange[1]);
        }

        cameraSettings.setRecordingHintEnabled(false);

        if (cameraCapabilities.supports(CameraCapabilities.Feature.VIDEO_STABILIZATION)) {
            cameraSettings.setVideoStabilization(false);
        }
    }

    private void updateCameraParametersZoom() {
        if (zoomSupported) {
            cameraSettings.setZoomRatio(zoomValue);
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void updateAutoFocusMoveCallback() {
        if (cameraSettings.getCurrentFocusMode() ==
                CameraCapabilities.FocusMode.CONTINUOUS_PICTURE) {
            cameraProxy.setAutoFocusMoveCallback(cameraHandler,
                    (CameraAgent.CameraAFMoveCallback) autoFocusMoveCallback);
        } else {
            cameraProxy.setAutoFocusMoveCallback(null, null);
        }
    }

    private void updateParametersPictureQuality() {
        cameraSettings.setPhotoJpegCompressionQuality(JPEG_QUALITY);
    }

    private void updateParametersExposureCompensation() {
        setExposureCompensation(0);
    }

    private void updateCameraParametersPreference() {
        setAutoExposureLockIfSupported();
        setAutoWhiteBalanceLockIfSupported();
        setFocusAreasIfSupported();
        setMeteringAreasIfSupported();

//        cameraSettings.setFocusMode(cameraSettings.getCurrentFocusMode());

        // Set JPEG quality.
        updateParametersPictureQuality();

        // For the following settings, we need to check if the settings are
        // still supported by latest driver, if not, ignore the settings.

        // Set exposure compensation
        updateParametersExposureCompensation();

        // Set the scene mode: also sets flash and white balance.
//        updateParametersSceneMode();

        if (continuousFocusSupported && Utils.HAS_AUTO_FOCUS_MOVE_CALLBACK) {
            updateAutoFocusMoveCallback();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setAutoExposureLockIfSupported() {
        if (aeLockSupported) {
//            cameraSettings.setAutoExposureLock();
        }
    }

    @TargetApi(Build.VERSION_CODES.JELLY_BEAN)
    private void setAutoWhiteBalanceLockIfSupported() {
        if (awbLockSupported) {
//            cameraSettings.setAutoWhiteBalanceLock();
        }
    }

    private void setFocusAreasIfSupported() {
        if (focusAreaSupported) {
//            cameraSettings.setFocusAreas();
        }
    }

    private void setMeteringAreasIfSupported() {
        if (meteringAreaSupported) {
//            cameraSettings.setMeteringAreas();
        }
    }

    private void setExposureCompensation(int value) {
        int max = cameraCapabilities.getMaxExposureCompensation();
        int min = cameraCapabilities.getMinExposureCompensation();
        if (value >= min && value <= max) {
            cameraSettings.setExposureCompensationIndex(value);
        } else {
            Log.w(TAG, "invalid exposure range: " + value);
        }
    }

    private void setCameraParameters(int updateSet) {
        if ((updateSet & UPDATE_PARAM_INITIALIZE) != 0) {
            updateCameraParametersInitialize();
        }

        if ((updateSet & UPDATE_PARAM_ZOOM) != 0) {
            updateCameraParametersZoom();
        }

        if ((updateSet & UPDATE_PARAM_PREFERENCE) != 0) {
            updateCameraParametersPreference();
        }

        cameraProxy.applySettings(cameraSettings);
    }
}
