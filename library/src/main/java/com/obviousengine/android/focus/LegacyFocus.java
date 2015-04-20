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

import android.os.Handler;

/**
 * The {@link Focus} implementation on top of the Camera 1 API
 * portability layer.
 */
class LegacyFocus extends Focus {

    @Override
    public void open(Facing facing, Size pictureSize, OpenCallback callback, Handler handler) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }

    @Override
    public boolean hasCameraFacing(Facing facing) {
        throw new UnsupportedOperationException("Not implemented yet.");
    }
}
