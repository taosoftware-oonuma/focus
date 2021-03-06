package com.obviousengine.android.focus;

import android.annotation.TargetApi;
import android.graphics.Point;
import android.hardware.Camera;
import android.os.Build;
import android.text.TextUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * An immutable simple size container.
 */
public class Size {

    public static final String DELIMITER = ",";

    /**
     * An helper method to build a list of this class from a list of
     * {@link android.hardware.Camera.Size}.
     *
     * @param cameraSizes Source.
     * @return The built list.
     */
    public static List<Size> buildListFromCameraSizes(List<Camera.Size> cameraSizes) {
        ArrayList<Size> list = new ArrayList<>(cameraSizes.size());
        for (Camera.Size cameraSize : cameraSizes) {
            list.add(new Size(cameraSize));
        }
        return list;
    }

    /**
     * A helper method to build a list of this class from a list of
     * {@link com.android.ex.camera2.portability.Size}.
     *
     * @param sizes Source.
     * @return The built list.
     */
    public static List<Size> buildListFromPortabilitySizes(
            List<com.android.ex.camera2.portability.Size> sizes) {
        ArrayList<Size> list = new ArrayList<>(sizes.size());
        for (com.android.ex.camera2.portability.Size size : sizes) {
            list.add(new Size(size));
        }
        return list;
    }

    /**
     * A helper method to build a list of this class from a list of {@link android.util.Size}.
     *
     * @param sizes Source.
     * @return The built list.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public static List<Size> buildListFromAndroidSizes(List<android.util.Size> sizes) {
        ArrayList<Size> list = new ArrayList<>(sizes.size());
        for (android.util.Size size : sizes) {
            list.add(new Size(size));
        }
        return list;
    }

    /**
     * A helper method to build an array of this class from a list of {@link android.util.Size}.
     *
     * @param sizes source
     * @return the convert array
     */
    public static Size[] buildArrayFromAndroidSizes(android.util.Size[] sizes) {
        Size[] converted = new Size[sizes.length];
        for (int i = 0; i < sizes.length; ++i) {
            converted[i] = new Size(sizes[i]);
        }
        return converted;
    }

    /**
     * A helper method to build an array of this class from a list of
     * {@link com.android.ex.camera2.portability.Size}.
     *
     * @param sizes source
     * @return the convert array
     */
    public static Size[] buildArrayFromPortabilitySizes(
            com.android.ex.camera2.portability.Size[] sizes) {
        Size[] converted = new Size[sizes.length];
        for (int i = 0; i < sizes.length; ++i) {
            converted[i] = new Size(sizes[i]);
        }
        return converted;
    }

    /**
     * Encode List of this class as comma-separated list of integers.
     *
     * @param sizes List of this class to encode.
     * @return encoded string.
     */
    public static String listToString(List<Size> sizes) {
        ArrayList<Integer> flatSizes = new ArrayList<>();
        for (Size s : sizes) {
            flatSizes.add(s.getWidth());
            flatSizes.add(s.getHeight());
        }
        return TextUtils.join(DELIMITER, flatSizes);
    }

    /**
     * Decode comma-separated even-length list of integers into a List of this class.
     *
     * @param encodedSizes encoded string.
     * @return List of this class.
     */
    public static List<Size> stringToList(String encodedSizes) {
        String[] flatSizes = TextUtils.split(encodedSizes, DELIMITER);
        ArrayList<Size> list = new ArrayList<>();
        for (int i = 0; i < flatSizes.length; i += 2) {
            int width = Integer.parseInt(flatSizes[i]);
            int height = Integer.parseInt(flatSizes[i + 1]);
            list.add(new Size(width,height));
        }
        return list;
    }


    private final Point val;

    /**
     * Constructor.
     */
    public Size(int width, int height) {
        val = new Point(width, height);
    }

    /**
     * Copy constructor.
     */
    public Size(Size other) {
        if (other == null) {
            val = new Point(0, 0);
        } else {
            val = new Point(other.getWidth(), other.getHeight());
        }
    }

    /**
     * Constructor from a source {@link android.hardware.Camera.Size}.
     *
     * @param other The source size.
     */
    public Size(Camera.Size other) {
        if (other == null) {
            val = new Point(0, 0);
        } else {
            val = new Point(other.width, other.height);
        }
    }

    /**
     * Constructor from a source {@link android.util.Size}.
     *
     * @param other The source size.
     */
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public Size(android.util.Size other) {
        if (other == null) {
            val = new Point(0, 0);
        } else {
            val = new Point(other.getWidth(), other.getHeight());
        }
    }

    /**
     * Constructor from a source {@link com.android.ex.camera2.portability.Size}.
     *
     * @param other The source size.
     */
    public Size(com.android.ex.camera2.portability.Size other) {
        if (other == null) {
            val = new Point(0, 0);
        } else {
            val = new Point(other.width(), other.height());
        }
    }

    /**
     * Constructor from a source {@link android.graphics.Point}.
     *
     * @param p The source size.
     */
    public Size(Point p) {
        if (p == null) {
            val = new Point(0, 0);
        } else {
            val = new Point(p);
        }
    }

    public int getWidth() {
        return val.x;
    }

    public int getHeight() {
        return val.y;
    }

    @Override
    public boolean equals(Object o) {
        if (o instanceof Size) {
            Size other = (Size) o;
            return val.equals(other.val);
        }
        return false;
    }

    @Override
    public int hashCode() {
        return val.hashCode();
    }

    @Override
    public String toString() {
        return "Size: (" + this.getWidth() + " x " + this.getHeight() + ")";
    }
}
