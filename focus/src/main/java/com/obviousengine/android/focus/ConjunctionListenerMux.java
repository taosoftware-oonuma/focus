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

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;

/**
 * Enables thread-safe multiplexing of multiple input boolean states into a
 * single listener to be invoked upon change in the conjunction (logical AND) of
 * all inputs.
 */
final class ConjunctionListenerMux<Input extends Enum<Input>> {
    /**
     * Callback for listening to changes to the conjunction of all inputs.
     */
    public interface OutputChangeListener {
        /**
         * Called whenever the conjunction of all inputs changes. Listeners MUST
         * NOT call {@link #setInput} while still registered as a listener, as
         * this will result in infinite recursion.
         *
         * @param state the conjunction of all input values.
         */
        void onOutputChange(boolean state);
    }

    /** Mutex for inputs and state. */
    private final Object lock = new Object();
    /** Stores the current input state. */
    private final EnumMap<Input, Boolean> inputs;
    /** The current output state */
    private boolean output;
    /**
     * The set of listeners to notify when the output (the conjunction of all
     * inputs) changes.
     */
    private final List<OutputChangeListener> listeners = Collections.synchronizedList(
            new ArrayList<OutputChangeListener>());

    public void addListener(OutputChangeListener listener) {
        listeners.add(listener);
    }

    public void removeListener(OutputChangeListener listener) {
        listeners.remove(listener);
    }

    public boolean getOutput() {
        synchronized (lock) {
            return output;
        }
    }

    /**
     * Updates the state of the given input, dispatching to all output change
     * listeners if the output changes.
     *
     * @param input the input to change.
     * @param newValue the new value of the input.
     * @return The new output.
     */
    public boolean setInput(Input input, boolean newValue) {
        synchronized (lock) {
            inputs.put(input, newValue);

            // If the new input value is the same as the existing output,
            // then nothing will change.
            if (newValue == output) {
                return output;
            } else {
                boolean oldOutput = output;

                // Recompute the output by AND'ing all the inputs.
                output = true;
                for (Boolean b : inputs.values()) {
                    output &= b;
                }

                // If the output has changed, notify the listeners.
                if (oldOutput != output) {
                    notifyListeners();
                }

                return output;
            }
        }
    }

    public ConjunctionListenerMux(Class<Input> clazz, OutputChangeListener listener) {
        this(clazz);
        addListener(listener);
    }

    public ConjunctionListenerMux(Class<Input> clazz) {
        inputs = new EnumMap<>(clazz);

        for (Input i : clazz.getEnumConstants()) {
            inputs.put(i, false);
        }

        output = false;
    }

    /**
     * Notifies all listeners of the current state, regardless of whether or not
     * it has actually changed.
     */
    public void notifyListeners() {
        synchronized (lock) {
            for (OutputChangeListener listener : listeners) {
                listener.onOutputChange(output);
            }
        }
    }
}
