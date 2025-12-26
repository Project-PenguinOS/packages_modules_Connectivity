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

import android.annotation.IntDef;
import android.util.ArraySet;
import androidx.annotation.NonNull;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Collections;
import java.util.Objects;
import java.util.Set;

/**
 * A data class to hold the network policy information for a set of UIDs.
 * This object encapsulates the combination of network requirements for a group of UIDs
 * that share the same policy.
 */
public class AppOptInDefaultNetworkPolicy {

    /**
     * A bitmask of flags representing the network policies that can be applied to a UID.
     *
     * <p>These flags are used to create a unique integer key for each combination of
     * policies, allowing for efficient grouping of UIDs that share the same network
     * requirements.
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef(flag = true, prefix = { "POLICY_" }, value = {
            POLICY_NONE,
            POLICY_SATELLITE_ROLE_SMS,
            POLICY_SATELLITE_OPT_IN,
            POLICY_OTT,
    })
    public @interface Policy {}

    /** No specific network policy applies. */
    public static final int POLICY_NONE = 0;

    /**
     * A policy flag indicating that the UID has the SMS role and is eligible for
     * satellite fallback, including access to restricted networks.
     */
    public static final int POLICY_SATELLITE_ROLE_SMS = 1 << 0;

    /**
     * A policy flag indicating that the UID belongs to an application that has
     * opted-in for satellite network access via its manifest metadata.
     */
    public static final int POLICY_SATELLITE_OPT_IN = 1 << 1;

    /**
     * A policy flag indicating that the UID is ott call and is eligible for network slicing
     */
    public static final int POLICY_OTT = 1 << 2;

    @Policy
    private final int mPolicyFlags;
    private final Set<Integer> mUids;

    /**
     * Constructor accepts an integer bitmask of policy flags.
     */
    public AppOptInDefaultNetworkPolicy(@Policy int policyFlags, @NonNull Set<Integer> uids) {
        mPolicyFlags = policyFlags;
        mUids = Collections.unmodifiableSet(new ArraySet<>(uids));
    }

    public boolean isSatelliteOptIn() {
        return (mPolicyFlags & POLICY_SATELLITE_OPT_IN) != 0;
    }

    public boolean isSatelliteRoleSms() {
        return (mPolicyFlags & POLICY_SATELLITE_ROLE_SMS) != 0;
    }

    public boolean isOtt() {
        return (mPolicyFlags & POLICY_OTT) != 0;
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
        return mPolicyFlags == that.mPolicyFlags && mUids.equals(that.mUids);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mPolicyFlags, mUids);
    }

    @Override
    public String toString() {
        return "AppOptInDefaultNetworkPolicy{"
                + "policyFlags=" + mPolicyFlags
                + ", uids=" + mUids + '}';
    }
}
