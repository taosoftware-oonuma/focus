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

import static com.obviousengine.android.focus.FocusCamera.OpenCallback;
import static com.obviousengine.android.focus.FocusCamera.Facing;
import static com.obviousengine.android.focus.debug.Log.Tag;

import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import com.obviousengine.android.focus.debug.Log;


/**
 * The camera manager is responsible for instantiating {@link FocusCamera}
 * instances.
 */
public abstract class Focus {

    private static Tag TAG = new Tag("Focus");

    /**
     * Attempts to open the camera facing the given direction with the given
     * capture size.
     *
     * Exactly one call will always be made to a single method in the provided
     * {@link OpenCallback}.
     *
     * @param facing which camera to open. The first camera found in the given
     *            direction will be opened.
     * @param captureSize the capture size. This must be one of the supported
     *            sizes.
     * @param callback this listener is called when the camera was opened or
     *            when it failed to open.
     * @param handler the handler on which callback methods are invoked.
     */
    public abstract void open(Facing facing, Size captureSize,
                              OpenCallback callback, Handler handler);

    /**
     * Returns whether the device has a camera facing the given direction.
     */
    public abstract boolean hasCameraFacing(Facing facing);

    /**
     * Creates a camera manager that is based on Camera2 API, if available, or
     * otherwise uses the portability layer API.
     *
     * @throws FocusCameraException Thrown if an error occurred while trying to
     *             access the camera.
     */
    public static Focus get(Context context) throws FocusCameraException {
        return create(context);
    }

    /**
     * Creates a new camera manager that is based on Camera2 API, if available,
     * or otherwise uses the portability API.
     *
     * @throws FocusCameraException Thrown if an error occurred while trying to
     *             access the camera.
     */
    private static Focus create(Context context) throws FocusCameraException {
        DisplayMetrics displayMetrics = getDisplayMetrics(context);
        CameraManager cameraManager = null;

        try {
            cameraManager = Utils.HAS_CAMERA_2_API ? (CameraManager) context
                    .getSystemService(Context.CAMERA_SERVICE) : null;
        } catch (IllegalStateException ex) {
            cameraManager = null;
            Log.e(TAG, "Could not get camera service v2", ex);
        }
        if (cameraManager != null && isCamera2Supported(cameraManager)) {
            return new DefaultFocus(context, cameraManager, displayMetrics);
        } else {
            return new LegacyFocus();
        }
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
}
