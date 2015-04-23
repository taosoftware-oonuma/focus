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

import android.content.Context;
import android.os.Handler;

/**
 * The camera manager is responsible for instantiating {@link FocusCamera}
 * instances.
 */
public abstract class Focus {

    /** Set to true if Focus should force use legacy camera wrapper implementation **/
    private static final boolean DEBUG_FORCE_LEGACY = true;

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
        Focus focus = null;
        if (Utils.HAS_CAMERA_2_API && !DEBUG_FORCE_LEGACY) {
            focus = ModernFocusFactory.newInstance(context);
        }
        if (focus == null) {
            focus = LegacyFocusFactory.newInstance(context);
        }
        return focus;
    }
}
