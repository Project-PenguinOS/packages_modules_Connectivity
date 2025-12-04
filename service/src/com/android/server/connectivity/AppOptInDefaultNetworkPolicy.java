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

package com.android.server.connectivity;

import android.util.ArraySet;
import androidx.annotation.NonNull;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * A data class to hold the network policy information for a set of UIDs.
 * This object encapsulates the combination of network requirements for a group of UIDs
 * that share the same policy.
 */
public class AppOptInDefaultNetworkPolicy {
    private final boolean mIsSatelliteOptIn;
    private final boolean mIsSatelliteRoleSms;
    private final Set<Integer> mUids;

    public AppOptInDefaultNetworkPolicy(boolean isSatelliteOptIn, boolean isSatelliteRoleSms,
            @NonNull Set<Integer> uids) {
        mIsSatelliteOptIn = isSatelliteOptIn;
        mIsSatelliteRoleSms = isSatelliteRoleSms;
        mUids = Collections.unmodifiableSet(new ArraySet<>(uids));
    }

    public boolean isSatelliteOptIn() {
        return mIsSatelliteOptIn;
    }

    public boolean isSatelliteRoleSms() {
        return mIsSatelliteRoleSms;
    }

    @NonNull
    public Set<Integer> uids() {
        return mUids;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof AppOptInDefaultNetworkPolicy)) return false;
        AppOptInDefaultNetworkPolicy that = (AppOptInDefaultNetworkPolicy) o;
        return mIsSatelliteOptIn == that.mIsSatelliteOptIn &&
                mIsSatelliteRoleSms == that.mIsSatelliteRoleSms &&
                mUids.equals(that.mUids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mIsSatelliteOptIn, mIsSatelliteRoleSms, mUids);
    }

    @Override
    public String toString() {
        return "AppOptInDefaultNetworkPolicy{"
                + "isSatelliteOptIn="
                + mIsSatelliteOptIn
                + ", isSatelliteRoleSms="
                + mIsSatelliteRoleSms
                + ", uids="
                + mUids
                + '}';
    }
}
