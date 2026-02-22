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

package com.android.server.connectivity.proxy;

import android.net.ProxyInfo;
import java.util.Optional;

/**
 * This class is responsible for coordinating the PAC script download and managing the
 * MultiPacService and MultiProxyService services. It keeps track of the PAC scripts that are
 * currently in use and ensures that the corresponding PAC components serving these scripts are
 * running.
 *
 * @hide
 */
public class PacCoordinator {
    private static final String TAG = "PacCoordinator";

    // The listener (MultiProxyTracker) that should be notified when the proxy server is running AND
    // the PAC script is downloaded and set in PacProcessor for a certain entity.
    private final MultiPacProxyInstalledListener mListener;

    public PacCoordinator(MultiPacProxyInstalledListener listener) {
        mListener = listener;
    }

    /**
     * Binds to MultiProxyService and MultiPacService instances (if not bound yet). Schedules PAC
     * script download, notifies MultiProxyService and MultiPacService to start a pair of
     * {ProxyServer; PacProcessor} for the given PAC script. Note that if the {ProxyServer;
     * PacProcessor} is already running for the current key, this is noop. mListener will be
     * notified when the PAC setup is complete.
     */
    public void startServingPacScript(ProxyInfo proxy, Optional<Integer> netId) {
        throw new UnsupportedOperationException("not implemented");
    }

    /**
     * Notifies MultiProxyService and MultiPacService to stop the pair of {ProxyServer;
     * PacProcessor} for the given PAC script and (optionally) network. Invoked by MultiProxyTracker
     * when the PAC script is no longer be used.
     */
    public void stopServingPacScript(ProxyInfo proxy, Optional<Integer> netId) {
        throw new UnsupportedOperationException("not implemented");
    }
}
