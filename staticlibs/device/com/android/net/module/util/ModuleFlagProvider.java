/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
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

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.Objects;

/**
 * A class that allows staticlib to easily access aconfig flags in a given process.
 *
 * <p>This class provides a singleton pattern for accessing feature flags. Each module
 * must provide its own {@link FlagProvider} implementation during initialization by
 * calling {@link #setFlagProvider(FlagProvider)}. Once provided, the provider is used for
 * all feature flag queries within that process and should never be changed.
 *
 * <p><b>Default behavior:</b> If no provider is set, all flags default to {@code false}.
 *
 * @hide
 */
public final class ModuleFlagProvider {

    /**
     * Flag to control whether to use timeout values in netlink API calls.
     */
    public static final String FLAG_NETLINK_NO_TIMEOUTS = "netlink_no_timeouts";

    private static volatile FlagProvider sInstance;

    private ModuleFlagProvider() {}

    /**
     * Sets the per-module feature flag provider.
     *
     * <p>This method should only be called once during process initialization.
     *
     * @param provider the flag provider to use
     * @throws IllegalStateException if the provider has already been set
     */
    public static void setFlagProvider(@NonNull FlagProvider provider) {
        Objects.requireNonNull(provider, "provider must not be null");
        if (sInstance != null) {
            throw new IllegalStateException("FlagProvider already set");
        }
        sInstance = provider;
    }

    /**
     * Resets the flag provider instance.
     *
     * <p>This method is for test purposes only.
     */
    @VisibleForTesting
    public static void resetForTest() {
        sInstance = null;
    }

    /**
     * Returns whether the named feature flag is enabled.
     *
     * <p>If a {@link FlagProvider} has been set via {@link #setFlagProvider(FlagProvider)},
     * this method delegates to the provider to check if the flag is enabled. If no provider
     * has been set, this method returns {@code false}.
     *
     * @param featureName the name of the feature flag to check
     * @return {@code true} if the flag is enabled, {@code false} otherwise (or if no
     *         provider is set)
     */
    public static boolean isFeatureFlagEnabled(@NonNull String featureName) {
        return sInstance != null && sInstance.isFeatureFlagEnabled(featureName);
    }

    /**
     * Interface for providing module feature flag values.
     *
     * <p>Each module should implement this interface to define how feature flags
     * are resolved. The implementation is responsible for retrieving and caching flag
     * values as appropriate for the process.
     */
    public interface FlagProvider {
        /**
         * Checks if the named feature flag is enabled.
         *
         * @param featureName the name of the feature flag to check
         * @return {@code true} if the flag is enabled, {@code false} otherwise
         */
        boolean isFeatureFlagEnabled(@NonNull String featureName);
    }
}
