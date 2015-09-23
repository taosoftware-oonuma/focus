package com.obviousengine.android.focus;

import android.content.Context;

import com.android.ex.camera2.portability.CameraAgent;
import com.android.ex.camera2.portability.CameraAgentFactory;

/**
 * Legacy {@link Focus} factory.
 */
final class LegacyFocusFactory {

    public static Focus newInstance(Context context) throws FocusCameraException {
        CameraAgentFactory.recycle(CameraAgentFactory.CameraApi.API_1);
        CameraAgent agent = CameraAgentFactory
                .getAndroidCameraAgent(context, CameraAgentFactory.CameraApi.API_1);
        return new LegacyFocus(context, agent);
    }

    private LegacyFocusFactory() {
        throw new AssertionError("No instances");
    }
}
