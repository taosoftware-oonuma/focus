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

import android.os.Handler;
import android.util.Pair;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Semaphore;

/**
 * Implements a thread-safe fixed-size pool map of integers to objects such that
 * the least element may be swapped out for a new element at any time. Elements
 * may be temporarily "pinned" for processing in separate threads, during which
 * they will not be swapped out. <br>
 * This class enforces the invariant that a new element can always be swapped
 * in. Thus, requests to pin an element for a particular task may be denied if
 * there are not enough unpinned elements which can be removed. <br>
 */
final class ConcurrentSharedRingBuffer<E> {
    private static final Tag TAG = new Tag("CncrrntShrdRingBuf");

    /**
     * Callback interface for swapping elements at the head of the buffer.
     */
    public interface SwapTask<E> {
        /**
         * Called if the buffer is under-capacity and a new element is being
         * added.
         *
         * @return the new element to add.
         */
        E create();

        /**
         * Called if the buffer is full and an old element must be swapped out
         * to make room for the new element.
         *
         * @param oldElement the element being removed from the buffer.
         * @return the new element to add.
         */
        E swap(E oldElement);

        /**
         * Called if the buffer already has an element with the specified key.
         * Note that the element may currently be pinned for processing by other
         * elements. Therefore, implementations must be thread safe with respect
         * to any other operations which may be applied to pinned tasks.
         *
         * @param existingElement the element to be updated.
         */
        void update(E existingElement);
    }

    /**
     * Callback for selecting an element to pin. See
     * {@link com.obviousengine.android.focus.ConcurrentSharedRingBuffer#tryPinGreatestSelected}.
     */
    public interface Selector<E> {
        /**
         * @param element The element to select or not select.
         * @return true if the element should be selected, false otherwise.
         */
        boolean select(E element);
    }

    public static interface PinStateListener {
        /**
         * Invoked whenever the ability to pin an element for processing
         * changes.
         *
         * @param pinsAvailable If true, requests to pin elements (e.g. calls to
         *            pinGreatest()) are less-likely to fail. If false, they are
         *            more-likely to fail.
         */
        void onPinStateChange(boolean pinsAvailable);
    }

    /**
     * Wraps E with reference counting.
     */
    private static class Pinnable<E> {
        private E element;

        /** Reference-counting for the number of tasks holding this element. */
        private int pins;

        public Pinnable(E element) {
            this.element = element;
            pins = 0;
        }

        public E getElement() {
            return element;
        }

        private boolean isPinned() {
            return pins > 0;
        }
    }

    /** Allow only one swapping operation at a time. */
    private final Object swapLock = new Object();
    /**
     * Lock all transactions involving elements, unpinnedElements,
     * capacitySemaphore, pinSemaphore, closed, pinStateHandler, and
     * pinStateListener and the state of Pinnable instances. <br>
     * TODO Replace this with a priority semaphore and allow swapLeast()
     * operations to run faster at the expense of slower tryPin()/release()
     * calls.
     */
    private final Object lock = new Object();
    /** Stores all elements. */
    private TreeMap<Long, Pinnable<E>> elements;
    /** Stores the subset of elements which is not pinned. */
    private TreeMap<Long, Pinnable<E>> unpinnedElements;
    /** Used to acquire space in elements. */
    private final Semaphore capacitySemaphore;
    /** This must be acquired while an element is pinned. */
    private final Semaphore pinSemaphore;
    private boolean closed = false;

    private Handler pinStateHandler = null;
    private PinStateListener pinStateListener = null;

    /**
     * Constructs a new ring buffer with the specified capacity.
     *
     * @param capacity the maximum number of elements to store.
     */
    public ConcurrentSharedRingBuffer(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("Capacity must be positive.");
        }

