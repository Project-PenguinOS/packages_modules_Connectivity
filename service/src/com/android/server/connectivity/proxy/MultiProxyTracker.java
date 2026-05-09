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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.LinkProperties;
import android.net.Network;
import android.net.ProxyInfo;
import android.os.Handler;

import com.android.server.connectivity.IProxyTracker;

/**
 * A multi-proxy tracker that can track multiple proxies for different entities.
 *
 * @hide
 */
public class MultiProxyTracker implements IProxyTracker {
    private static final String TAG = "MultiProxyTracker";

    private final Context mContext;
    private final Handler mHandler;

    public MultiProxyTracker(@NonNull Context context, @NonNull Handler handler) {
        mContext = context;
        mHandler = handler;
    }

    @Override
    public void loadGlobalProxy() {
        // TODO: Implement
    }

    @Override
    public boolean loadDeprecatedGlobalHttpProxy() {
        // TODO: Implement
        return false;
    }

    @Nullable
    @Override
    public ProxyInfo getGlobalProxy() {
        // TODO: Implement
        return null;
    }

    @Override
    public void setGlobalProxy(@Nullable ProxyInfo proxyInfo) {
        // TODO: Implement
    }

    @Override
    public void updateDefaultNetworkProxyPortForPAC(@NonNull LinkProperties lp,
            @Nullable Network network) {
        // TODO: Implement
    }

    @Override
    public void updateDefaultNetworkState(@Nullable Network newDefaultNetwork,
            @Nullable ProxyInfo proxyInfo) {
        // TODO: Implement
    }

    @Override
    public void updateNetworkProxy(@NonNull Network network, @Nullable ProxyInfo newProxyInfo,
            @Nullable ProxyInfo oldProxyInfo) {
        // TODO: Implement
    }
}
