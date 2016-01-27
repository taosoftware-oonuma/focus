/*
 * com.obviousengine.android.focus.LegacyParameters
 *
 * Created by eleventigers on 19/01/16 17:39.
 * Copyright (c) 2016 Obvious Engineering. All rights reserved.
 */

package com.obviousengine.android.focus;

import android.hardware.Camera;
import timber.log.Timber;

@SuppressWarnings("deprecation")
final class LegacyParameters {

    private final float horizontalFov;
    private final float verticalFov;
    private final float focalLength;

    private LegacyParameters(float horizontalFov, float verticalFov, float focalLength) {
        this.horizontalFov = horizontalFov;
        this.verticalFov = verticalFov;
        this.focalLength = focalLength;
    }

    public static LegacyParameters create(String cameraId) {
        try {
            int id = Integer.valueOf(cameraId);
            return create(id);
        } catch (NumberFormatException e) {
            Timber.e(e, "Failure converting camera id");
            return new LegacyParameters(0, 0, 0);
        }
    }

    public static LegacyParameters create(int cameraId) {
        Camera camera = null;
        try {
            camera = Camera.open(cameraId);
            Camera.Parameters parameters = camera.getParameters();
            return new LegacyParameters(parameters.getHorizontalViewAngle(),
                                        parameters.getVerticalViewAngle(),
                                        parameters.getFocalLength());
        } catch (RuntimeException e) {
                Timber.e(e, "Failure reading legacy camera FOV parameters");
                return new LegacyParameters(0, 0, 0);
        } finally {
            if (camera != null) {
                camera.release();
            }
        }
    }

    public float getHorizontalFov() {
        return horizontalFov;
    }

    public float getVerticalFov() {
        return verticalFov;
    }

    public float getFocalLength() {
        return focalLength;
    }
}
