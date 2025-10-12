/*
 * Copyright (C) 2025 The Android Open Source Project
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

package com.android.net.module.util;

import android.os.Build;
import android.system.ErrnoException;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.net.module.util.Struct.Bool;
import com.android.net.module.util.Struct.S32;

import java.util.Objects;

/**
 * A wrapper class for a BPF map that is used to store a single boolean value.
 * This class simplifies interaction by abstracting away the underlying map
 * structure and the constant key (0). It provides a simple get/set interface
 * for a kernel-backed boolean flag.
 */
@RequiresApi(Build.VERSION_CODES.S)
public class BpfBoolean {

    private final IBpfMap<S32, Bool> mMap;
    private static final S32 KEY_ZERO = new S32(0);
    private static final Bool VALUE_FALSE = new Bool(false);
    private static final Bool VALUE_TRUE = new Bool(true);

    /**
     * Creates a BpfBoolean wrapper for the eBPF map at the specified filesystem path.
     *
     * @param path The absolute path to the pinned eBPF map file.
     * @param exclusive Whether to open the map in exclusive mode (and thus cache lookups).
     * @throws ErrnoException if the BPF map cannot be opened or accessed.
     */
    public BpfBoolean(@NonNull final String path, boolean exclusive) throws ErrnoException {
        if (exclusive) {
            mMap = SingleWriterBpfMap.getSingleton(path, S32.class, Bool.class);
        } else {
            mMap = new BpfMap<>(path, BpfMap.BPF_F_RDWR, S32.class, Bool.class);
        }
    }

    /**
     * Creates a BpfBoolean wrapper using a pre-existing IBpfMap instance.
     * This constructor is primarily useful for testing with mock or pre-configured maps.
     *
     * @param map The IBpfMap instance to wrap. Must not be null. The map's key must be
     * S32 and its value must be Bool.
     */
    public BpfBoolean(@NonNull final IBpfMap<S32, Bool> map) {
        mMap = Objects.requireNonNull(map, "BpfMap instance cannot be null");
    }

    /**
     * Retrieves the current state of the boolean flag.
     *
     * @return {@code true} if the flag is set in the map, otherwise {@code false}.
     * If the entry does not exist in the map, it defaults to {@code false}.
     * @throws ErrnoException if there is an error reading from the map.
     */
    public boolean get() throws ErrnoException {
        final Bool value = mMap.getValue(KEY_ZERO);
        // If the value is null (key not present), treat it as false.
        return (value != null) && value.val;
    }

    /**
     * Sets the boolean flag to a specific value.
     * This will create the entry if it doesn't exist or update it if it does.
     *
     * @param value The boolean value to set.
     * @throws ErrnoException if there is an error writing to the map.
     */
    public void set(boolean value) throws ErrnoException {
        mMap.updateEntry(KEY_ZERO, value ? VALUE_TRUE : VALUE_FALSE);
    }

    /**
     * A convenience method to set the flag to {@code true}.
     *
     * @throws ErrnoException if there is an error writing to the map.
     */
    public void set() throws ErrnoException {
        set(true);
    }

    /**
     * A convenience method to set the flag to {@code false}.
     *
     * @throws ErrnoException if there is an error writing to the map.
     */
    public void clear() throws ErrnoException {
        set(false);
    }
}
