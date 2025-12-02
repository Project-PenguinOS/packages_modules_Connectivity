/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.vcn.routeselection;


import static com.android.internal.annotations.VisibleForTesting.Visibility;

import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.TargetApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.IpSecTransformState;
import android.net.Network;
import android.net.vcn.Flags;
import android.os.Build;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.os.PowerManager;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.modules.utils.HandlerExecutor;
import com.android.net.module.util.HexDump;
import com.android.server.vcn.VcnCarrierConfig;
import com.android.server.vcn.VcnContext;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.BitSet;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * IpSecPacketLossDetector is responsible for continuously monitoring IPsec packet loss
 *
 * <p>When the packet loss rate surpass the threshold, IpSecPacketLossDetector will report it to the
 * caller
 *
 * <p>IpSecPacketLossDetector will start monitoring when the network being monitored is selected AND
 * an inbound IpSecTransform has been applied to this network.
 *
 * <p>This class is flag gated by "network_metric_monitor" and "ipsec_tramsform_state"
 */
@TargetApi(Build.VERSION_CODES.BAKLAVA)
public class IpSecPacketLossDetector extends NetworkMetricMonitor {
    private static final String TAG = IpSecPacketLossDetector.class.getSimpleName();

    // This is a hardcoded value at the moment. When IpSecTransform supports configuring
    // bitmap size, this can be a configurable field
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int IPSEC_REPLAY_BITMAP_SIZE = 4096;

    private static final int PACKET_LOSS_PERCENT_UNAVAILABLE = -1;

