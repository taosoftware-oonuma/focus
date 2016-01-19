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

import static com.obviousengine.android.focus.debug.Log.Tag;
import static com.obviousengine.android.focus.ConcurrentSharedRingBuffer.PinStateListener;
import static com.obviousengine.android.focus.ConcurrentSharedRingBuffer.SwapTask;
import static com.obviousengine.android.focus.ConcurrentSharedRingBuffer.Selector;

import android.annotation.TargetApi;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.CaptureResult.Key;
import android.hardware.camera2.TotalCaptureResult;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Pair;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import com.obviousengine.android.focus.debug.Log;

/**
 * Implements {@link ImageReader.OnImageAvailableListener} and
 * {@link CameraCaptureSession.CaptureCallback} to
 * store the results of capture requests (both {@link Image}s and
 * {@link TotalCaptureResult}s in a ring-buffer from which they may be saved.
 * <br>
 * This also manages the lifecycle of {@link Image}s within the application as
 * they are passed in from the lower-level camera2 API.
 */
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
final class ImageCaptureManager extends CameraCaptureSession.CaptureCallback implements
        ImageReader.OnImageAvailableListener {
    /**
     * Callback to listen for changes to the ability to capture an existing
     * image from the internal ring-buffer.
     */
    public interface CaptureReadyListener {
        /**
         * Called whenever the ability to capture an existing image from the
         * ring-buffer changes. Calls to {@link #tryCaptureExistingImage} are
         * more likely to succeed or fail depending on the value passed in to
         * this function.
         *
         * @param capturePossible true if capture is more-likely to be possible,
         *            false if capture is less-likely to be possible.
         */
        void onReadyStateChange(boolean capturePossible);
    }

    /**
     * Callback for listening to changes to individual metadata values.
     */
    public interface MetadataChangeListener {
        /**
         * This will be called whenever a metadata value changes.
         * Implementations should not take too much time to execute since this
         * will be called faster than the camera's frame rate.
         *
         * @param key the {@link CaptureResult} key this listener listens for.
         * @param second the previous value, or null if no such value existed.
         *            The type will be that associated with the
         *            {@link Key} this
         *            listener is bound to.
         * @param newValue the new value. The type will be that associated with
         *            the {@link Key}
         *            this listener is bound to.
         * @param result the CaptureResult containing the new value
         */
        void onImageMetadataChange(Key<?> key, Object second, Object newValue,
                                          CaptureResult result);
    }

    /**
     * Callback for saving an image.
     */
    public interface ImageCaptureListener {
         /**
         * Called with the {@link Image} and associated
         * {@link TotalCaptureResult}. A typical implementation would save this
         * to disk.
         * <p>
         * Note: Implementations must be thread-safe and must not close the
         * image.
         * </p>
         */
        void onImageCaptured(Image image, TotalCaptureResult captureResult);
    }

    /**
     * Callback for placing constraints on which images to capture. See
     * {@link #tryCaptureExistingImage} and {@link #captureNextImage}.
     */
    public interface CapturedImageConstraint {
        /**
         * Implementations should return true if the provided
         * TotalCaptureResults satisfies constraints necessary for the intended
         * image capture. For example, a constraint may return false if
         * {@link CaptureResult} indicates that the lens was moving during image
         * capture.
         *
         * @param captureResult The metadata associated with the image.
         * @return true if this image satisfies the constraint and can be
         *         captured, false otherwise.
         */
        boolean satisfiesConstraint(TotalCaptureResult captureResult);
    }

    /**
     * Holds an {@link Image} and {@link TotalCaptureResult} pair which may be
     * added asynchronously.
     */
    private class CapturedImage {
        /**
         * The Image and TotalCaptureResult may be received at different times
         * (via the onImageAvailableListener and onCaptureProgressed callbacks,
         * respectively).
         */
        private Image image = null;
        private TotalCaptureResult metadata = null;

        /**
         * Resets the object, closing and removing any existing image and
         * metadata.
         */
        public void reset() {
            if (image != null) {
                image.close();
                int numOpenImages = ImageCaptureManager.this.numOpenImages.decrementAndGet();
                if (DEBUG_PRINT_OPEN_IMAGE_COUNT) {
                    Log.v(TAG, "Closed an image. Number of open images = " + numOpenImages);
                }
            }

            image = null;
            metadata = null;
        }

        /**
         * @return true if both the image and metadata are present, false
         *         otherwise.
         */
        public boolean isComplete() {
            return image != null && metadata != null;
        }

        /**
         * Adds the image. Note that this can only be called once before a
         * {@link #reset()} is necessary.
         *
         * @param image the {@link Image} to add.
         */
        public void addImage(Image image) {
            if (this.image != null) {
                throw new IllegalArgumentException(
                        "Unable to add an Image when one already exists.");
            }
            this.image = image;
        }

        /**
         * Retrieves the {@link Image} if it has been added, returns null if it
         * is not available yet.
         */
        public Image tryGetImage() {
            return image;
        }

        /**
         * Adds the metadata. Note that this can only be called once before a
         * {@link #reset()} is necessary.
         *
         * @param metadata the {@link TotalCaptureResult} to add.
         */
        public void addMetadata(TotalCaptureResult metadata) {
            if (this.metadata != null) {
                throw new IllegalArgumentException(
                        "Unable to add a TotalCaptureResult when one already exists.");
            }
            this.metadata = metadata;
        }

        /**
         * Retrieves the {@link TotalCaptureResult} if it has been added,
         * returns null if it is not available yet.
         */
        public TotalCaptureResult tryGetMetadata() {
            return metadata;
        }
    }

    private static final Tag TAG = new Tag("ZSLImageListener");

    /**
     * If true, the number of open images will be printed to LogCat every time
     * an image is opened or closed.
     */
    private static final boolean DEBUG_PRINT_OPEN_IMAGE_COUNT = false;

    /**
     * The maximum duration for an onImageAvailable() callback before debugging
     * output is printed. This is a little under 1/30th of a second to enable
     * detecting jank in the preview stream caused by {@link #onImageAvailable}
     * taking too long to return.
     */
    private static final long DEBUG_MAX_IMAGE_CALLBACK_DUR = 25;

    /**
     * If spacing between onCaptureCompleted() callbacks is lower than this
     * value, camera operations at the Java level have stalled, and are now
     * catching up. In milliseconds.
     */
    private static final long DEBUG_INTERFRAME_STALL_WARNING = 5;

    /**
     * Last called to onCaptureCompleted() in SystemClock.uptimeMillis().
     */
    private long debugLastOnCaptureCompletedMillis = 0;

    /**
     * Number of frames in a row exceeding DEBUG_INTERFRAME_STALL_WARNING.
     */
    private long debugStalledFrameCount = 0;

    /**
     * Stores the ring-buffer of captured images.<br>
     * Note that this takes care of thread-safe reference counting of images to
     * ensure that they are never leaked by the app.
     */
    private final ConcurrentSharedRingBuffer<CapturedImage> capturedImageBuffer;

    /** Track the number of open images for debugging purposes. */
    private final AtomicInteger numOpenImages = new AtomicInteger(0);

    /**
     * The handler used to invoke light-weight listeners:
     * {@link CaptureReadyListener} and {@link MetadataChangeListener}.
     */
    private final Handler listenerHandler;

    /**
     * The executor used to invoke {@link ImageCaptureListener}. Note that this
     * is different from listenerHandler because a typical ImageCaptureListener
     * will compress the image to jpeg, and we may wish to execute these tasks
     * on multiple threads.
     */
    private final Executor imageCaptureListenerExecutor;

    /**
     * The set of constraints which must be satisfied for a newly acquired image
     * to be captured and sent to {@link #pendingImageCaptureCallback}. null if
     * there is no pending capture request.
     */
    private List<CapturedImageConstraint> pendingImageCaptureConstraints;

    /**
     * The callback to be invoked upon successfully capturing a newly-acquired
     * image which satisfies {@link #pendingImageCaptureConstraints}. null if
     * there is no pending capture request.
     */
    private ImageCaptureListener pendingImageCaptureCallback;

    /**
     * Map from CaptureResult key to the frame number of the capture result
     * containing the most recent value for this key and the most recent value
     * of the key.
     */
    private final Map<Key<?>, Pair<Long, Object>> metadata = new ConcurrentHashMap<>();

    /**
     * The set of callbacks to be invoked when an entry in {@link #metadata} is
     * changed.
     */
    private final Map<Key<?>, Set<MetadataChangeListener>>
            metadataChangeListeners = new ConcurrentHashMap<>();

    /**
     * @param maxImages the maximum number of images provided by the
     *            {@link ImageReader}. This must be greater than 2.
     * @param listenerHandler the handler on which to invoke listeners. Note
     *            that this should probably be on a different thread than the
     *            one used for camera operations, such as capture requests and
     *            OnImageAvailable listeners, to avoid stalling the preview.
     * @param imageCaptureListenerExecutor the executor on which to invoke image
     *            capture listeners, {@link ImageCaptureListener}.
     */
    ImageCaptureManager(int maxImages, Handler listenerHandler,
            Executor imageCaptureListenerExecutor) {
        // Ensure that there are always 2 images available for the framework to
        // continue processing frames.
        // TODO Could we make this tighter?
        capturedImageBuffer = new ConcurrentSharedRingBuffer<>(
                maxImages - 2);

        this.listenerHandler = listenerHandler;
        this.imageCaptureListenerExecutor = imageCaptureListenerExecutor;
    }

    /**
     * See {@link CaptureReadyListener}.
     */
    public void setCaptureReadyListener(final CaptureReadyListener listener) {
        capturedImageBuffer.setListener(listenerHandler,
                new PinStateListener() {
                    @Override
                    public void onPinStateChange(boolean pinsAvailable) {
                        listener.onReadyStateChange(pinsAvailable);
                    }
                });
    }

    /**
     * Adds a metadata stream listener associated with the given key.
     *
     * @param key the key of the metadata to track.
     * @param listener the listener to be invoked when the value associated with
     *            key changes.
     */
    public <T> void addMetadataChangeListener(Key<T> key, MetadataChangeListener listener) {
        if (!metadataChangeListeners.containsKey(key)) {
            // Listeners may be added to this set from a different thread than
            // that which must iterate over this set to invoke the listeners.
            // Therefore, we need a thread save hash set.
            metadataChangeListeners.put(key,
                    Collections.newSetFromMap(new ConcurrentHashMap<
                            MetadataChangeListener, Boolean>()));
        }
        metadataChangeListeners.get(key).add(listener);
    }

    /**
     * Removes the metadata stream listener associated with the given key.
     *
     * @param key the key associated with the metadata to track.
     * @param listener the listener to be invoked when the value associated with
     *            key changes.
     * @return true if the listener was removed, false if no such listener had
     *         been added.
     */
    public <T> boolean removeMetadataChangeListener(Key<T> key, MetadataChangeListener listener) {
        if (!metadataChangeListeners.containsKey(key)) {
            return false;
        } else {
            return metadataChangeListeners.get(key).remove(listener);
        }
    }

    @Override
    public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request,
            final CaptureResult partialResult) {
        long frameNumber = partialResult.getFrameNumber();

        // Update metadata for whichever keys are present, if this frame is
        // supplying newer values.
        for (final Key<?> key : partialResult.getKeys()) {
            Pair<Long, Object> oldEntry = metadata.get(key);
            final Object oldValue = (oldEntry != null) ? oldEntry.second : null;

            boolean newerValueAlreadyExists = oldEntry != null
                    && frameNumber < oldEntry.first;
            if (newerValueAlreadyExists) {
                continue;
            }

            final Object newValue = partialResult.get(key);
            metadata.put(key, new Pair<>(frameNumber, newValue));

            // If the value has changed, call the appropriate listeners, if
            // any exist.
            if (oldValue == newValue || !metadataChangeListeners.containsKey(key)) {
                continue;
            }

            for (final MetadataChangeListener listener :
                    metadataChangeListeners.get(key)) {
                Log.v(TAG, "Dispatching to metadata change listener for key: "
                        + key.toString());
                listenerHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.onImageMetadataChange(key, oldValue, newValue,
                                partialResult);
                    }
                });
            }
        }
    }

    @Override
    public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request,
            final TotalCaptureResult result) {
        final long timestamp = result.get(TotalCaptureResult.SENSOR_TIMESTAMP);

        // Detect camera thread stall.
        long now = SystemClock.uptimeMillis();
        if (now - debugLastOnCaptureCompletedMillis < DEBUG_INTERFRAME_STALL_WARNING) {
            Log.e(TAG, "Camera thread has stalled for " + ++debugStalledFrameCount +
                    " frames at # " + result.getFrameNumber() + ".");
        } else {
            debugStalledFrameCount = 0;
        }
        debugLastOnCaptureCompletedMillis = now;

        // Find the CapturedImage in the ring-buffer and attach the
        // TotalCaptureResult to it.
        // See documentation for swapLeast() for details.
        boolean swapSuccess = capturedImageBuffer.swapLeast(timestamp,
                new SwapTask<CapturedImage>() {
                @Override
                    public CapturedImage create() {
                        CapturedImage image = new CapturedImage();
                        image.addMetadata(result);
                        return image;
                    }

                @Override
                    public CapturedImage swap(CapturedImage oldElement) {
                        oldElement.reset();
                        oldElement.addMetadata(result);
                        return oldElement;
                    }

                @Override
                    public void update(CapturedImage existingElement) {
                        existingElement.addMetadata(result);
                    }
                });

        if (!swapSuccess) {
            // Do nothing on failure to swap in.
            Log.v(TAG, "Unable to add new image metadata to ring-buffer.");
        }

        tryExecutePendingCaptureRequest(timestamp);
    }

    @Override
    public void onImageAvailable(ImageReader reader) {
        long startTime = SystemClock.currentThreadTimeMillis();

        final Image img = reader.acquireLatestImage();

        if (img != null) {
            int numOpenImages = this.numOpenImages.incrementAndGet();
            if (DEBUG_PRINT_OPEN_IMAGE_COUNT) {
                Log.v(TAG, "Acquired an image. Number of open images = " + numOpenImages);
            }

            // Try to place the newly-acquired image into the ring buffer.
            boolean swapSuccess = capturedImageBuffer.swapLeast(
                    img.getTimestamp(), new SwapTask<CapturedImage>() {
                            @Override
                        public CapturedImage create() {
                            CapturedImage image = new CapturedImage();
                            image.addImage(img);
                            return image;
                        }

                            @Override
                        public CapturedImage swap(CapturedImage oldElement) {
                            oldElement.reset();
                            oldElement.addImage(img);
                            return oldElement;
                        }

                            @Override
                        public void update(CapturedImage existingElement) {
                            existingElement.addImage(img);
                        }
                    });

            if (!swapSuccess) {
                // If we were unable to save the image to the ring buffer, we
                // must close it now.
                // We should only get here if the ring buffer is closed.
                img.close();
                numOpenImages = this.numOpenImages.decrementAndGet();
                if (DEBUG_PRINT_OPEN_IMAGE_COUNT) {
                    Log.v(TAG, "Closed an image. Number of open images = " + numOpenImages);
                }
            }

            tryExecutePendingCaptureRequest(img.getTimestamp());

            long endTime = SystemClock.currentThreadTimeMillis();
            long totTime = endTime - startTime;
            if (totTime > DEBUG_MAX_IMAGE_CALLBACK_DUR) {
                // If it takes too long to swap elements, we will start skipping
                // preview frames, resulting in visible jank.
                Log.v(TAG, "onImageAvailable() took " + totTime + "ms");
            }
        }
    }

    /**
     * Closes the listener, eventually freeing all currently-held {@link Image}
     * s.
     */
    public void close() {
        try {
            capturedImageBuffer.close(new Task<CapturedImage>() {
                @Override
                public void run(CapturedImage e) {
                    e.reset();
                }
            });
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    /**
     * Sets the pending image capture request, overriding any previous calls to
     * {@link #captureNextImage} which have not yet been resolved. When the next
     * available image which satisfies the given constraints can be captured,
     * onImageCaptured will be invoked.
     *
     * @param onImageCaptured the callback which will be invoked with the
     *            captured image.
     * @param constraints the set of constraints which must be satisfied in
     *            order for the image to be captured.
     */
    public void captureNextImage(final ImageCaptureListener onImageCaptured,
            final List<CapturedImageConstraint> constraints) {
        pendingImageCaptureCallback = onImageCaptured;
        pendingImageCaptureConstraints = constraints;
    }

    /**
     * Tries to resolve any pending image capture requests.
     *
     * @param newImageTimestamp the timestamp of a newly-acquired image which
     *            should be captured if appropriate and possible.
     */
    private void tryExecutePendingCaptureRequest(long newImageTimestamp) {
        if (pendingImageCaptureCallback != null) {
            final Pair<Long, CapturedImage> pinnedImage = capturedImageBuffer.tryPin(
                    newImageTimestamp);
            if (pinnedImage != null) {
                CapturedImage image = pinnedImage.second;

                if (!image.isComplete()) {
                    capturedImageBuffer.release(pinnedImage.first);
                    return;
                }

                // Check to see if the image satisfies all constraints.
                TotalCaptureResult captureResult = image.tryGetMetadata();

                if (pendingImageCaptureConstraints != null) {
                    for (CapturedImageConstraint constraint : pendingImageCaptureConstraints) {
                        if (!constraint.satisfiesConstraint(captureResult)) {
                            capturedImageBuffer.release(pinnedImage.first);
                            return;
                        }
                    }
                }

                // If we get here, the image satisfies all the necessary
                // constraints.

                if (tryExecuteCaptureOrRelease(pinnedImage, pendingImageCaptureCallback)) {
                    // If we successfully handed the image off to the callback,
                    // remove the pending
                    // capture request.
                    pendingImageCaptureCallback = null;
                    pendingImageCaptureConstraints = null;
                }
            }
        }
    }

    /**
     * Tries to capture an existing image from the ring-buffer, if one exists
     * that satisfies the given constraint and can be pinned.
     *
     * @return true if the image could be captured, false otherwise.
     */
    public boolean tryCaptureExistingImage(final ImageCaptureListener onImageCaptured,
            final List<CapturedImageConstraint> constraints) {
        // The selector to use in choosing the image to capture.
        Selector<CapturedImage> selector;

        if (constraints == null || constraints.isEmpty()) {
            // If there are no constraints, use a trivial Selector.
            selector = new Selector<CapturedImage>() {
                    @Override
                public boolean select(CapturedImage image) {
                    return true;
                }
            };
        } else {
            // If there are constraints, create a Selector which will return
            // true if all constraints
            // are satisfied.
            selector = new Selector<CapturedImage>() {
                    @Override
                public boolean select(CapturedImage e) {
                    // If this image already has metadata associated with it,
                    // then use it.
                    // Otherwise, we can't block until it's available, so assume
                    // it doesn't
                    // satisfy the required constraints.
                    TotalCaptureResult captureResult = e.tryGetMetadata();

                    if (captureResult == null || e.tryGetImage() == null) {
                        return false;
                    }

                    for (CapturedImageConstraint constraint : constraints) {
                        if (!constraint.satisfiesConstraint(captureResult)) {
                            return false;
                        }
                    }
                    return true;
                }
            };
        }

        // Acquire a lock (pin) on the most recent (greatest-timestamp) image in
        // the ring buffer which satisfies our constraints.
        // Note that this must be released as soon as we are done with it.
        final Pair<Long, CapturedImage> toCapture = capturedImageBuffer.tryPinGreatestSelected(
                selector);

        return tryExecuteCaptureOrRelease(toCapture, onImageCaptured);
    }

    /**
     * Tries to execute the image capture callback with the pinned CapturedImage
     * provided.
     *
     * @param toCapture The pinned CapturedImage to pass to the callback, or
     *            release on failure.
     * @param callback The callback to execute.
     * @return true upon success, false upon failure and the release of the
     *         pinned image.
     */
    private boolean tryExecuteCaptureOrRelease(final Pair<Long, CapturedImage> toCapture,
            final ImageCaptureListener callback) {
        if (toCapture == null) {
            return false;
        } else {
            try {
                imageCaptureListenerExecutor.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            CapturedImage img = toCapture.second;
                            callback.onImageCaptured(img.tryGetImage(),
                                    img.tryGetMetadata());
                        } finally {
                            capturedImageBuffer.release(toCapture.first);
                        }
                    }
                });
            } catch (RejectedExecutionException e) {
                // We may get here if the thread pool has been closed.
                capturedImageBuffer.release(toCapture.first);
                return false;
            }

            return true;
        }
    }
}
