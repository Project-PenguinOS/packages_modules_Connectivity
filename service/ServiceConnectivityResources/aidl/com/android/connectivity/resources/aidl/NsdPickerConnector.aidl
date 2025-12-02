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
import com.android.connectivity.resources.aidl.NsdServiceReceiver;

// Set the descriptor explicitly so it is not modified by jarjar (b/350630377)
@Descriptor("value=com.android.connectivity.resources.aidl.descriptor.NsdPickerConnector")
interface NsdPickerConnector {
    /**
     * Intent action for starting the picker.
     */
    const String ACTION_PICKER = "com.android.connectivity.resources.action.NSD_PICKER";

    /**
     * Intent extra containing the {@link NsdPickerConnector}, to include when starting the picker.
     */
    const String EXTRA_CONNECTOR = "connector";

    /**
     * Intent extra containing the user-friendly name of the app requesting discovery.
     */
    const String EXTRA_APP_NAME = "app_name";

    oneway void setServiceReceiver(in NsdServiceReceiver receiver);
    oneway void notifyServiceSelected(in NsdServiceInfo service);
}