    // Ignore the packet loss detection result if the expected packet number is smaller than 10.
    // Solarwinds NPM uses 10 ICMP echos to calculate packet loss rate (as per
    // https://thwack.solarwinds.com/products/network-performance-monitor-npm/f/forum/63829/how-is-packet-loss-calculated)
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int MIN_VALID_EXPECTED_RX_PACKET_NUM = 10;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"LOSS_RESULT_"},
            value = {
                LOSS_RESULT_VALID,
                LOSS_RESULT_SEQ_DIFF_TOO_SMALL,
                LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP,
                LOSS_RESULT_UNEXPECTED_ERROR,
                LOSS_RESULT_PACKETS_TOO_OLD,
            })
    @Target({ElementType.TYPE_USE})
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @interface PacketLossResultType {}

    /** Indicates a valid packet loss rate is available */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int LOSS_RESULT_VALID = 0;

    /**
     * Indicates that the detector cannot get a valid packet loss rate because the sequence number
     * increase is too small. It can be one of the following reasons:
     *
     * <ul>
     *   <li>The replay window did not proceed and thus all packets might have been delivered out of
     *       order
     *   <li>The expected received packet number (calculated from sequence number increase) is end
     *       up being too small and thus the detection result is not reliable
     * </ul>
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int LOSS_RESULT_SEQ_DIFF_TOO_SMALL = 1;

    /**
     * The sequence number increase is unusually large and might be caused an intentional leap on
     * the server's downlink
     *
     * <p>Inbound sequence number will not always increase consecutively. During load balancing the
     * server might add a big leap on the sequence number intentionally. In such case a high packet
     * loss rate does not always indicate a lossy network
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP = 2;

    /** Indicates that the detector cannot get a valid packet loss rate due to unexpected errors */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int LOSS_RESULT_UNEXPECTED_ERROR = 3;

    /**
     * Indicates that the detector cannot get a valid packet loss rate because the IpSecTransform
     * state is too old
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int LOSS_RESULT_PACKETS_TOO_OLD = 4;

    @Retention(RetentionPolicy.SOURCE)
    @IntDef(
            prefix = {"DETECTION_MODE_"},
            value = {
                DETECTION_MODE_RAPID,
                DETECTION_MODE_NORMAL,
            })
    @Target({ElementType.TYPE_USE})
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @interface DetectionMode {}

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int DETECTION_MODE_RAPID = 0;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int DETECTION_MODE_NORMAL = 1;

    // For VoIP, losses between 5% and 10% of the total packet stream will affect the quality
    // significantly (as per "Computer Networking for LANS to WANS: Hardware, Software and
    // Security"). For audio and video streaming, above 10-12% packet loss is unacceptable (as per
    // "ICTP-SDU: About PingER"). Thus choose 12% as a conservative default threshold to declare a
    // validation failure.
    public static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DEFAULT = 12;

    /** Carriers can disable the detector by setting the threshold to -1 */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR = -1;

    public static final int POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT = 10;

    // By default, there's no maximum limit enforced
    public static final int MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED = -1;

    // The minimum inbound sequence number increase required for a valid packet loss calculation.
    // This is to ensure that enough packets are received to make the calculation reliable.
    public static final int MIN_SEQ_NUM_INCREASE_DEFAULT = 100;

    // The maximum time difference between two consecutive IpSecTransformState samples for a
    // valid packet loss calculation. This is to avoid using stale data.
    public static final int MAX_TIME_DIFF_SECONDS_DEFAULT = 20;

    public static final int RAPID_MODE_POLL_IPSEC_STATE_INTERVAL_SECONDS_DEFAULT = 2;
    public static final int RAPID_MODE_EXIT_TIMER_SECONDS_DEFAULT = 30;
    public static final int RAPID_MODE_EXIT_TIMER_RAPID_MODE_DISABLED = 0;
    public static final int RAPID_MODE_EXIT_NOT_LOSSY_COUNT = 3;

    private int mPollIpSecStateIntervalSec;
    private int mPacketLossRatePercentThreshold;
    private int mMaxSeqNumIncreasePerSec;
    private int mMinSeqNumIncrease;
    private int mMaxTimeDiffSec;
    private int mRapidModePollIpSecStateIntervalSec;
    private int mRapidModeExitTimerSec;

    private int mConsecutiveReportNotLossyCount = 0;

    @DetectionMode private int mDetectionMode = DETECTION_MODE_RAPID;

    @NonNull private final Handler mHandler;
    @NonNull private final PowerManager mPowerManager;
    @NonNull private final ConnectivityManager mConnectivityManager;
    @NonNull private final Object mCancellationToken = new Object();
    @NonNull private final Object mCancelExitRapidModeToken = new Object();
    @NonNull private final PacketLossCalculator mPacketLossCalculator;

    @Nullable private BroadcastReceiver mDeviceIdleReceiver;

    @Nullable private IpSecTransformWrapper mInboundTransform;
    @Nullable private IpSecTransformState mLastIpSecTransformState;

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public IpSecPacketLossDetector(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull VcnCarrierConfig carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback,
            @NonNull Dependencies deps)
            throws IllegalAccessException {
        super(vcnContext, network, carrierConfig, callback);

        Objects.requireNonNull(deps, "Missing deps");

        mHandler = new Handler(getVcnContext().getLooper());

        mPowerManager = getVcnContext().getContext().getSystemService(PowerManager.class);
        mConnectivityManager =
                getVcnContext().getContext().getSystemService(ConnectivityManager.class);

        mPacketLossCalculator = deps.getPacketLossCalculator();

        mPollIpSecStateIntervalSec = carrierConfig.getNwSelectIpSecLossDetectPollIntervalSec();
        mPacketLossRatePercentThreshold =
                carrierConfig.getNwSelectIpSecLossDetectPercentThreshold();
        mMaxSeqNumIncreasePerSec = carrierConfig.getNwSelectIpSecLossDetectMaxSeqIncPerSec();
        mMinSeqNumIncrease = carrierConfig.getNwSelectIpSecLossDetectMinSeqInc();
        mMaxTimeDiffSec = carrierConfig.getNwSelectIpSecLossDetectMaxTimeDiffSec();
        mRapidModePollIpSecStateIntervalSec =
                carrierConfig.getNwSelectIpSecLossDetectRapidPollIntervalSec();
        mRapidModeExitTimerSec = carrierConfig.getNwSelectIpSecLossDetectRapidDurationSec();

        validate();

        // Register for system broadcasts to monitor idle mode change
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);

        mDeviceIdleReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (!PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED.equals(
                                intent.getAction())) {
                            return;
                        }

                        if (mPowerManager.isDeviceIdleMode()) {
                            mLastIpSecTransformState = null;
                        } else if (Flags.improvePacketLossDetector()) {
                            if (canStart()) {
                                start();
                            }
                        }
                    }
                };
        getVcnContext()
                .getContext()
                .registerReceiver(
                        mDeviceIdleReceiver,
                        intentFilter,
                        null /* broadcastPermission not required */,
                        mHandler);
    }

    public IpSecPacketLossDetector(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull VcnCarrierConfig carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback)
            throws IllegalAccessException {
        this(vcnContext, network, carrierConfig, callback, new Dependencies());
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class Dependencies {
        public PacketLossCalculator getPacketLossCalculator() {
            return new PacketLossCalculator();
        }
    }

    private void validate() {
        if (Flags.improvePacketLossDetector()) {
            // For each VALID detection, the time diff MUST fall into [mPollIpSecStateIntervalSec,
            // mMaxTimeDiffSec]. The following check ensures the upper bound is not smaller than the
            // lower bound
            if (mPollIpSecStateIntervalSec > mMaxTimeDiffSec) {
                throw new IllegalArgumentException(
                        "Poll interval cannot be greater than max time difference; pollInterval="
                                + mPollIpSecStateIntervalSec
                                + ", maxTimeDiff="
                                + mMaxTimeDiffSec);
            }

            // For each VALID detection, the seq diff MUST fall into [mMinSeqNumIncrease,
            // maxSeqNumIncrease]. The following check ensures the upper bound is not smaller than
            // the lower bound
            final long maxSeqNumIncrease =
                    (long) mMaxSeqNumIncreasePerSec * mPollIpSecStateIntervalSec;
            if (mMaxSeqNumIncreasePerSec != MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED
                    && mMinSeqNumIncrease > maxSeqNumIncrease) {
                throw new IllegalArgumentException(
                        "Minimum sequence increase cannot be greater than maximum sequence"
                                + " increase; minSeqIncrease="
                                + mMinSeqNumIncrease
                                + ", maxSeqIncrease="
                                + maxSeqNumIncrease);
            }
        }
    }

    @Override
    protected void onSelectedUnderlyingNetworkChanged() {
        if (!isSelectedUnderlyingNetwork()) {
            mInboundTransform = null;
            stop();
        }

        // No action when the underlying network got selected. Wait for the inbound transform to
        // start the monitor
    }

    @Override
    public void setInboundTransformInternal(@NonNull IpSecTransformWrapper inboundTransform) {
        Objects.requireNonNull(inboundTransform, "inboundTransform is null");

        if (Objects.equals(inboundTransform, mInboundTransform)) {
            return;
        }

        if (!isSelectedUnderlyingNetwork()) {
            logWtf("setInboundTransform called but network not selected");
            return;
        }

        // When multiple parallel inbound transforms are created, NetworkMetricMonitor will be
        // enabled on the last one as a sample
        mInboundTransform = inboundTransform;

        if (canStart()) {
            start();
        }
    }

    @Override
    public void setCarrierConfig(@NonNull VcnCarrierConfig carrierConfig) {
        // The already scheduled event will not be affected. The followup events will be scheduled
        // with the new interval
        mPollIpSecStateIntervalSec = carrierConfig.getNwSelectIpSecLossDetectPollIntervalSec();
        mPacketLossRatePercentThreshold =
                carrierConfig.getNwSelectIpSecLossDetectPercentThreshold();
        mMaxSeqNumIncreasePerSec = carrierConfig.getNwSelectIpSecLossDetectMaxSeqIncPerSec();
        mMinSeqNumIncrease = carrierConfig.getNwSelectIpSecLossDetectMinSeqInc();
        mMaxTimeDiffSec = carrierConfig.getNwSelectIpSecLossDetectMaxTimeDiffSec();
        mRapidModePollIpSecStateIntervalSec =
                carrierConfig.getNwSelectIpSecLossDetectRapidPollIntervalSec();
        mRapidModeExitTimerSec = carrierConfig.getNwSelectIpSecLossDetectRapidDurationSec();

        validate();

        if (canStart() != isStarted()) {
            if (canStart()) {
                start();
            } else {
                stop();
            }
        }
    }

    @Override
    public void onLinkPropertiesOrCapabilitiesChanged() {
        if (!isStarted()) return;

        reschedulePolling();
    }

    private void reschedulePolling() {
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
        mHandler.postDelayed(new PollIpSecStateRunnable(), mCancellationToken, 0L);
    }

    private boolean canStart() {
        return mInboundTransform != null
                && mPacketLossRatePercentThreshold
                        != IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR;
    }

    @Override
    protected void start() {
        super.start();
        clearTransformStateAndPollingEvents();
        mHandler.postDelayed(new PollIpSecStateRunnable(), mCancellationToken, 0L);

        if (Flags.improvePacketLossDetector()
                && mRapidModeExitTimerSec > RAPID_MODE_EXIT_TIMER_RAPID_MODE_DISABLED) {
            mDetectionMode = DETECTION_MODE_RAPID;
            mHandler.postDelayed(
                    new ExitRapidModeRunnable(),
                    mCancelExitRapidModeToken,
                    TimeUnit.SECONDS.toMillis(mRapidModeExitTimerSec));
        } else {
            mDetectionMode = DETECTION_MODE_NORMAL;
        }
    }

    @Override
    public void stop() {
        super.stop();
        clearTransformStateAndPollingEvents();
    }

    private void clearTransformStateAndPollingEvents() {
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
        mLastIpSecTransformState = null;

        if (Flags.improvePacketLossDetector()) {
            mConsecutiveReportNotLossyCount = 0;
            mHandler.removeCallbacksAndEqualMessages(mCancelExitRapidModeToken);
        }
    }

    @Override
    public void close() {
        super.close();

        if (mInboundTransform != null) {
            mInboundTransform = null;
        }

        if (mDeviceIdleReceiver != null) {
            getVcnContext().getContext().unregisterReceiver(mDeviceIdleReceiver);
            mDeviceIdleReceiver = null;
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @Nullable
    public IpSecTransformState getLastTransformState() {
        return mLastIpSecTransformState;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    @DetectionMode
    public int getDetectionMode() {
        return mDetectionMode;
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public int getConsecutiveReportNotLossyCount() {
        return mConsecutiveReportNotLossyCount;
    }

    @VisibleForTesting(visibility = Visibility.PROTECTED)
    @Nullable
    public IpSecTransformWrapper getInboundTransformInternal() {
        return mInboundTransform;
    }

    private class PollIpSecStateRunnable implements Runnable {
        @Override
        public void run() {
            if (!isStarted()) {
                logWtf("Monitor stopped but PollIpSecStateRunnable not removed from Handler");
                return;
            }

            getInboundTransformInternal()
                    .requestIpSecTransformState(
                            new HandlerExecutor(mHandler), new IpSecTransformStateReceiver());

            final int pollIntervalSeconds =
                    mDetectionMode == DETECTION_MODE_RAPID
                            ? mRapidModePollIpSecStateIntervalSec
                            : mPollIpSecStateIntervalSec;

            // Schedule for next poll
            mHandler.postDelayed(
                    new PollIpSecStateRunnable(),
                    mCancellationToken,
                    TimeUnit.SECONDS.toMillis(pollIntervalSeconds));
        }
    }

    private class ExitRapidModeRunnable implements Runnable {
        @Override
        public void run() {
            mDetectionMode = DETECTION_MODE_NORMAL;
        }
    }

    private class IpSecTransformStateReceiver
            implements OutcomeReceiver<IpSecTransformState, RuntimeException> {
        @Override
        public void onResult(@NonNull IpSecTransformState state) {
            getVcnContext().ensureRunningOnLooperThread();

            if (!isStarted()) {
                return;
            }

            onIpSecTransformStateReceived(state);
        }

        @Override
        public void onError(@NonNull RuntimeException error) {
            getVcnContext().ensureRunningOnLooperThread();

            // Nothing we can do here
            logW("TransformStateReceiver#onError " + error.toString());
        }
    }

    private void onIpSecTransformStateReceived(@NonNull IpSecTransformState state) {
        if (mLastIpSecTransformState == null) {
            // This is first time to poll the state
            mLastIpSecTransformState = state;
            return;
        }

        final PacketLossCalculationResult calculateResult;
        if (Flags.improvePacketLossDetector()) {
            calculateResult =
                    mPacketLossCalculator.getPacketLossRatePercentage(
                            mLastIpSecTransformState,
                            state,
                            mMaxSeqNumIncreasePerSec,
                            mMinSeqNumIncrease,
                            mMaxTimeDiffSec,
                            getLogPrefix());
        } else {
            calculateResult =
                    mPacketLossCalculator.getPacketLossRatePercentageLegacy(
                            mLastIpSecTransformState,
                            state,
                            mMaxSeqNumIncreasePerSec,
                            getLogPrefix());
        }

        final int packetLossResultType = calculateResult.getResultType();
        final int packetLossPercent = calculateResult.getPacketLossRatePercent();
        final boolean isLossy = packetLossPercent >= mPacketLossRatePercentThreshold;

        final String logMsg =
                "calculateResult: "
                        + calculateResult
                        + "% in the past "
                        + (state.getTimestampMillis()
                                - mLastIpSecTransformState.getTimestampMillis())
                        + "ms";

        if (shouldReportValidationResult(isLossy, packetLossResultType) && isLossy) {
            logInfo(logMsg);
        } else {
            logV(logMsg);
        }

        if (shouldUpdateLastTransformState(packetLossResultType)) {
            mLastIpSecTransformState = state;
        }

        if (shouldReportValidationResult(isLossy, packetLossResultType)) {
            handleValidationResultReceivedInternal(isLossy);
        }

        if (shouldReportNetworkConnectivity(isLossy, packetLossResultType)) {
            mConnectivityManager.reportNetworkConnectivity(
                    getNetwork(), false /* hasConnectivity */);
        }
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    void handleValidationResultReceivedInternal(boolean isLossy) {
        onValidationResultReceivedInternal(!isLossy /* isSucceeded */);

        if (Flags.improvePacketLossDetector()) {
            if (isLossy) {
                mConsecutiveReportNotLossyCount = 0;
            } else {
                mConsecutiveReportNotLossyCount++;
                if (mDetectionMode == DETECTION_MODE_RAPID
                        && mConsecutiveReportNotLossyCount >= RAPID_MODE_EXIT_NOT_LOSSY_COUNT) {
                    mHandler.removeCallbacksAndEqualMessages(mCancelExitRapidModeToken);
                    mDetectionMode = DETECTION_MODE_NORMAL;
                }
            }
        }
    }

    /**
     * Return whether the mLastIpSecTransformState should be updated when handling the result
     *
     * <p>When the seq diff is too small to calculate the packet loss reliably, the detector should
     * grab a newer state and still compare it with the current "last state" so as to increase the
     * seq diff.
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean shouldUpdateLastTransformState(@PacketLossResultType int resultType) {
        return resultType != LOSS_RESULT_SEQ_DIFF_TOO_SMALL;
    }

    /** Return whether it is a reliable validation result to report */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean shouldReportValidationResult(
            boolean isLossy, @PacketLossResultType int resultType) {
        return switch (resultType) {
            case LOSS_RESULT_VALID -> true;
            // In this case a high loss rate might be caused by an intentional sequence number leap.
            // Thus only trust the result if it is "not lossy"
            case LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP -> !isLossy;
            default -> false;
        };
    }

    /** Return whether to trigger network revalidation */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static boolean shouldReportNetworkConnectivity(
            boolean isLossy, @PacketLossResultType int resultType) {
        return switch (resultType) {
            case LOSS_RESULT_VALID -> isLossy;
            // Although the "loss report" might be caused by an intentional leap, still trigger a
            // revalidation to double check
            case LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP -> isLossy;
            default -> false;
        };
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class PacketLossCalculator {
        /**
         * Calculates the packet loss rate between two IpSecTransformState snapshots (oldState and
         * newState), by inspecting their replay windows.
         *
         * <p>Expected And Actual Packet Count Determination:
         *
         * <ul>
         *   <li>Non-overlapping Replay Windows: The expected packet count is the replay window
         *       size. The actual count is derived from the entire newState replay window.
         *   <li>Overlapping Replay Windows: The expected packet count is the change in the highest
         *       sequence number between oldState and newState. The actual count is derived from the
         *       subset of the newState replay window, ranging from the oldState's highest sequence
         *       number to the newState's highest sequence number.
         * </ul>
         *
         * <p>Special Cases for Invalid Results:
         *
         * <ul>
         *   <li><b>Stale Data:</b> If the time between snapshots exceeds maxTimeDiffSec.
         *   <li><b>Insufficient Sample Size:</b> If the sequence number increase is below
         *       minSeqNumIncrease.
         *   <li><b>Unusual Sequence Leap:</b> If the sequence number increase is unusually large
         *       (e.g., due to server-side load balancing), the result is flagged to prevent
         *       misinterpretation as network loss.
         * </ul>
         */
        public PacketLossCalculationResult getPacketLossRatePercentage(
                @NonNull IpSecTransformState oldState,
                @NonNull IpSecTransformState newState,
                int maxSeqNumIncreasePerSec,
                int minSeqNumIncrease,
                int maxTimeDiffSec,
                String logPrefix) {
            logVIpSecTransform("oldState", oldState, logPrefix);
            logVIpSecTransform("newState", newState, logPrefix);
            final long seqHiDiff =
                    newState.getRxHighestSequenceNumber() - oldState.getRxHighestSequenceNumber();
            final long timeDiffMillis =
                    newState.getTimestampMillis() - oldState.getTimestampMillis();

            // Handle Invalid Result: If the sample is too old, skip this report to avoid using
            // stale data
            if (timeDiffMillis > TimeUnit.SECONDS.toMillis(maxTimeDiffSec)) {
                return PacketLossCalculationResult.packetsTooOld();
            }

            // Handle Invalid Result: If the sample size is too small, skip this report to ensure
            // calculation reliability
            if (seqHiDiff < minSeqNumIncrease) {
                return PacketLossCalculationResult.seqDiffTooSmall();
            }

            // Determine the expected and actual packet count
            final int expectedPktCnt = (int) Math.min(seqHiDiff, IPSEC_REPLAY_BITMAP_SIZE);
            final int actualPktCnt = getRecentPacketCntInReplayWindow(newState, expectedPktCnt);
            logV(
                    TAG,
                    logPrefix
                            + " expectedPktCnt: "
                            + expectedPktCnt
                            + " actualPktCnt: "
                            + actualPktCnt);
            final int percent = 100 - (int) (actualPktCnt * 100 / expectedPktCnt);

            // Handle Invalid Result: Unusual Sequence Leap
            final boolean isUnusualSeqNumLeap =
                    isUnusualSeqNumLeap(timeDiffMillis, maxSeqNumIncreasePerSec, seqHiDiff);

            return isUnusualSeqNumLeap
                    ? PacketLossCalculationResult.unusualSeqNumLeap(percent)
                    : PacketLossCalculationResult.valid(percent);
        }

        /**
         * Calculate the packet loss rate between two timestamps.
         *
         * <p>This is a legacy implementation.
         */
        public PacketLossCalculationResult getPacketLossRatePercentageLegacy(
                @NonNull IpSecTransformState oldState,
                @NonNull IpSecTransformState newState,
                int maxSeqNumIncreasePerSec,
                String logPrefix) {
            logVIpSecTransform("oldState", oldState, logPrefix);
            logVIpSecTransform("newState", newState, logPrefix);

            final int replayWindowSize = oldState.getReplayBitmap().length * 8;
            final long oldSeqHi = oldState.getRxHighestSequenceNumber();
            final long oldSeqLow = Math.max(0L, oldSeqHi - replayWindowSize + 1);
            final long newSeqHi = newState.getRxHighestSequenceNumber();
            final long newSeqLow = Math.max(0L, newSeqHi - replayWindowSize + 1);

            if (oldSeqHi == newSeqHi || newSeqHi < replayWindowSize) {
                // The replay window did not proceed and all packets might have been delivered out
                // of order
                return PacketLossCalculationResult.seqDiffTooSmall();
            }

            final long timeDiffMillis =
                    newState.getTimestampMillis() - oldState.getTimestampMillis();
            final boolean isUnusualSeqNumLeap =
                    isUnusualSeqNumLeap(
                            timeDiffMillis, maxSeqNumIncreasePerSec, newSeqHi - oldSeqHi);

            // Get the expected packet count by assuming there is no packet loss. In this case, SA
            // should receive all packets whose sequence numbers are smaller than the lower bound of
            // the replay window AND the packets received within the window.
            // When the lower bound is 0, it's not possible to tell whether packet with seqNo 0 is
            // received or not. For simplicity just assume that packet is received.
            final long newExpectedPktCnt = newSeqLow + getPacketCntInReplayWindow(newState);
            final long oldExpectedPktCnt = oldSeqLow + getPacketCntInReplayWindow(oldState);

            final long expectedPktCntDiff = newExpectedPktCnt - oldExpectedPktCnt;
            final long actualPktCntDiff = newState.getPacketCount() - oldState.getPacketCount();

            logV(
                    TAG,
                    logPrefix
                            + " expectedPktCntDiff: "
                            + expectedPktCntDiff
                            + " actualPktCntDiff: "
                            + actualPktCntDiff);

            if (expectedPktCntDiff < MIN_VALID_EXPECTED_RX_PACKET_NUM) {
                // The sample size is too small to ensure a reliable detection result
                return PacketLossCalculationResult.seqDiffTooSmall();
            }

            if (expectedPktCntDiff < 0
                    || expectedPktCntDiff == 0
                    || actualPktCntDiff < 0
                    || actualPktCntDiff > expectedPktCntDiff) {
                logWtf(TAG, "Impossible values for expectedPktCntDiff or" + " actualPktCntDiff");
                return PacketLossCalculationResult.unexpectedError();
            }

            final int percent = 100 - (int) (actualPktCntDiff * 100 / expectedPktCntDiff);
            return isUnusualSeqNumLeap
                    ? PacketLossCalculationResult.unusualSeqNumLeap(percent)
                    : PacketLossCalculationResult.valid(percent);
        }
    }

    private static boolean isUnusualSeqNumLeap(
            long timeDiffMillis, int maxSeqNumIncreasePerSec, long seqHiDiff) {
        if (maxSeqNumIncreasePerSec == MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED) {
            return false;
        }

        final long maxSeqNumIncrease = timeDiffMillis * maxSeqNumIncreasePerSec / 1000;
        return Long.compareUnsigned(seqHiDiff, maxSeqNumIncrease) > 0;
    }

    private static void logVIpSecTransform(
            String transformTag, IpSecTransformState state, String logPrefix) {
        final String stateString =
                " seqNo: "
                        + state.getRxHighestSequenceNumber()
                        + " | pktCnt: "
                        + state.getPacketCount()
                        + " | pktCntInWindow: "
                        + getPacketCntInReplayWindow(state)
                        + " | replayWindow: "
                        + HexDump.toHexString(state.getReplayBitmap());
        logV(TAG, logPrefix + " " + transformTag + stateString);
    }

    /** Get the number of received packets within the replay window */
    private static long getPacketCntInReplayWindow(@NonNull IpSecTransformState state) {
        return BitSet.valueOf(state.getReplayBitmap()).cardinality();
    }

    /**
     * Get the number of received packets in the most recent portion of the replay window.
     *
     * @param state The current IpSec state containing the replay window.
     * @param lookbackDepth The number of slots to check, counting backwards from the highest
     *     sequence number.
     * @return The number of packets received within that depth.
     */
    private static int getRecentPacketCntInReplayWindow(
            @NonNull IpSecTransformState state, int lookbackDepth) {
        final BitSet replayBitmap = BitSet.valueOf(state.getReplayBitmap());
        final long highestSeq = state.getRxHighestSequenceNumber();
        final int maxIndexExclusive = (int) Math.min(IPSEC_REPLAY_BITMAP_SIZE, highestSeq + 1);
        return replayBitmap.get(maxIndexExclusive - lookbackDepth, maxIndexExclusive).cardinality();
    }

    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public static class PacketLossCalculationResult {
        @PacketLossResultType private final int mResultType;
        private final int mPacketLossRatePercent;

        private PacketLossCalculationResult(@PacketLossResultType int type, int percent) {
            mResultType = type;
            mPacketLossRatePercent = percent;
        }

        /** Construct an instance that contains a valid packet loss rate */
        public static PacketLossCalculationResult valid(int percent) {
            return new PacketLossCalculationResult(LOSS_RESULT_VALID, percent);
        }

        /** Constructs an instance indicating the sequence number difference is too small */
        public static PacketLossCalculationResult seqDiffTooSmall() {
            return new PacketLossCalculationResult(
                    LOSS_RESULT_SEQ_DIFF_TOO_SMALL, PACKET_LOSS_PERCENT_UNAVAILABLE);
        }

        /** Constructs an instance indicating that the IpSecTransform state is too old */
        public static PacketLossCalculationResult packetsTooOld() {
            return new PacketLossCalculationResult(
                    LOSS_RESULT_PACKETS_TOO_OLD, PACKET_LOSS_PERCENT_UNAVAILABLE);
        }

        /** Constructs an instance indicating that there is an unexpected error */
        public static PacketLossCalculationResult unexpectedError() {
            return new PacketLossCalculationResult(
                    LOSS_RESULT_UNEXPECTED_ERROR, PACKET_LOSS_PERCENT_UNAVAILABLE);
        }

        /** Construct an instance indicating that there is an unusual sequence number leap */
        public static PacketLossCalculationResult unusualSeqNumLeap(int percent) {
            return new PacketLossCalculationResult(LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP, percent);
        }

        @PacketLossResultType
        public int getResultType() {
            return mResultType;
        }

        public int getPacketLossRatePercent() {
            return mPacketLossRatePercent;
        }

        @Override
        public int hashCode() {
            return Objects.hash(mResultType, mPacketLossRatePercent);
        }

        @Override
        public boolean equals(@Nullable Object other) {
            if (!(other instanceof PacketLossCalculationResult)) {
                return false;
            }

            final PacketLossCalculationResult rhs = (PacketLossCalculationResult) other;
            return mResultType == rhs.mResultType
                    && mPacketLossRatePercent == rhs.mPacketLossRatePercent;
        }

        @Override
        public String toString() {
            return "mResultType: "
                    + mResultType
                    + " | mPacketLossRatePercent: "
                    + mPacketLossRatePercent;
        }
    }
}
