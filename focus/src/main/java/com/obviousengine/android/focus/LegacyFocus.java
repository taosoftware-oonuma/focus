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

import android.content.Context;

import android.os.Handler;

import com.android.ex.camera2.portability.CameraAgent;
import com.android.ex.camera2.portability.CameraDeviceInfo;
import com.obviousengine.android.focus.debug.Log;

/**
 * The {@link Focus} implementation on top of the portability layer.
 */
final class LegacyFocus extends Focus {

    private static final Log.Tag TAG = new Log.Tag("LegacyFocus");

    private final Context context;
    private final CameraAgent cameraAgent;

    public LegacyFocus(Context context, CameraAgent cameraAgent) {
        this.context = context.getApplicationContext();
        this.cameraAgent = cameraAgent;
    }

    //TODO: be smarter when re-opening -
    // https://github.com/obviousengineering/android-camera2-app/blob/master/src/com/android/camera/app/CameraController.java#L206
    @Override
    public void open(Facing facing, final Size pictureSize,
                     final OpenCallback openCallback, Handler handler) {
        final int cameraId = getCameraId(facing);
        Log.d(TAG, "Opening Camera ID " + cameraId);
        cameraAgent.openCamera(handler, cameraId, new CameraAgent.CameraOpenCallback() {

            @Override
            public void onCameraOpened(CameraAgent.CameraProxy camera) {
                Log.v(TAG, "onCameraOpened(" + camera + ")");
                CameraDeviceInfo.Characteristics characteristics = camera.getCharacteristics();
                FocusCamera focusCamera = new LegacyFocusCamera(context, cameraAgent,
                        camera, characteristics, pictureSize);
                openCallback.onCameraOpened(focusCamera);
            }

            @Override
            public void onCameraDisabled(int cameraId) {
                Log.w(TAG, "onCameraDisabled(" + cameraId + ")");
                openCallback.onCameraClosed();
            }

            @Override
            public void onDeviceOpenFailure(int cameraId, String info) {
                Log.w(TAG, "onDeviceOpenFailure(" + cameraId + ", " + info + ")");
                openCallback.onFailure();
            }

            @Override
            public void onDeviceOpenedAlready(int cameraId, String info) {
                Log.w(TAG, "onDeviceOpenedAlready(" + cameraId + ", " + info + ")");
                openCallback.onFailure();
            }

            @Override
            public void onReconnectionFailure(CameraAgent mgr, String info) {
                Log.w(TAG, "onReconnectionFailure(" + cameraId + ")");
                openCallback.onFailure();
            }
        });
    }

    @Override
    public boolean hasCameraFacing(Facing facing) {
        return getFirstCameraFacing(facing) != -1;
    }

    /** Returns the ID of the first camera facing the given direction. */
    private int getCameraId(Facing facing) {
        if (facing == Facing.FRONT) {
            return getFirstFrontCameraId();
        } else {
            return getFirstBackCameraId();
        }
    }

    /** Returns the ID of the first back-facing camera. */
    private int getFirstBackCameraId() {
        Log.d(TAG, "Getting First BACK Camera");
        int cameraId = getFirstCameraFacing(Facing.BACK);
        if (cameraId == -1) {
            throw new RuntimeException("No back-facing camera found.");
        }
        return cameraId;
    }

    /** Returns the ID of the first front-facing camera. */
    private int getFirstFrontCameraId() {
        Log.d(TAG, "Getting First FRONT Camera");
        int cameraId = getFirstCameraFacing(Facing.FRONT);
        if (cameraId == -1) {
            throw new RuntimeException("No front-facing camera found.");
        }
        return cameraId;
    }

    /** Returns the ID of the first camera facing the given direction. */
    private int getFirstCameraFacing(Facing facing) {
        CameraDeviceInfo deviceInfo = cameraAgent.getCameraDeviceInfo();
        int numberOfCameras = deviceInfo.getNumberOfCameras();
        for (int i = 0; i < numberOfCameras; i++) {
            CameraDeviceInfo.Characteristics characteristics = deviceInfo
                    .getCharacteristics(i);
            if (facing == Facing.BACK && characteristics.isFacingBack()) {
                return i;
            }
            if (facing == Facing.FRONT && characteristics.isFacingFront()) {
                return i;
            }
        }
        return -1;
    }
}
