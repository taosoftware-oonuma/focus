package com.obviousengine.android.focus;

import android.annotation.TargetApi;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Point;
import android.graphics.PointF;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.os.Build;
import android.view.OrientationEventListener;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import com.android.ex.camera2.portability.CameraCapabilities;
import com.obviousengine.android.focus.debug.Log;
import com.obviousengine.android.focus.exif.ExifInterface;

public final class Utils {

    private static final Log.Tag TAG = new Log.Tag("Utils");

    private final static int MAX_PREVIEW_FPS_TIMES_1000 = 400000;
    private final static int PREFERRED_PREVIEW_FPS_TIMES_1000 = 30000;

    static final boolean HAS_AUTO_FOCUS_MOVE_CALLBACK = hasAutoFocusMoveCallback();

    static final boolean IS_NEXUS_4 = "mako".equalsIgnoreCase(Build.DEVICE);
    static final boolean IS_NEXUS_5 = "LGE".equalsIgnoreCase(Build.MANUFACTURER)
            && "hammerhead".equalsIgnoreCase(Build.DEVICE);
    static final boolean IS_NEXUS_6 = "motorola".equalsIgnoreCase(Build.MANUFACTURER)
            && "shamu".equalsIgnoreCase(Build.DEVICE);
    static final boolean IS_NEXUS_9 = "htc".equalsIgnoreCase(Build.MANUFACTURER)
            && ("flounder".equalsIgnoreCase(Build.DEVICE)
            || "flounder_lte".equalsIgnoreCase(Build.DEVICE));
    static final boolean IS_HTC = "htc".equalsIgnoreCase(Build.MANUFACTURER);

    static final boolean HAS_CAMERA_2_API = isLOrHigher();