        elements = new TreeMap<Long, Pinnable<E>>();
        unpinnedElements = new TreeMap<Long, Pinnable<E>>();
        capacitySemaphore = new Semaphore(capacity);
        // Start with -1 permits to pin elements since we must always have at
        // least one unpinned
        // element available to swap out as the head of the buffer.
        pinSemaphore = new Semaphore(-1);
    }

    /**
     * Sets or replaces the listener.
     *
     * @param handler The handler on which to invoke the listener.
     * @param listener The listener to be called whenever the ability to pin an
     *            element changes.
     */
    public void setListener(Handler handler, PinStateListener listener) {
        synchronized (lock) {
            pinStateHandler = handler;
            pinStateListener = listener;
        }
    }

    /**
     * Places a new element in the ring buffer, removing the least (by key)
     * non-pinned element if necessary. The existing element (or {@code null} if
     * the buffer is under-capacity) is passed to {@code swapper.swap()} and the
     * result is saved to the buffer. If an entry with {@code newKey} already
     * exists in the ring-buffer, then {@code swapper.update()} is called and
     * may modify the element in-place. See {@link SwapTask}. <br>
     * Note that this method is the only way to add new elements to the buffer
     * and will never be blocked on pinned tasks.
     *
     * @param newKey the key with which to store the swapped-in element.
     * @param swapper the callback used to perform the swap.
     * @return true if the swap was successful and the new element was saved to
     *         the buffer, false if the swap was not possible and the element
     *         was not saved to the buffer. Note that if the swap failed,
     *         {@code swapper.create()} may or may not have been invoked.
     */
    public boolean swapLeast(long newKey, SwapTask<E> swapper) {
        synchronized (swapLock) {
            Pinnable<E> existingElement = null;

            synchronized (lock) {
                if (closed) {
                    return false;
                }
                existingElement = elements.get(newKey);
            }

            if (existingElement != null) {
                swapper.update(existingElement.getElement());
                return true;
            }

            if (capacitySemaphore.tryAcquire()) {
                // If we are under capacity, insert the new element and return.
                Pinnable<E> p = new Pinnable<E>(swapper.create());

                synchronized (lock) {
                    if (closed) {
                        return false;
                    }

                    // Add the new element and release another permit to pin
                    // allow pinning another element.
                    elements.put(newKey, p);
                    unpinnedElements.put(newKey, p);
                    pinSemaphore.release();
                    if (pinSemaphore.availablePermits() == 1) {
                        notifyPinStateChange(true);
                    }
                }

                return true;
            } else {
                Pinnable<E> toSwap;

                // Note that this method must be synchronized to avoid
                // attempting to remove more than one unpinned element at a
                // time.
                synchronized (lock) {
                    if (closed) {
                        return false;
                    }

                    Map.Entry<Long, Pinnable<E>> toSwapEntry = unpinnedElements.pollFirstEntry();

                    if (toSwapEntry == null) {
                        // We should never get here.
                        throw new RuntimeException("No unpinned element available.");
                    }

                    toSwap = toSwapEntry.getValue();

                    // We must remove the element from both elements and
                    // unpinnedElements because it must be re-added after the
                    // swap to be placed in the correct order with newKey.
                    elements.remove(toSwapEntry.getKey());
                }

                try {
                    toSwap.element = swapper.swap(toSwap.element);
                } finally {
                    synchronized (lock) {
                        if (closed) {
                            return false;
                        }

                        elements.put(newKey, toSwap);
                        unpinnedElements.put(newKey, toSwap);
                    }
                }
                return true;
            }
        }
    }

    /**
     * Attempts to pin the element with the given key and return it. <br>
     * Note that, if a non-null pair is returned, the caller <em>must</em> call
     * {@link #release} with the key.
     *
     * @return the key and object of the pinned element, if one could be pinned,
     *         or null.
     */
    public Pair<Long, E> tryPin(long key) {

        boolean acquiredLastPin = false;
        Pinnable<E> entry = null;

        synchronized (lock) {
            if (closed) {
                return null;
            }

            if (elements.isEmpty()) {
                return null;
            }

            entry = elements.get(key);

            if (entry == null) {
                return null;
            }

            if (entry.isPinned()) {
                // If the element is already pinned by another task, simply
                // increment the pin count.
                entry.pins++;
            } else {
                // We must ensure that there will still be an unpinned element
                // after we pin this one.
                if (pinSemaphore.tryAcquire()) {
                    unpinnedElements.remove(key);
                    entry.pins++;

                    acquiredLastPin = pinSemaphore.availablePermits() <= 0;
                } else {
                    return null;
                }
            }
        }

        // If we just grabbed the last permit, we must notify listeners of the
        // pin
        // state change.
        if (acquiredLastPin) {
            notifyPinStateChange(false);
        }

        return Pair.create(key, entry.getElement());
    }

    public void release(long key) {
        synchronized (lock) {
            // Note that this must proceed even if the buffer has been closed.

            Pinnable<E> element = elements.get(key);

            if (element == null) {
                throw new InvalidParameterException("No entry found for the given key.");
            }

            if (!element.isPinned()) {
                throw new IllegalArgumentException("Calling release() with unpinned element.");
            }

            // Unpin the element
            element.pins--;

            if (!element.isPinned()) {
                // If there are now 0 tasks pinning this element...
                unpinnedElements.put(key, element);

                // Allow pinning another element.
                pinSemaphore.release();

                if (pinSemaphore.availablePermits() == 1) {
                    notifyPinStateChange(true);
                }
            }
        }
    }

    /**
     * Attempts to pin the greatest element and return it. <br>
     * Note that, if a non-null element is returned, the caller <em>must</em>
     * call {@link #release} with the element. Furthermore, behavior is
     * undefined if the element's {@code compareTo} behavior changes between
     * these calls.
     *
     * @return the key and object of the pinned element, if one could be pinned,
     *         or null.
     */
    public Pair<Long, E> tryPinGreatest() {
        synchronized (lock) {
            if (closed) {
                return null;
            }

            if (elements.isEmpty()) {
                return null;
            }

            return tryPin(elements.lastKey());
        }
    }

    /**
     * Attempts to pin the greatest element for which {@code selector} returns
     * true. <br>
     *
     * @see #pinGreatest
     */
    public Pair<Long, E> tryPinGreatestSelected(Selector<E> selector) {
        // (Quickly) get the list of elements to search through.
        ArrayList<Long> keys = new ArrayList<Long>();
        synchronized (lock) {
            if (closed) {
                return null;
            }

            if (elements.isEmpty()) {
                return null;
            }

            keys.addAll(elements.keySet());
        }

        Collections.sort(keys);

        // Pin each element, from greatest key to least, until we find the one
        // we want (the element with the greatest key for which
        // selector.selected() returns true).
        for (int i = keys.size() - 1; i >= 0; i--) {
            Pair<Long, E> pinnedCandidate = tryPin(keys.get(i));
            if (pinnedCandidate != null) {
                boolean selected = false;

                try {
                    selected = selector.select(pinnedCandidate.second);
                } finally {
                    // Don't leak pinnedCandidate if the above select() threw an
                    // exception.
                    if (selected) {
                        return pinnedCandidate;
                    } else {
                        release(pinnedCandidate.first);
                    }
                }
            }
        }

        return null;
    }

    /**
     * Removes all elements from the buffer, running {@code task} on each one,
     * and waiting, if necessary, for all pins to be released.
     *
     * @param task
     * @throws InterruptedException
     */
    public void close(Task<E> task) throws InterruptedException {
        int numPinnedElements;

        // Ensure that any pending swap tasks complete before closing.
        synchronized (swapLock) {
            synchronized (lock) {
                closed = true;
                numPinnedElements = elements.size() - unpinnedElements.size();
            }
        }

        notifyPinStateChange(false);

        // Wait for all pinned tasks to complete.
        if (numPinnedElements > 0) {
            pinSemaphore.acquire(numPinnedElements);
        }

        for (Pinnable<E> element : elements.values()) {
            task.run(element.element);
        }

        unpinnedElements.clear();

        elements.clear();
    }

    private void notifyPinStateChange(final boolean pinsAvailable) {
        synchronized (lock) {
            // We must synchronize on pinStateHandler and pinStateListener.
            if (pinStateHandler != null) {
                final PinStateListener listener = pinStateListener;
                pinStateHandler.post(new Runnable() {
                        @Override
                    public void run() {
                        listener.onPinStateChange(pinsAvailable);
                    }
                });
            }
        }
    }
}
