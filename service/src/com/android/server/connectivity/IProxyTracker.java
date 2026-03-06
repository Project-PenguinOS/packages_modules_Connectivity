/*
 * Copyright (C) 2026 The Android Open Source Project
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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ProxyInfo;

/**
 * Interface for tracking global and per-network proxies.
 *
 * @hide
 */
public interface IProxyTracker {
    /** Read the global proxy settings and cache them in memory. */
    void loadGlobalProxy();

    /**
     * Read the global proxy from the deprecated Settings.Global.HTTP_PROXY setting and apply it.
     * Returns {@code true} when global proxy was set successfully from deprecated setting.
     */
    boolean loadDeprecatedGlobalHttpProxy();

    /**
     * Gets the global proxy.
     *
     * @return The global proxy or null if none.
     */
    @Nullable
    ProxyInfo getGlobalProxy();

    /**
     * Sets the global proxy in memory. Also writes the values to the global settings of the device.
     *
     * @param proxyInfo the proxy spec, or null for no proxy.
     */
    void setGlobalProxy(@Nullable ProxyInfo proxyInfo);

    /**
     * Adjust the proxy in the link properties if necessary.
     *
     * @param lp the LinkProperties to fix up.
     * @param network the network of the local proxy server.
     */
    void updateDefaultNetworkProxyPortForPAC(@NonNull LinkProperties lp, @Nullable Network network);

    /**
     * Handles a change in the system's default network and its associated proxy configuration.
     *
     * <p>This method updates the tracker's internal state to reflect the new default network. If
     * the provided {@link ProxyInfo} differs from the current effective default proxy, it triggers
     * an update to apply the new proxy settings.
     *
     * @param newDefaultNetwork The new default network, or null if no default.
     * @param proxyInfo The new proxyInfo, or null if no proxy.
     */
    void updateDefaultNetworkState(
            @Nullable Network newDefaultNetwork, @Nullable ProxyInfo proxyInfo);

    /**
     * Called when the HTTP proxy for a specific network has changed within its LinkProperties. If
     * `network` is the system default network, then it should also setup the PAC services, if
     * required.
     *
     * @param network The Network whose proxy changed.
     * @param newProxyInfo The new ProxyInfo for the network.
     * @param oldProxyInfo The old ProxyInfo for the network.
     */
    void updateNetworkProxy(
            @NonNull Network network,
            @Nullable ProxyInfo newProxyInfo,
            @Nullable ProxyInfo oldProxyInfo);
}