    static boolean isLOrHigher() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP
                || "L".equals(Build.VERSION.CODENAME);
    }

    static boolean isJellyBeanMr2OrHigher() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2;
    }

    static boolean hasAutoFocusMoveCallback() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN;
    }

    /**
     * Deletes the given directory and all it's contents, including
     * sub-directories.
     *
     * @param directory The directory to delete.
     * @return Whether The deletion was a success.
     */
    static boolean deleteDirectoryRecursively(File directory) {
        if (!directory.exists() || !directory.isDirectory()) {
            return false;
        }

        for (File entry : directory.listFiles()) {
            if (entry.isDirectory()) {
                deleteDirectoryRecursively(entry);
            }
            if (!entry.delete()) {
                return false;
            }
        }
        return directory.delete();
    }

    /**
     * Reads the content of a {@code File} as a byte array.
     *
     * @param file The file to read
     * @return  The content of the file
     * @throws java.io.IOException if the content of the {@code File} could not be read
     */
    static byte[] readFileToByteArray(File file) throws IOException {
        int length = (int) file.length();
        byte[] data = new byte[length];
        FileInputStream stream = new FileInputStream(file);
        try {
            int offset = 0;
            while (offset < length) {
                offset += stream.read(data, offset, length - offset);
            }
        } catch (IOException e) {
            throw e;
        } finally {
            stream.close();
        }
        return data;
    }

    /**
     * Clamps x to between min and max (inclusive on both ends, x = min --> min,
     * x = max --> max).
     */
    static int clamp(int x, int min, int max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    /**
     * Clamps x to between min and max (inclusive on both ends, x = min --> min,
     * x = max --> max).
     */
    static float clamp(float x, float min, float max) {
        if (x > max) {
            return max;
        }
        if (x < min) {
            return min;
        }
        return x;
    }

    /**
     * Linear interpolation between a and b by the fraction t. t = 0 --> a, t =
     * 1 --> b.
     */
    static float lerp(float a, float b, float t) {
        return a + t * (b - a);
    }

    /**
     * Given (nx, ny) \in [0, 1]^2, in the display's portrait coordinate system,
     * returns normalized sensor coordinates \in [0, 1]^2 depending on how
     * the sensor's orientation \in {0, 90, 180, 270}.
     *
     * <p>
     * Returns null if sensorOrientation is not one of the above.
     * </p>
     */
    public static PointF normalizedSensorCoordsForNormalizedDisplayCoords(
            float nx, float ny, int sensorOrientation) {
        switch (sensorOrientation) {
            case 0:
                return new PointF(nx, ny);
            case 90:
                return new PointF(ny, 1.0f - nx);
            case 180:
                return new PointF(1.0f - nx, 1.0f - ny);
            case 270:
                return new PointF(1.0f - ny, nx);
            default:
                return null;
        }
    }

    public static int getDeviceNaturalOrientation(Context context) {
        Configuration config = context.getResources().getConfiguration();
        int rotation = getDisplayRotation(context);

        if (((rotation == Surface.ROTATION_0 || rotation == Surface.ROTATION_180) &&
                config.orientation == Configuration.ORIENTATION_LANDSCAPE) ||
                ((rotation == Surface.ROTATION_90 || rotation == Surface.ROTATION_270) &&
                        config.orientation == Configuration.ORIENTATION_PORTRAIT)) {
            return Configuration.ORIENTATION_LANDSCAPE;
        } else {
            return Configuration.ORIENTATION_PORTRAIT;
        }
    }


    public static int getDisplayRotation(Context context) {
        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        int rotation = windowManager.getDefaultDisplay().getRotation();
        switch (rotation) {
            case Surface.ROTATION_0:
                return 0;
            case Surface.ROTATION_90:
                return 90;
            case Surface.ROTATION_180:
                return 180;
            case Surface.ROTATION_270:
                return 270;
        }
        return 0;
    }

    public static Size getOptimalPreviewSize(Context context, Size[] sizes, double targetRatio) {
        // TODO(andyhuibers): Don't hardcode this but use device's measurements.
        final int MAX_ASPECT_HEIGHT = 1080;

        // Count sizes with getHeight <= 1080p to mimic camera1 api behavior.
        int count = 0;
        for (Size s : sizes) {
            if (s.getHeight() <= MAX_ASPECT_HEIGHT) {
                count++;
            }
        }
        ArrayList<Size> camera1Sizes = new ArrayList<>(count);

        // Set array of all sizes with getHeight <= 1080p
        for (Size s : sizes) {
            if (s.getHeight() <= MAX_ASPECT_HEIGHT) {
                camera1Sizes.add(new Size(s.getWidth(), s.getHeight()));
            }
        }

        int optimalIndex = getOptimalPreviewSizeIndex(context, camera1Sizes, targetRatio);

        if (optimalIndex == -1) {
            return null;
        }

        Size optimal = camera1Sizes.get(optimalIndex);
        for (Size s : sizes) {
            if (s.getWidth() == optimal.getWidth() && s.getHeight() == optimal.getHeight()) {
                return s;
            }
        }
        return null;
    }

    public static int getOptimalPreviewSizeIndex(Context context, List<Size> sizes,
                                                 double targetRatio) {
        // Use a very small tolerance because we want an exact match.
        final double ASPECT_TOLERANCE;
        // HTC 4:3 ratios is over .01 from true 4:3
        if (IS_HTC && targetRatio > 1.3433 && targetRatio < 1.35) {
            Log.w(TAG, "4:3 ratio out of normal tolerance, increasing tolerance to 0.02");
            ASPECT_TOLERANCE = 0.02;
        } else {
            ASPECT_TOLERANCE = 0.01;
        }
        if (sizes == null) {
            return -1;
        }

        int optimalSizeIndex = -1;
        double minDiff = Double.MAX_VALUE;

        // Because of bugs of overlay and layout, we sometimes will try to
        // layout the viewfinder in the portrait orientation and thus get the
        // wrong size of preview surface. When we change the preview size, the
        // new overlay will be created before the old one closed, which causes
        // an exception. For now, just get the screen size.
        Size defaultDisplaySize = getDefaultDisplaySize(context);
        int targetHeight = Math.min(defaultDisplaySize.getWidth(), defaultDisplaySize.getHeight());
        // Try to find an size match aspect ratio and size
        for (int i = 0; i < sizes.size(); i++) {
            Size size = sizes.get(i);
            double ratio = (double) size.getWidth() / size.getHeight();
            if (Math.abs(ratio - targetRatio) > ASPECT_TOLERANCE) {
                continue;
            }

            double heightDiff = Math.abs(size.getHeight() - targetHeight);
            if (heightDiff < minDiff) {
                optimalSizeIndex = i;
                minDiff = heightDiff;
            } else if (heightDiff == minDiff) {
                // Prefer resolutions smaller-than-display when an equally close
                // larger-than-display resolution is available
                if (size.getHeight() < targetHeight) {
                    optimalSizeIndex = i;
                    minDiff = heightDiff;
                }
            }
        }
        // Cannot find the one match the aspect ratio. This should not happen.
        // Ignore the requirement.
        if (optimalSizeIndex == -1) {
            Log.w(TAG, "No preview size match the aspect ratio. available sizes: " + sizes);
            minDiff = Double.MAX_VALUE;
            for (int i = 0; i < sizes.size(); i++) {
                Size size = sizes.get(i);
                if (Math.abs(size.getHeight() - targetHeight) < minDiff) {
                    optimalSizeIndex = i;
                    minDiff = Math.abs(size.getHeight() - targetHeight);
                }
            }
        }

        return optimalSizeIndex;
    }

    /**
     * For still image capture, we need to get the right fps range such that the
     * camera can slow down the framerate to allow for less-noisy/dark
     * viewfinder output in dark conditions.
     *
     * @param capabilities Camera's capabilities.
     * @return null if no appropiate fps range can't be found. Otherwise, return
     *         the right range.
     */
    static int[] getPhotoPreviewFpsRange(CameraCapabilities capabilities) {
        return getPhotoPreviewFpsRange(capabilities.getSupportedPreviewFpsRange());
    }

    static int[] getPhotoPreviewFpsRange(List<int[]> frameRates) {
        if (frameRates.size() == 0) {
            Log.e(TAG, "No supported frame rates returned!");
            return null;
        }

        // Find the lowest min rate in supported ranges who can cover 30fps.
        int lowestMinRate = MAX_PREVIEW_FPS_TIMES_1000;
        for (int[] rate : frameRates) {
            int minFps = rate[0];
            int maxFps = rate[1];
            if (maxFps >= PREFERRED_PREVIEW_FPS_TIMES_1000 &&
                    minFps <= PREFERRED_PREVIEW_FPS_TIMES_1000 &&
                    minFps < lowestMinRate) {
                lowestMinRate = minFps;
            }
        }

        // Find all the modes with the lowest min rate found above, the pick the
        // one with highest max rate.
        int resultIndex = -1;
        int highestMaxRate = 0;
        for (int i = 0; i < frameRates.size(); i++) {
            int[] rate = frameRates.get(i);
            int minFps = rate[0];
            int maxFps = rate[1];
            if (minFps == lowestMinRate && highestMaxRate < maxFps) {
                highestMaxRate = maxFps;
                resultIndex = i;
            }
        }

        if (resultIndex >= 0) {
            return frameRates.get(resultIndex);
        }
        Log.e(TAG, "Can't find an appropiate frame rate range!");
        return null;
    }

    static int[] getMaxPreviewFpsRange(List<int[]> frameRates) {
        if (frameRates != null && frameRates.size() > 0) {
            // The list is sorted. Return the last element.
            return frameRates.get(frameRates.size() - 1);
        }
        return new int[0];
    }

    public static Size getDefaultDisplaySize(Context context) {
        WindowManager windowManager = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        Point res = new Point();
        windowManager.getDefaultDisplay().getSize(res);
        return new Size(res.x, res.y);
    }

    /**
     * Given the device orientation and Camera2 characteristics, this returns
     * the required JPEG rotation for this camera.
     *
     * @param deviceOrientationDegrees the device orientation in degrees.
     * @return The JPEG orientation in degrees.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static int getJpegRotation(int deviceOrientationDegrees,
                                      CameraCharacteristics characteristics) {
        if (deviceOrientationDegrees == OrientationEventListener.ORIENTATION_UNKNOWN) {
            return 0;
        }
        int facing = characteristics.get(CameraCharacteristics.LENS_FACING);
        int sensorOrientation = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
        if (facing == CameraMetadata.LENS_FACING_FRONT) {
            return (sensorOrientation + deviceOrientationDegrees) % 360;
        } else {
            return (sensorOrientation - deviceOrientationDegrees + 360) % 360;
        }
    }

    public static ExifInterface getExif(byte[] jpegData) {
        ExifInterface exif = new ExifInterface();
        try {
            exif.readExif(jpegData);
        } catch (IOException e) {
            Log.w(TAG, "Failed to read EXIF data", e);
        }
        return exif;
    }

    // Returns the degrees in clockwise. Values are 0, 90, 180, or 270.
    public static int getOrientation(ExifInterface exif) {
        Integer val = exif.getTagIntValue(ExifInterface.TAG_ORIENTATION);
        if (val == null) {
            return 0;
        } else {
            return ExifInterface.getRotationForOrientationValue(val.shortValue());
        }
    }

    public static int getOrientation(byte[] jpegData) {
        if (jpegData == null) return 0;

        ExifInterface exif = getExif(jpegData);
        return getOrientation(exif);
    }

    /**
     * Gets the number of cores available in this device, across all processors.
     * Requires: Ability to peruse the filesystem at "/sys/devices/system/cpu"
     * <p>
     * Source: http://stackoverflow.com/questions/7962155/
     *
     * @return The number of cores, or 1 if failed to get result
     */
    static int getNumCpuCores() {
        // Private Class to display only CPU devices in the directory listing
        class CpuFilter implements java.io.FileFilter {
            @Override
            public boolean accept(java.io.File pathname) {
                // Check if filename is "cpu", followed by a single digit number
                if (java.util.regex.Pattern.matches("cpu[0-9]+", pathname.getName())) {
                    return true;
                }
                return false;
            }
        }

        try {
            // Get directory containing CPU info
            java.io.File dir = new java.io.File("/sys/devices/system/cpu/");
            // Filter to only list the devices we care about
            java.io.File[] files = dir.listFiles(new CpuFilter());
            // Return the number of cores (virtual CPU devices)
            return files.length;
        } catch (Exception e) {
            // Default to return 1 core
            Log.e(TAG, "Failed to count number of cores, defaulting to 1", e);
            return 1;
        }
    }

    private Utils() {
        throw new AssertionError("No instances");
    }
}
