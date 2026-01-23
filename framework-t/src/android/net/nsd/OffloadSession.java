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


package android.net.nsd;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.SystemApi;

/**
 * OffloadSession is an interface for injecting response from the OffloadEngine.
 * @hide
 */
@FlaggedApi(com.android.tethering.flags.Flags.FLAG_NSD_MDNS_SCAN_OFFLOAD)
@SystemApi
public interface OffloadSession {
    /**
     * Notifies to the NsdManager that the service is found.
     *
     * @param nsdServiceInfo The NsdServiceInfo response received from the offloaded device.
     */
    void onServiceFound(@NonNull NsdServiceInfo nsdServiceInfo);

    /**
     * Notifies to the NsdManager that the service is updated.
     *
     * @param nsdServiceInfo The NsdServiceInfo response received from the offloaded device.
     */
    void onServiceUpdated(@NonNull NsdServiceInfo nsdServiceInfo);

    /**
     * Notifies to the NsdManager that the service is lost.
     *
     * @param nsdServiceInfo The NsdServiceInfo response received from the offloaded device
     */
    void onServiceLost(@NonNull NsdServiceInfo nsdServiceInfo);
}
