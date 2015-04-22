package com.obviousengine.android.focus;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.support.annotation.Nullable;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.obviousengine.android.focus.debug.Log;

/**
 * On older Android version trying to load {@link android.hardware.camera2}
 * will cause {@link VerifyError} therefore we use this separate factory class
 * to setup a Camera2 backed {@link Focus} implementation.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class ModernFocusFactory {

    private static Log.Tag TAG = new Log.Tag("Camera2FocusHlpr");

    @Nullable
    public static Focus newInstance(Context context) throws FocusCameraException {
        DisplayMetrics displayMetrics = getDisplayMetrics(context);
        CameraManager cameraManager;
        try {
            cameraManager = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        } catch (IllegalStateException ex) {
            cameraManager = null;
            Log.e(TAG, "Could not get camera service v2", ex);
        }
        if (cameraManager != null && isCamera2Supported(cameraManager)) {
            return new DefaultFocus(context, cameraManager, displayMetrics);
        }
        return null;
    }

    /**
     * Returns whether the device fully supports API2
     *
     * @param cameraManager the Camera2 API manager.
     * @return If this device is only emulating Camera2 API on top of an older
     *         HAL (such as the Nexus 4, 7 or 10), this method returns false. It
     *         only returns true, if Camera2 is fully supported through newer
     *         HALs.
     * @throws FocusCameraException Thrown if an error occurred while trying to
     *             access the camera.
     */
    private static boolean isCamera2Supported(CameraManager cameraManager)
            throws FocusCameraException {
        if (!Utils.HAS_CAMERA_2_API) {
            return false;
        }
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            if (cameraIds.length == 0) {
                throw new FocusCameraException("Camera 2 API supported but no devices available.");
            }
            final String id = cameraIds[0];
            // TODO: We should check for all the flags we need to ensure the
            // device is capable of taking Camera2 API shots. For now, let's
            // accept all device that are either 'partial' or 'full' devices
            // (but not legacy).
            return cameraManager.getCameraCharacteristics(id).get(
                    CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)
                    != CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY;
        } catch (CameraAccessException ex) {
            Log.e(TAG, "Could not access camera to determine hardware-level API support.");
            return false;
        }
    }

    private static DisplayMetrics getDisplayMetrics(Context context) {
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager wm = (WindowManager)
                context.getSystemService(Context.WINDOW_SERVICE);
        if (wm != null) {
            displayMetrics = new DisplayMetrics();
            wm.getDefaultDisplay().getMetrics(displayMetrics);
        }
        return displayMetrics;
    }

    private ModernFocusFactory() {
        throw new AssertionError("No instances");
    }
}
