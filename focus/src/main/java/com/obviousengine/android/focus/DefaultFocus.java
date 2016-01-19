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

import static com.obviousengine.android.focus.FocusCamera.Facing;
import static com.obviousengine.android.focus.FocusCamera.OpenCallback;

import android.annotation.TargetApi;
import android.content.Context;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.os.Build;
import android.os.Handler;
import android.support.annotation.NonNull;
import android.util.DisplayMetrics;

import timber.log.Timber;

/**
 * The {@link Focus} implementation on top of Camera2 API.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class DefaultFocus extends Focus {

    private final Context context;
    private final CameraManager cameraManager;
    private final DisplayMetrics displayMetrics;

    /**
     * Instantiates a new {@link Focus} for Camera2 API.
     *
     * @param context application context
     * @param cameraManager the underlying Camera2 camera manager.
     * @param displayMetrics display metrics
     */
    DefaultFocus(Context context, CameraManager cameraManager, DisplayMetrics displayMetrics) {
        this.context = context.getApplicationContext();
        this.cameraManager = cameraManager;
        this.displayMetrics = displayMetrics;
    }

    @Override
    public void open(Facing facing, final Size pictureSize,
                     final OpenCallback openCallback, Handler handler) {
        try {
            final String cameraId = getCameraId(facing);
            Timber.i("Opening Camera ID %s", cameraId);
            cameraManager.openCamera(cameraId, new CameraDevice.StateCallback() {
                // We may get multiple calls to StateCallback, but only the
                // first callback indicates the status of the camera-opening
                // operation.  For example, we may receive onOpened() and later
                // onClosed(), but only the first should be relayed to
                // openCallback.
                private boolean isFirstCallback = true;

                @Override
                public void onDisconnected(@NonNull CameraDevice device) {
                    if (isFirstCallback) {
                        isFirstCallback = false;
                        // If the camera is disconnected before it is opened
                        // then we must call close.
                        device.close();
                        openCallback.onCameraClosed();
                    }
                }

                @Override
                public void onClosed(@NonNull CameraDevice device) {
                    if (isFirstCallback) {
                        isFirstCallback = false;
                        openCallback.onCameraClosed();
                    }
                }

                @Override
                public void onError(@NonNull CameraDevice device, int error) {
                    if (isFirstCallback) {
                        isFirstCallback = false;
                        device.close();
                        openCallback.onFailure();
                    }
                }

                @Override
                public void onOpened(@NonNull CameraDevice device) {
                    if (isFirstCallback) {
                        isFirstCallback = false;
                        try {
                            CameraCharacteristics characteristics = cameraManager
                                    .getCameraCharacteristics(device.getId());
                            FocusCamera focusCamera = new DefaultFocusCamera(
                                    device, characteristics, pictureSize);
                            openCallback.onCameraOpened(focusCamera);
                        } catch (CameraAccessException e) {
                            Timber.d("Could not get camera characteristics");
                            openCallback.onFailure();
                        }
                    }
                }
            }, handler);
        } catch (CameraAccessException | UnsupportedOperationException ex) {
            Timber.e(ex, "Could not open camera");
            handler.post(new Runnable() {
                @Override
                public void run() {
                    openCallback.onFailure();
                }
            });
        }
    }

    @Override
    public boolean hasCameraFacing(Facing facing) {
        return getFirstCameraFacing(facing == Facing.FRONT ? CameraCharacteristics.LENS_FACING_FRONT
                : CameraCharacteristics.LENS_FACING_BACK) != null;
    }

    /** Returns the ID of the first camera facing the given direction. */
    private String getCameraId(Facing facing) {
        if (facing == Facing.FRONT) {
            return getFirstFrontCameraId();
        } else {
            return getFirstBackCameraId();
        }
    }

    /** Returns the ID of the first back-facing camera. */
    private String getFirstBackCameraId() {
        Timber.d("Getting First BACK Camera");
        String cameraId = getFirstCameraFacing(CameraCharacteristics.LENS_FACING_BACK);
        if (cameraId == null) {
            throw new RuntimeException("No back-facing camera found.");
        }
        return cameraId;
    }

    /** Returns the ID of the first front-facing camera. */
    private String getFirstFrontCameraId() {
        Timber.d("Getting First FRONT Camera");
        String cameraId = getFirstCameraFacing(CameraCharacteristics.LENS_FACING_FRONT);
        if (cameraId == null) {
            throw new RuntimeException("No front-facing camera found.");
        }
        return cameraId;
    }

    /** Returns the ID of the first camera facing the given direction. */
    private String getFirstCameraFacing(int facing) {
        try {
            String[] cameraIds = cameraManager.getCameraIdList();
            for (String cameraId : cameraIds) {
                CameraCharacteristics characteristics = cameraManager
                        .getCameraCharacteristics(cameraId);
                if (characteristics.get(CameraCharacteristics.LENS_FACING) == facing) {
                    return cameraId;
                }
            }
            return null;
        } catch (CameraAccessException ex) {
            throw new RuntimeException("Unable to get camera ID", ex);
        }
    }
}
