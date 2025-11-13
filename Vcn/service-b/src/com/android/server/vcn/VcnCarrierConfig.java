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

package com.android.server.vcn;

import static android.net.vcn.VcnManager.KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_MAX_SEQ_INC_PER_SEC_INT;
import static android.net.vcn.VcnManager.KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_PERCENT_THD_INT;
import static android.net.vcn.VcnManager.KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_POLL_INTERVAL_SEC_INT;
import static android.net.vcn.util.PersistableBundleUtils.PersistableBundleWrapper;

import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT;

import android.annotation.NonNull;
import android.annotation.Nullable;

/**
 * VcnCarrierConfig translates a carrier config bundle to VCN info that can be queried
 *
 * @hide
 */
public class VcnCarrierConfig {
    private final int mNwSelectIpSecLossDetectPollIntervalSec;
    private final int mNwSelectIpSecLossDetectPercentThreshold;
    private final int mNwSelectIpSecLossDetectMaxSeqIncPerSec;

    public VcnCarrierConfig(@Nullable PersistableBundleWrapper carrierConfig) {
        mNwSelectIpSecLossDetectPollIntervalSec =
                getCarrierConfigInt(
                        carrierConfig,
                        KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_POLL_INTERVAL_SEC_INT,
                        POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT);

        mNwSelectIpSecLossDetectPercentThreshold =
                getCarrierConfigInt(
                        carrierConfig,
                        KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_PERCENT_THD_INT,
                        IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT);

        mNwSelectIpSecLossDetectMaxSeqIncPerSec =
                getCarrierConfigInt(
                        carrierConfig,
                        KEY_NETWORK_SELECTION_IPSEC_LOSS_DETECT_MAX_SEQ_INC_PER_SEC_INT,
                        MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED);
    }

    private static int getCarrierConfigInt(
            @Nullable PersistableBundleWrapper carrierConfig,
            @NonNull String key,
            int defaultValue) {
        if (carrierConfig != null) {
            return carrierConfig.getInt(key, defaultValue);
        }
        return defaultValue;
    }

    public int getNwSelectIpSecLossDetectPollIntervalSec() {
        return mNwSelectIpSecLossDetectPollIntervalSec;
    }

    public int getNwSelectIpSecLossDetectPercentThreshold() {
        return mNwSelectIpSecLossDetectPercentThreshold;
    }

    public int getNwSelectIpSecLossDetectMaxSeqIncPerSec() {
        return mNwSelectIpSecLossDetectMaxSeqIncPerSec;
    }
}
