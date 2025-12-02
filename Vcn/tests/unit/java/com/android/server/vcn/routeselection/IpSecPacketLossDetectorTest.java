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

import static android.net.vcn.Flags.FLAG_IMPROVE_PACKET_LOSS_DETECTOR;

import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.DETECTION_MODE_NORMAL;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.DETECTION_MODE_RAPID;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.LOSS_RESULT_PACKETS_TOO_OLD;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.LOSS_RESULT_SEQ_DIFF_TOO_SMALL;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.LOSS_RESULT_UNEXPECTED_ERROR;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.LOSS_RESULT_VALID;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.shouldReportNetworkConnectivity;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.shouldReportValidationResult;
import static com.android.server.vcn.routeselection.IpSecPacketLossDetector.shouldUpdateLastTransformState;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.BroadcastReceiver;
import android.content.Intent;
import android.net.IpSecTransformState;
import android.os.Message;
import android.os.OutcomeReceiver;
import android.os.PowerManager;
import android.platform.test.annotations.EnableFlags;
import android.platform.test.flag.junit.SetFlagsRule;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.server.vcn.VcnCarrierConfig;
import com.android.server.vcn.routeselection.IpSecPacketLossDetector.PacketLossCalculationResult;
import com.android.server.vcn.routeselection.IpSecPacketLossDetector.PacketLossCalculator;
import com.android.server.vcn.routeselection.NetworkMetricMonitor.IpSecTransformWrapper;
import com.android.server.vcn.routeselection.NetworkMetricMonitor.NetworkMetricMonitorCallback;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Spy;

import java.util.Arrays;
import java.util.BitSet;
import java.util.concurrent.TimeUnit;

@EnableFlags(FLAG_IMPROVE_PACKET_LOSS_DETECTOR)
@RunWith(AndroidJUnit4.class)
@SmallTest
public class IpSecPacketLossDetectorTest extends NetworkEvaluationTestBase {
    private static final String TAG = IpSecPacketLossDetectorTest.class.getSimpleName();

    @Rule public final SetFlagsRule mSetFlagsRule = new SetFlagsRule();

    private static final int REPLAY_BITMAP_LEN_BYTE = 512;
    private static final int REPLAY_BITMAP_LEN_BIT = REPLAY_BITMAP_LEN_BYTE * 8;
    private static final int IPSEC_PACKET_LOSS_PERCENT_THRESHOLD = 5;
    private static final int MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED = -1;
    private static final int MIN_SEQ_NUM_INCREASE = 200;

    private static final int POLL_IPSEC_STATE_INTERVAL_SECONDS = 30;
    private static final long POLL_IPSEC_STATE_INTERVAL_MS =
            TimeUnit.SECONDS.toMillis(POLL_IPSEC_STATE_INTERVAL_SECONDS);
    private static final int MAX_TIME_DIFF_SECONDS = POLL_IPSEC_STATE_INTERVAL_SECONDS * 2;

    private static final int RAPID_POLL_IPSEC_STATE_INTERVAL_SECONDS = 2;
    private static final long RAPID_POLL_IPSEC_STATE_INTERVAL_MS =
            TimeUnit.SECONDS.toMillis(RAPID_POLL_IPSEC_STATE_INTERVAL_SECONDS);
    private static final int RAPID_MODE_EXIT_TIMER_RAPID_MODE_DISABLED = 0;
    private static final int RAPID_MODE_EXIT_TIMER_SECONDS = 30;
    private static final int RAPID_MODE_EXIT_NOT_LOSSY_COUNT = 3;

    // Used in tests where bitmap and packet count are not used and thus can be arbitrary values
    private static final byte[] REPLAY_BITMAP_DEFAULT = newReplayBitmap(0);
    private static final int PACKET_COUNT_DEFAULT = 0;

    @Mock private IpSecTransformWrapper mIpSecTransform;
    @Mock private NetworkMetricMonitorCallback mMetricMonitorCallback;
    @Mock private IpSecPacketLossDetector.Dependencies mDependencies;
    @Mock private VcnCarrierConfig mCarrierConfig;
    @Spy private PacketLossCalculator mPacketLossCalculator = new PacketLossCalculator();

