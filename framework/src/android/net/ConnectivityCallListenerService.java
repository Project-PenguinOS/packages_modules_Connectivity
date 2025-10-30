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

package android.net;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SystemApi;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Process;
import android.os.UserHandle;
import android.telecom.Call;
import android.telecom.InCallService;
import android.telecom.PhoneAccount;
import android.telecom.PhoneAccountHandle;
import android.telecom.TelecomManager;
import android.util.Log;
import com.android.tethering.flags.Flags;

/**
 * An {@link InCallService} that monitors the state of calls to influence system-level network
 * behavior based on call activity.
 *
 * <p>This service acts as a listener for call state changes (e.g., call added, call removed) and
 * can trigger actions within the connectivity subsystem. Its purpose is to bridge the gap between
 * the telecom framework and network policy management, allowing call status to inform network
 * preferences.
 *
 * <h3>Expected Telecom Callback Behavior</h3>
 * <p>As an {@link InCallService}, this service's functionality relies on the timing and
 * correctness of the {@link #onCallAdded(Call)} and {@link #onCallRemoved(Call)} callbacks
 * from the Telecom framework. This service expects the following behavior from Telecom:
 * <ul>
 *     <li><b>Multiple calls from the same application:</b> If a single application
 *     has multiple concurrent calls, the Telecom framework is expected to trigger
 *     {@link #onCallAdded(Call)} for the first call and {@link #onCallRemoved(Call)}
 *     only after the last call has ended.</li>
 *
 *     <li><b>Call Termination:</b> This service relies on the Telecom framework
 *     to issue an {@link #onCallRemoved(Call)} event for any reason a call ends. This includes
 *     normal hangup, an application crash, or an application uninstall while a call is active.
 *     This service does not independently track application lifecycle events.</li>
 * </ul>
 *
 * @hide
 */
@SystemApi(client = SystemApi.Client.MODULE_LIBRARIES)
@FlaggedApi(Flags.FLAG_ENABLE_INCALL_SERVICE_API)
public class ConnectivityCallListenerService extends InCallService {
    private static final String TAG = "ConnCallListenerSvc";
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private PackageManager mPackageManager;
    private ConnectivityManager mConnectivityManager;
    private static final int APPLICATION_INFO_FLAGS = 0;
    private boolean mSupportOttNetworkSlicing;
    private TelecomManager mTelecomManager;

    @Override
    public void onCreate() {
        super.onCreate();
        if (DBG) Log.d(TAG, "onCreate() called");
        mPackageManager = getPackageManager();
        mConnectivityManager = getSystemService(ConnectivityManager.class);
        mTelecomManager = getSystemService(TelecomManager.class);
        mSupportOttNetworkSlicing = mConnectivityManager
                .isFeatureEnabled(ConnectivityManager.FEATURE_OTT_NETWORK_SLICING);
        if (DBG) Log.d(TAG, "ott network slicing status:" + mSupportOttNetworkSlicing);
    }

    @Override
    public void onCallAdded(@Nullable Call call) {
        super.onCallAdded(call);
        if (DBG) Log.d(TAG, "onCallAdded called");

        if (!mSupportOttNetworkSlicing) {
            if (DBG) Log.d(TAG, "ott network slicing feature is disabled");
            return;
        }
        handleCallUpdate(call, true /* isAdd */);
    }

    @Override
    public void onCallRemoved(@Nullable Call call) {
        super.onCallRemoved(call);
        if (DBG) Log.d(TAG, "onCallRemoved called");

        if (!mSupportOttNetworkSlicing) {
            if (DBG) Log.d(TAG, "ott network slicing feature is disabled");
            return;
        }
        // TODO (b/448566948): Different ott apps sharing same uid with same time call,
        //  call ending scenario to be checked and handled if needed
        handleCallUpdate(call, false /* isAdd */);
    }

    /**
     * Extracts the UID from the call's PhoneAccountHandle for the correct user.
     */
    private int getUidFromCall(@NonNull PhoneAccountHandle accountHandle) {
        final String packageName = accountHandle.getComponentName().getPackageName();
        final UserHandle userHandle = accountHandle.getUserHandle();

        if (userHandle == null) {
            Log.w(TAG, "PhoneAccountHandle has null UserHandle for package: " + packageName);
            return Process.INVALID_UID;
        }

        try {
            // Use getApplicationInfoAsUser to get the info for the correct user.
            final ApplicationInfo appInfo = mPackageManager.getApplicationInfoAsUser(packageName,
                    APPLICATION_INFO_FLAGS, userHandle);
            return appInfo.uid;
        } catch (NameNotFoundException e) {
            Log.e(TAG, "Could not find package info for user " + userHandle.getIdentifier()
                    + " and package " + packageName, e);
            return Process.INVALID_UID;
        }
    }

    /**
     * Determines if a call is eligible for network slicing.
     */
    private boolean isTransactionalOttCall(@NonNull Call.Details details,
            @NonNull PhoneAccountHandle handle) {
        if (!details.hasProperty(Call.Details.PROPERTY_IS_TRANSACTIONAL)) {
            return false;
        }

        if (details.hasProperty(Call.Details.PROPERTY_SELF_MANAGED)) {
            return true;
        }

        final PhoneAccount phoneAccount = mTelecomManager.getPhoneAccount(handle);
        return phoneAccount != null &&
                phoneAccount.hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED);
    }

    /**
     * Handles call state updates from InCallService, filters for valid, eligible OTT calls,
     * and notifies the {@link com.android.server.ConnectivityService}.
     *
     * <p>This is the primary implementation of this method for OTT network slicing.
     * It listens for transactional OTT calls and notifies the
     * {@link com.android.server.ConnectivityService}, which then applies or revokes the
     * {@link NetworkCapabilities#NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS}
     * network slice for the corresponding application UID to ensure a higher Quality of Service.
     */
    private void handleCallUpdate(@Nullable Call call, boolean isAdd) {
        if (call == null || call.getDetails() == null) {
            Log.w(TAG, "handleCallUpdate: Ignoring call with null call or details.");
            return;
        }
        final Call.Details details = call.getDetails();

        final PhoneAccountHandle handle = details.getAccountHandle();
        if (handle == null) {
            Log.w(TAG, "handleCallUpdate: Ignoring call with null PhoneAccountHandle.");
            return;
        }

        if (!isTransactionalOttCall(details, handle)) {
            if (DBG) Log.d(TAG, "handleCallUpdate: ignoring non transactional ott call");
            return;
        }

        // TODO (b/448546376): OTT app uninstalled during calling, this is expected to fail due to
        //  package info not found scenario. To be handled for active ott slicing request removal
        //  for the scenario
        final int uid = getUidFromCall(handle);
        if (uid == Process.INVALID_UID) {
            Log.w(TAG, "handleCallUpdate: Ignoring call with invalid UID.");
            return;
        }

        Log.i(TAG, "Processing transactional OTT call state change for UID: " + uid
                + ", isAdd: " + isAdd);
        mConnectivityManager.onOttCallStateChanged(uid, isAdd);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (DBG) Log.d(TAG, "onDestroy() called");
    }
}
