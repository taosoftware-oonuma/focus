/*
 * Copyright (C) 2013 The Android Open Source Project
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

import android.location.Location;
import android.net.Uri;

import com.obviousengine.android.focus.exif.ExifInterface;

/**
 * A session is an item that is in progress of being created and saved, such as
 * a photo sphere or HDR+ photo.
 */
public interface CaptureSession {
    /**
     * Classes implementing this interface can listen to progress updates of
     * this session.
     */
    interface ProgressListener {
        /**
         * Called when the progress is changed.
         *
         * @param progressPercent The current progress in percent.
         */
        void onProgressChanged(int progressPercent);

        /**
         * Called when the progress message is changed.
         *
         * @param message The current progress message.
         */
        void onStatusMessageChanged(CharSequence message);
    }

    /**
     * An interface defining the callback when a image is saved.
     */
    interface OnImageSavedListener {
        /**
         * The callback when the saving is done in the background.
         * @param uri The final content Uri of the saved image.
         */
        void onImageSaved(Uri uri);
    }

    /** Returns the title/name of this session. */
    String getTitle();

    /** Returns the location of this session or null. */
    Location getLocation();

    /** Sets the location of this session. */
    void setLocation(Location location);

    /**
     * Set the progress in percent for the current session. If set to or left at
     * 0, no progress bar is shown.
     */
    void setProgress(int percent);

    /**
     * Returns the progress of this session in percent.
     */
    int getProgress();

    /**
     * Returns the current progress message.
     */
    CharSequence getProgressMessage();

    /**
     * Starts the session by adding a placeholder to the filmstrip and adding
     * notifications.
     *
     * @param placeholder a valid encoded bitmap to be used as the placeholder.
     * @param progressMessage the message to be used to the progress
     *            notification initially. This can later be changed using
     *            {@link #setProgressMessage(CharSequence)}.
     */
    void startSession(byte[] placeholder, CharSequence progressMessage);

    /**
     * Starts the session by marking the item as in-progress and adding
     * notifications.
     *
     * @param uri the URI of the item to be re-processed.
     * @param progressMessage the message to be used to the progress
     *            notification initially. This can later be changed using
     *            {@link #setProgressMessage(CharSequence)}.
     */
    void startSession(Uri uri, CharSequence progressMessage);

    /**
     * Start a session like this if it's not processing for a long time and
     * therefore doesn't need a temporary placeholder or a progress message.
     */
    void startEmpty();

    /**
     * Cancel the session without a final result. The session will be removed
     * from the film strip, progress notifications will be cancelled.
     */
    void cancel();

    /**
     * Changes the progress status message of this session.
     *
     * @param message the new message
     */
    void setProgressMessage(CharSequence message);

    /**
     * Finish the session by saving the image to disk.
     */
    void saveAndFinish(byte[] data, int width, int height, int orientation,
                       ExifInterface exif, OnImageSavedListener listener);

    /**
     * Finishes the session.
     */
    void finish();

    /**
     * Finish the session and indicate it failed.
     */
    void finishWithFailure(CharSequence reason);

    /**
     * Returns the path to the final output of this session. This is only
     * available after startSession has been called.
     */
    String getPath();

    /**
     * Returns the URI to the final output of this session. This is only available
     * after startSession has been called.
     */
    Uri getUri();

    /**
     * Returns the Content URI to the final output of this session. This is only
     * available if the session has been finished.
     *
     * Returns null if it has not been finished.
     */
    Uri getContentUri();

    /**
     * Whether this session already has a path. This is the case once it has
     * been started. False is returned, if the session has not been started yet
     * and no path is available.
     */
    boolean hasPath();

    /**
     * Updates the preview from a file. {@link #onPreviewAvailable()} will be
     * invoked upon completion.
     *
     * @param previewPath The path to the file.
     */
    void updatePreview(String previewPath);

    /**
     * Called when the preview is already available.
     */
    void onPreviewAvailable();

    /**
     * Adds a progress listener to this session.
     */
    void addProgressListener(ProgressListener listener);

    /**
     * Removes the given progress listener from this session.
     */
    void removeProgressListener(ProgressListener listener);
}