    @Captor private ArgumentCaptor<OutcomeReceiver> mTransformStateReceiverCaptor;
    @Captor private ArgumentCaptor<BroadcastReceiver> mBroadcastReceiverCaptor;

    private IpSecPacketLossDetector mIpSecPacketLossDetector;
    private IpSecTransformState mTransformStateInitial;

    @Before
    public void setUp() throws Exception {
        super.setUp();
        mTransformStateInitial = newTransformState(0, 0, newReplayBitmap(0));

        when(mCarrierConfig.getNwSelectIpSecLossDetectPollIntervalSec())
                .thenReturn((int) TimeUnit.MILLISECONDS.toSeconds(POLL_IPSEC_STATE_INTERVAL_MS));
        when(mCarrierConfig.getNwSelectIpSecLossDetectPercentThreshold())
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);
        when(mCarrierConfig.getNwSelectIpSecLossDetectMaxSeqIncPerSec())
                .thenReturn(MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED);
        when(mCarrierConfig.getNwSelectIpSecLossDetectMinSeqInc()).thenReturn(MIN_SEQ_NUM_INCREASE);
        when(mCarrierConfig.getNwSelectIpSecLossDetectMaxTimeDiffSec())
                .thenReturn(MAX_TIME_DIFF_SECONDS);
        when(mCarrierConfig.getNwSelectIpSecLossDetectRapidPollIntervalSec())
                .thenReturn(RAPID_POLL_IPSEC_STATE_INTERVAL_SECONDS);
        when(mCarrierConfig.getNwSelectIpSecLossDetectRapidDurationSec())
                .thenReturn(RAPID_MODE_EXIT_TIMER_RAPID_MODE_DISABLED);

        when(mDependencies.getPacketLossCalculator()).thenReturn(mPacketLossCalculator);

