/**
 * Copyright (c) 2025, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.connectivity.resources.aidl;

import android.net.nsd.NsdServiceInfo;

// Set the descriptor explicitly so it is not modified by jarjar (b/350630377)
@Descriptor("value=com.android.connectivity.resources.aidl.descriptor.NsdServiceReceiver")
interface NsdServiceReceiver {
    oneway void onServiceFound(in NsdServiceInfo service);
    oneway void onServiceLost(in NsdServiceInfo service);
    oneway void onCancelled();
}