        mIpSecPacketLossDetector =
                new IpSecPacketLossDetector(
                        mVcnContext,
                        mNetwork,
                        mCarrierConfig,
                        mMetricMonitorCallback,
                        mDependencies);
    }

    private static IpSecTransformState.Builder newTransformStateBuilder(int rxSeqNo) {
        return new IpSecTransformState.Builder()
                .setRxHighestSequenceNumber(rxSeqNo)
                .setPacketCount(PACKET_COUNT_DEFAULT)
                .setReplayBitmap(REPLAY_BITMAP_DEFAULT);
    }

    private static IpSecTransformState newTransformState(
            long rxSeqNo, long packtCount, byte[] replayBitmap) {
        return new IpSecTransformState.Builder()
                .setRxHighestSequenceNumber(rxSeqNo)
                .setPacketCount(packtCount)
                .setReplayBitmap(replayBitmap)
                .build();
    }

    private static IpSecTransformState newNextTransformState(
            IpSecTransformState before,
            long timeDiffMillis,
            long rxSeqNoDiff,
            long packtCountDiff,
            int packetInWin) {
        return new IpSecTransformState.Builder()
                .setTimestampMillis(before.getTimestampMillis() + timeDiffMillis)
                .setRxHighestSequenceNumber(before.getRxHighestSequenceNumber() + rxSeqNoDiff)
                .setPacketCount(before.getPacketCount() + packtCountDiff)
                .setReplayBitmap(newReplayBitmap(packetInWin))
                .build();
    }

    // Used when the highest sequence number of the current window is larger or equal than
    // REPLAY_BITMAP_LEN_BIT
    private static byte[] newReplayBitmap(int receivedPktCnt) {
        return newReplayBitmap(receivedPktCnt, REPLAY_BITMAP_LEN_BIT - 1);
    }

    // Used when the highest sequence number of the current window is smaller than
    // REPLAY_BITMAP_LEN_BIT
    private static byte[] newReplayBitmap(int receivedPktCnt, int highestSeqNum) {
        final BitSet bitSet = new BitSet(REPLAY_BITMAP_LEN_BIT);
        for (int i = 0; i < receivedPktCnt; i++) {
            bitSet.set(highestSeqNum - i);
        }
        return Arrays.copyOf(bitSet.toByteArray(), REPLAY_BITMAP_LEN_BYTE);
    }

    private void verifyStopped() {
        assertFalse(mIpSecPacketLossDetector.isStarted());
        assertTrue(mIpSecPacketLossDetector.isValidationSucceeded());
        assertNull(mIpSecPacketLossDetector.getLastTransformState());

        // No event scheduled
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testInitialization() throws Exception {
        assertFalse(mIpSecPacketLossDetector.isSelectedUnderlyingNetwork());
        verifyStopped();
    }

    private OutcomeReceiver<IpSecTransformState, RuntimeException>
            startMonitorAndCaptureStateReceiver() {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        // Trigger the runnable
        mTestLooper.dispatchAll();

        verify(mIpSecTransform)
                .requestIpSecTransformState(any(), mTransformStateReceiverCaptor.capture());
        return mTransformStateReceiverCaptor.getValue();
    }

    private void verifyStartMonitor(long pollIntervalMs) {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertTrue(mIpSecPacketLossDetector.isValidationSucceeded());
        assertTrue(mIpSecPacketLossDetector.isSelectedUnderlyingNetwork());
        assertEquals(mIpSecTransform, mIpSecPacketLossDetector.getInboundTransformInternal());

        // Mock receiving a state
        xfrmStateReceiver.onResult(mTransformStateInitial);

        // Verify the first polled state is stored
        assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(
                        any(), any(), anyInt(), anyInt(), anyInt(), anyString());

        // Verify next poll is scheduled and execute it
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(pollIntervalMs);
        final Message msg = mTestLooper.nextMessage();
        msg.getTarget().dispatchMessage(msg);
    }

    @Test
    public void testStartMonitor() throws Exception {
        verifyStartMonitor(POLL_IPSEC_STATE_INTERVAL_MS);
    }

    private void enableRapidMode() {
        when(mCarrierConfig.getNwSelectIpSecLossDetectRapidDurationSec())
                .thenReturn(RAPID_MODE_EXIT_TIMER_SECONDS);
        mIpSecPacketLossDetector.setCarrierConfig(mCarrierConfig);
    }

    private void verifyExitRapidMode() {
        assertEquals(DETECTION_MODE_NORMAL, mIpSecPacketLossDetector.getDetectionMode());

        // Execute the poll event scheduled during rapid mode
        mTestLooper.moveTimeForward(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
        mTestLooper.dispatchAll();

        // Verify the next poll event is not scheduled at the rapid mode interval
        mTestLooper.moveTimeForward(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(
                POLL_IPSEC_STATE_INTERVAL_MS - RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
        assertNotNull(mTestLooper.nextMessage());
    }

    @Test
    public void testStartMonitor_rapidMode() throws Exception {
        enableRapidMode();
        verifyStartMonitor(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
    }

    @Test
    public void testExitRapidMode_dueToTimerExpiry() throws Exception {
        enableRapidMode();

        verifyStartMonitor(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
        assertEquals(DETECTION_MODE_RAPID, mIpSecPacketLossDetector.getDetectionMode());

        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(RAPID_MODE_EXIT_TIMER_SECONDS));
        mTestLooper.dispatchAll();

        verifyExitRapidMode();
    }

    @Test
    public void testExitRapidMode_dueToNotLossyReported() throws Exception {
        enableRapidMode();

        verifyStartMonitor(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);
        assertEquals(DETECTION_MODE_RAPID, mIpSecPacketLossDetector.getDetectionMode());

        for (int i = 0; i < RAPID_MODE_EXIT_NOT_LOSSY_COUNT; i++) {
            mIpSecPacketLossDetector.handleValidationResultReceivedInternal(false /* isFailed */);
        }

        verifyExitRapidMode();
    }

    private void receiveIdleModeChange(boolean isInIdleMode) throws Exception {
        final Intent intent = mock(Intent.class);
        when(intent.getAction()).thenReturn(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED);
        when(mPowerManagerService.isDeviceIdleMode()).thenReturn(isInIdleMode);

        verify(mContext).registerReceiver(mBroadcastReceiverCaptor.capture(), any(), any(), any());
        final BroadcastReceiver broadcastReceiver = mBroadcastReceiverCaptor.getValue();
        broadcastReceiver.onReceive(mContext, intent);
    }

    @Test
    public void testStartedMonitor_enterIdleMode() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        receiveIdleModeChange(true /* isInIdleMode */);

        assertNull(mIpSecPacketLossDetector.getLastTransformState());
    }

    @Test
    public void testStartedMonitor_exitIdleMode() throws Exception {
        enableRapidMode();
        verifyStartMonitor(RAPID_POLL_IPSEC_STATE_INTERVAL_MS);

        mTestLooper.moveTimeForward(TimeUnit.SECONDS.toMillis(RAPID_MODE_EXIT_TIMER_SECONDS));
        mTestLooper.dispatchAll();
        verifyExitRapidMode();

        receiveIdleModeChange(false /* isInIdleMode */);

        assertEquals(DETECTION_MODE_RAPID, mIpSecPacketLossDetector.getDetectionMode());
    }

    @Test
    public void testStartedMonitor_updateInboundTransform() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();

        // Mock receiving a state
        xfrmStateReceiver.onResult(mTransformStateInitial);
        assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());

        // Update the inbound transform
        final IpSecTransformWrapper newTransform = mock(IpSecTransformWrapper.class);
        mIpSecPacketLossDetector.setInboundTransformInternal(newTransform);

        // Verifications
        assertNull(mIpSecPacketLossDetector.getLastTransformState());
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        mTestLooper.dispatchAll();
        verify(newTransform).requestIpSecTransformState(any(), any());
    }

    @Test
    public void testStartedMonitor_updateCarrierConfig() throws Exception {
        startMonitorAndCaptureStateReceiver();

        final int additionalPollIntervalMs = (int) TimeUnit.SECONDS.toMillis(10L);
        when(mCarrierConfig.getNwSelectIpSecLossDetectPollIntervalSec())
                .thenReturn(
                        (int)
                                TimeUnit.MILLISECONDS.toSeconds(
                                        POLL_IPSEC_STATE_INTERVAL_MS + additionalPollIntervalMs));
        mIpSecPacketLossDetector.setCarrierConfig(mCarrierConfig);
        mTestLooper.dispatchAll();

        // The already scheduled event is still fired with the old timeout
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        mTestLooper.dispatchAll();

        // The next scheduled event will take 10 more seconds to fire
        mTestLooper.moveTimeForward(POLL_IPSEC_STATE_INTERVAL_MS);
        assertNull(mTestLooper.nextMessage());
        mTestLooper.moveTimeForward(additionalPollIntervalMs);
        assertNotNull(mTestLooper.nextMessage());
    }

    @Test
    public void testStopMonitor() throws Exception {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertNotNull(mTestLooper.nextMessage());

        // Unselect the monitor
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(false /* setIsSelected */);
        verifyStopped();
    }

    @Test
    public void testClose() throws Exception {
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        mIpSecPacketLossDetector.setInboundTransformInternal(mIpSecTransform);

        assertTrue(mIpSecPacketLossDetector.isStarted());
        assertNotNull(mTestLooper.nextMessage());

        // Stop the monitor
        mIpSecPacketLossDetector.close();
        mIpSecPacketLossDetector.close();
        verifyStopped();

        verify(mIpSecTransform, never()).close();
        verify(mContext).unregisterReceiver(any());
    }

    @Test
    public void testTransformStateReceiverOnResultWhenStopped() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        xfrmStateReceiver.onResult(mTransformStateInitial);

        // Unselect the monitor
        mIpSecPacketLossDetector.setIsSelectedUnderlyingNetwork(false /* setIsSelected */);
        verifyStopped();

        xfrmStateReceiver.onResult(newTransformState(1, 1, newReplayBitmap(1)));
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(
                        any(), any(), anyInt(), anyInt(), anyInt(), anyString());
    }

    @Test
    public void testTransformStateReceiverOnError() throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        xfrmStateReceiver.onResult(mTransformStateInitial);

        xfrmStateReceiver.onError(new RuntimeException("Test"));
        verify(mPacketLossCalculator, never())
                .getPacketLossRatePercentage(
                        any(), any(), anyInt(), anyInt(), anyInt(), anyString());
    }

    private void checkHandleLossRate(
            PacketLossCalculationResult mockPacketLossRate,
            boolean isLastStateExpectedToUpdate,
            boolean isCallbackExpected)
            throws Exception {
        final OutcomeReceiver<IpSecTransformState, RuntimeException> xfrmStateReceiver =
                startMonitorAndCaptureStateReceiver();
        doReturn(mockPacketLossRate)
                .when(mPacketLossCalculator)
                .getPacketLossRatePercentage(
                        any(), any(), anyInt(), anyInt(), anyInt(), anyString());

        // Mock receiving two states with mTransformStateInitial and an arbitrary transformNew
        final IpSecTransformState transformNew = newTransformState(1, 1, newReplayBitmap(1));
        xfrmStateReceiver.onResult(mTransformStateInitial);
        xfrmStateReceiver.onResult(transformNew);

        // Verifications
        verify(mPacketLossCalculator)
                .getPacketLossRatePercentage(
                        eq(mTransformStateInitial),
                        eq(transformNew),
                        eq(MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED),
                        eq(MIN_SEQ_NUM_INCREASE),
                        eq(MAX_TIME_DIFF_SECONDS),
                        anyString());

        if (isLastStateExpectedToUpdate) {
            assertEquals(transformNew, mIpSecPacketLossDetector.getLastTransformState());
        } else {
            assertEquals(mTransformStateInitial, mIpSecPacketLossDetector.getLastTransformState());
        }

        if (isCallbackExpected) {
            verify(mMetricMonitorCallback).onValidationResultReceived();
        } else {
            verify(mMetricMonitorCallback, never()).onValidationResultReceived();
        }
    }

    @Test
    public void testHandleLossRate_validationPass() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.valid(2),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_validationFail() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.valid(22),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
        verify(mConnectivityManager).reportNetworkConnectivity(mNetwork, false);
    }

    @Test
    public void testHandleLossRate_resultUnavalaible() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.seqDiffTooSmall(),
                false /* isLastStateExpectedToUpdate */,
                false /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_unusualSeqNumLeap_highLossRate() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.unusualSeqNumLeap(22),
                true /* isLastStateExpectedToUpdate */,
                false /* isCallbackExpected */);
    }

    @Test
    public void testHandleLossRate_unusualSeqNumLeap_lowLossRate() throws Exception {
        checkHandleLossRate(
                PacketLossCalculationResult.unusualSeqNumLeap(2),
                true /* isLastStateExpectedToUpdate */,
                true /* isCallbackExpected */);
    }

    private void checkGetPacketLossRate(
            IpSecTransformState oldState,
            IpSecTransformState newState,
            PacketLossCalculationResult expectedLossRate)
            throws Exception {
        assertEquals(
                expectedLossRate,
                mPacketLossCalculator.getPacketLossRatePercentage(
                        oldState,
                        newState,
                        MAX_SEQ_NUM_INCREASE_DEFAULT_DISABLED,
                        MIN_SEQ_NUM_INCREASE,
                        MAX_TIME_DIFF_SECONDS,
                        TAG));
    }

    private void checkGetPacketLossRate(
            IpSecTransformState oldState, IpSecTransformState newState, int expectedDataLossRate)
            throws Exception {
        checkGetPacketLossRate(
                oldState, newState, PacketLossCalculationResult.valid(expectedDataLossRate));
    }

    @Test
    public void testGetPacketLossRate_expectedPacketNumTooFew() throws Exception {
        final int oldRxNo = 4096;
        final int seqNoDiff = MIN_SEQ_NUM_INCREASE - 1;

        final IpSecTransformState oldState = newTransformStateBuilder(oldRxNo).build();
        final IpSecTransformState newState = newTransformStateBuilder(oldRxNo + seqNoDiff).build();

        checkGetPacketLossRate(oldState, newState, PacketLossCalculationResult.seqDiffTooSmall());
    }

    @Test
    public void testGetPacketLossRate_againstInitialState() throws Exception {
        // Old Replay Window: []
        // New Replay Window: [0, 3000]
        final IpSecTransformState.Builder newStateBuilder =
                newTransformStateBuilder(3000 /* rxSeqNo */);

        // ExpectedDataLossRate: 100% - 3000/3000 => 0%
        checkGetPacketLossRate(
                mTransformStateInitial,
                newStateBuilder
                        .setReplayBitmap(
                                newReplayBitmap(3000 /* packetInWin */, 3000 /* highestSeqNum */))
                        .build(),
                0 /* expectedDataLossRate */);

        // ExpectedDataLossRate: 100% - 2000/3000 => 34%
        checkGetPacketLossRate(
                mTransformStateInitial,
                newStateBuilder
                        .setReplayBitmap(
                                newReplayBitmap(2000 /* packetInWin */, 3000 /* highestSeqNum */))
                        .build(),
                34 /* expectedDataLossRate */);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqSmallerThanWinSize_overlappedWithNewWin()
            throws Exception {
        // Old Replay Window: [0, 500]
        // New Replay Window: [205, 4300]
        final IpSecTransformState oldState = newTransformStateBuilder(500).build();
        final IpSecTransformState.Builder newStateBuilder = newTransformStateBuilder(4300);

        // ExpectedDataLossRate: 100% - 3800/3800 => 0%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(3800 /* packetInWin */)).build(),
                0 /* expectedDataLossRate */);

        // ExpectedDataLossRate: 100% - 1000/3800 => 74%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(1000 /* packetInWin */)).build(),
                74 /* expectedDataLossRate */);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqSmallerThanWinSize_notOverlappedWithNewWin()
            throws Exception {
        // Old Replay Window: [0, 500]
        // New Replay Window: [15905, 20000]
        final IpSecTransformState oldState = newTransformStateBuilder(500).build();
        final IpSecTransformState.Builder newStateBuilder = newTransformStateBuilder(20000);

        // ExpectedDataLossRate: 100% - 4096/4096 => 0%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(4096 /* packetInWin */)).build(),
                0 /* expectedDataLossRate */);

        // ExpectedDataLossRate: 100% - 3800/4096 => 8%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(3800 /* packetInWin */)).build(),
                8 /* expectedDataLossRate */);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqLargerThanWinSize_overlappedWithNewWin()
            throws Exception {
        // Old Replay Window: [5905, 10000]
        // New Replay Window: [7905, 12000]
        final IpSecTransformState oldState = newTransformStateBuilder(10000).build();
        final IpSecTransformState.Builder newStateBuilder = newTransformStateBuilder(12000);

        // ExpectedDataLossRate: 100% - 2000/2000 => 0%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(2000 /* packetInWin */)).build(),
                0 /* expectedDataLossRate */);

        // ExpectedDataLossRate: 100% - 1000/2000 => 50%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(1000 /* packetInWin */)).build(),
                50 /* expectedDataLossRate */);
    }

    @Test
    public void testGetPktLossRate_oldHiSeqLargerThanWinSize_notOverlappedWithNewWin()
            throws Exception {
        // Old Replay Window: [5905, 10000]
        // New Replay Window: [15905, 20000]
        final IpSecTransformState oldState = newTransformStateBuilder(10000).build();
        final IpSecTransformState.Builder newStateBuilder = newTransformStateBuilder(20000);

        // ExpectedDataLossRate: 100% - 4096/4096 => 0%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(4096 /* packetInWin */)).build(),
                0 /* expectedDataLossRate */);

        // ExpectedDataLossRate: 100% - 2000/4096 => 52%
        checkGetPacketLossRate(
                oldState,
                newStateBuilder.setReplayBitmap(newReplayBitmap(2000 /* packetInWin */)).build(),
                52 /* expectedDataLossRate */);
    }

    @Test
    public void testPacketsTooOld() throws Exception {
        final IpSecTransformState oldState = newTransformStateBuilder(100).build();
        final IpSecTransformState newState =
                newTransformStateBuilder(200)
                        .setTimestampMillis(
                                oldState.getTimestampMillis()
                                        + TimeUnit.SECONDS.toMillis(MAX_TIME_DIFF_SECONDS)
                                        + 1)
                        .build();

        checkGetPacketLossRate(oldState, newState, PacketLossCalculationResult.packetsTooOld());
    }

    private void checkGetPktLossRate_unusualSeqNumLeap(
            int maxSeqNumIncreasePerSecond,
            int timeDiffMillis,
            int rxSeqNoDiff,
            PacketLossCalculationResult expected)
            throws Exception {
        final IpSecTransformState oldState = mTransformStateInitial;
        final IpSecTransformState newState =
                newNextTransformState(
                        oldState,
                        timeDiffMillis,
                        rxSeqNoDiff,
                        1 /* packtCountDiff */,
                        1 /* packetInWin */);

        assertEquals(
                expected,
                mPacketLossCalculator.getPacketLossRatePercentage(
                        oldState,
                        newState,
                        maxSeqNumIncreasePerSecond,
                        MIN_SEQ_NUM_INCREASE,
                        MAX_TIME_DIFF_SECONDS,
                        TAG));
    }

    @Test
    public void testGetPktLossRate_unusualSeqNumLeap() throws Exception {
        checkGetPktLossRate_unusualSeqNumLeap(
                10000 /* maxSeqNumIncreasePerSecond */,
                (int) TimeUnit.SECONDS.toMillis(2L),
                30000 /* rxSeqNoDiff */,
                PacketLossCalculationResult.unusualSeqNumLeap(100));
    }

    @Test
    public void testGetPktLossRate_unusualSeqNumLeap_smallSeqNumDiff() throws Exception {
        checkGetPktLossRate_unusualSeqNumLeap(
                10000 /* maxSeqNumIncreasePerSecond */,
                (int) TimeUnit.SECONDS.toMillis(2L),
                5000 /* rxSeqNoDiff */,
                PacketLossCalculationResult.valid(100));
    }

    // Verify the polling event is scheduled with expected delays
    private void verifyPollEventDelayAndScheduleNext(long expectedDelayMs) {
        if (expectedDelayMs > 0) {
            mTestLooper.dispatchAll();
            verify(mIpSecTransform, never()).requestIpSecTransformState(any(), any());
            mTestLooper.moveTimeForward(expectedDelayMs);
        }

        mTestLooper.dispatchAll();
        verify(mIpSecTransform).requestIpSecTransformState(any(), any());
        reset(mIpSecTransform);
    }

    @Test
    public void testOnLinkPropertiesOrCapabilitiesChange() throws Exception {
        // Start the monitor; verify the 1st poll is scheduled without delay
        startMonitorAndCaptureStateReceiver();
        verifyPollEventDelayAndScheduleNext(0 /* expectedDelayMs */);

        // Verify the 2nd poll is rescheduled without delay
        mIpSecPacketLossDetector.onLinkPropertiesOrCapabilitiesChanged();
        verifyPollEventDelayAndScheduleNext(0 /* expectedDelayMs */);

        // Verify the 3rd poll is scheduled with configured delay
        verifyPollEventDelayAndScheduleNext(POLL_IPSEC_STATE_INTERVAL_MS);
    }

    private IpSecPacketLossDetector newDetectorAndSetTransform(int threshold) throws Exception {
        when(mCarrierConfig.getNwSelectIpSecLossDetectPercentThreshold()).thenReturn(threshold);

        final IpSecPacketLossDetector detector =
                new IpSecPacketLossDetector(
                        mVcnContext,
                        mNetwork,
                        mCarrierConfig,
                        mMetricMonitorCallback,
                        mDependencies);

        detector.setIsSelectedUnderlyingNetwork(true /* setIsSelected */);
        detector.setInboundTransformInternal(mIpSecTransform);

        return detector;
    }

    @Test
    public void testDisableAndEnableDetectorWithCarrierConfig() throws Exception {
        final IpSecPacketLossDetector detector =
                newDetectorAndSetTransform(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR);

        assertFalse(detector.isStarted());

        when(mCarrierConfig.getNwSelectIpSecLossDetectPercentThreshold())
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);
        detector.setCarrierConfig(mCarrierConfig);

        assertTrue(detector.isStarted());
    }

    @Test
    public void testEnableAndDisableDetectorWithCarrierConfig() throws Exception {
        final IpSecPacketLossDetector detector =
                newDetectorAndSetTransform(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD);

        assertTrue(detector.isStarted());

        when(mCarrierConfig.getNwSelectIpSecLossDetectPercentThreshold())
                .thenReturn(IPSEC_PACKET_LOSS_PERCENT_THRESHOLD_DISABLE_DETECTOR);
        detector.setCarrierConfig(mCarrierConfig);

        assertFalse(detector.isStarted());
    }

    @Test
    public void testShouldUpdateLastTransformState() {
        assertTrue(shouldUpdateLastTransformState(LOSS_RESULT_VALID));
        assertFalse(shouldUpdateLastTransformState(LOSS_RESULT_SEQ_DIFF_TOO_SMALL));
        assertTrue(shouldUpdateLastTransformState(LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP));
        assertTrue(shouldUpdateLastTransformState(LOSS_RESULT_UNEXPECTED_ERROR));
        assertTrue(shouldUpdateLastTransformState(LOSS_RESULT_PACKETS_TOO_OLD));
    }

    @Test
    public void testShouldReportValidationResult() {
        assertTrue(shouldReportValidationResult(true /* isLossy */, LOSS_RESULT_VALID));
        assertTrue(shouldReportValidationResult(false /* isLossy */, LOSS_RESULT_VALID));

        assertFalse(
                shouldReportValidationResult(true /* isLossy */, LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP));
        assertTrue(
                shouldReportValidationResult(
                        false /* isLossy */, LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP));

        assertFalse(
                shouldReportValidationResult(true /* isLossy */, LOSS_RESULT_SEQ_DIFF_TOO_SMALL));
        assertFalse(
                shouldReportValidationResult(false /* isLossy */, LOSS_RESULT_SEQ_DIFF_TOO_SMALL));

        assertFalse(shouldReportValidationResult(true /* isLossy */, LOSS_RESULT_UNEXPECTED_ERROR));
        assertFalse(
                shouldReportValidationResult(false /* isLossy */, LOSS_RESULT_UNEXPECTED_ERROR));

        assertFalse(shouldReportValidationResult(true /* isLossy */, LOSS_RESULT_PACKETS_TOO_OLD));
        assertFalse(shouldReportValidationResult(false /* isLossy */, LOSS_RESULT_PACKETS_TOO_OLD));
    }

    @Test
    public void testShouldReportNetworkConnectivity() {
        assertTrue(shouldReportNetworkConnectivity(true /* isLossy */, LOSS_RESULT_VALID));
        assertFalse(shouldReportNetworkConnectivity(false /* isLossy */, LOSS_RESULT_VALID));

        assertTrue(
                shouldReportNetworkConnectivity(
                        true /* isLossy */, LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP));
        assertFalse(
                shouldReportNetworkConnectivity(
                        false /* isLossy */, LOSS_RESULT_UNUSUAL_SEQ_NUM_LEAP));

        assertFalse(
                shouldReportNetworkConnectivity(
                        true /* isLossy */, LOSS_RESULT_SEQ_DIFF_TOO_SMALL));
        assertFalse(
                shouldReportNetworkConnectivity(
                        false /* isLossy */, LOSS_RESULT_SEQ_DIFF_TOO_SMALL));

        assertFalse(
                shouldReportNetworkConnectivity(true /* isLossy */, LOSS_RESULT_UNEXPECTED_ERROR));
        assertFalse(
                shouldReportNetworkConnectivity(false /* isLossy */, LOSS_RESULT_UNEXPECTED_ERROR));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidate_throwsOnInvalidTimeDiffConfigs() throws Exception {
        when(mCarrierConfig.getNwSelectIpSecLossDetectMaxTimeDiffSec())
                .thenReturn(POLL_IPSEC_STATE_INTERVAL_SECONDS - 1);

        mIpSecPacketLossDetector.setCarrierConfig(mCarrierConfig);
    }

    @Test(expected = IllegalArgumentException.class)
    public void testValidate_throws_minSeqNumIncrease_largerthan_maxSeqNumIncrease()
            throws Exception {
        final int maxSeqNumIncreasePerSec = 100;

        when(mCarrierConfig.getNwSelectIpSecLossDetectMaxSeqIncPerSec())
                .thenReturn(maxSeqNumIncreasePerSec);
        when(mCarrierConfig.getNwSelectIpSecLossDetectMinSeqInc())
                .thenReturn(maxSeqNumIncreasePerSec * POLL_IPSEC_STATE_INTERVAL_SECONDS + 1);

        mIpSecPacketLossDetector.setCarrierConfig(mCarrierConfig);
    }
}

