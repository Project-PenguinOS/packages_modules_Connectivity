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


import static com.android.server.VcnManagementService.LOCAL_LOG;

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.net.IpSecTransform;
import android.net.IpSecTransformState;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.vcn.Flags;
import android.os.Build;
import android.os.Handler;
import android.os.OutcomeReceiver;
import android.util.CloseGuard;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.annotations.VisibleForTesting.Visibility;
import com.android.server.vcn.VcnCarrierConfig;
import com.android.server.vcn.VcnContext;

import java.util.ListIterator;
import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * NetworkMetricMonitor is responsible for managing metric monitoring and tracking validation
 * results.
 *
 * <p>This class is flag gated by "network_metric_monitor"
 */
@TargetApi(Build.VERSION_CODES.BAKLAVA)
public abstract class NetworkMetricMonitor implements AutoCloseable {
    private static final String TAG = NetworkMetricMonitor.class.getSimpleName();

    private static final boolean VDBG = false; // STOPSHIP: if true

    public static final int[] PENALTY_TIMEOUT_MINUTES_DEFAULT = new int[] {2, 4, 8, 16};
    private static final long PENALTY_TIMEOUT_MIN = 1;

    @NonNull private final CloseGuard mCloseGuard = new CloseGuard();

    @NonNull private final Handler mHandler;
    @NonNull private final VcnContext mVcnContext;
    @NonNull private final Network mNetwork;
    @NonNull private final NetworkMetricMonitorCallback mCallback;
    @NonNull private final Object mCancellationToken = new Object();

    private boolean mIsSelectedUnderlyingNetwork;
    private boolean mIsStarted;
    private boolean mIsValidationSucceeded;

    private boolean mIsPenalized;
    @NonNull private ListIterator<Long> mPenaltyTimeoutIterator;

    protected NetworkMetricMonitor(
            @NonNull VcnContext vcnContext,
            @NonNull Network network,
            @NonNull VcnCarrierConfig carrierConfig,
            @NonNull NetworkMetricMonitorCallback callback)
            throws IllegalAccessException {
        mVcnContext = Objects.requireNonNull(vcnContext, "Missing vcnContext");
        mNetwork = Objects.requireNonNull(network, "Missing network");
        mCallback = Objects.requireNonNull(callback, "Missing callback");

        mHandler = new Handler(getVcnContext().getLooper());

        mIsSelectedUnderlyingNetwork = false;
        mIsStarted = false;

        // Assume the network is good before running validation
        mIsValidationSucceeded = true;

        mPenaltyTimeoutIterator = carrierConfig.getNwSelectPenaltyTimeoutMillis().listIterator();
        mIsPenalized = false;

        validate();
    }

    private void validate() {
        if (!mPenaltyTimeoutIterator.hasNext()) {
            throw new IllegalArgumentException("Penalty Timeout Millis list is empty");
        }

        while (mPenaltyTimeoutIterator.hasNext()) {
            final long timeout = mPenaltyTimeoutIterator.next();
            if (timeout < PENALTY_TIMEOUT_MIN) {
                throw new IllegalArgumentException("Invalid penalty timeout " + timeout);
            }
        }

        rewind(mPenaltyTimeoutIterator);
    }

    /** Callback to notify caller of the validation result */
    public interface NetworkMetricMonitorCallback {
        /** Called when there is a validation result is ready */
        void onValidationResultReceived();

        /** Called when there the penalty state has changed */
        void onIsPenalizedChanged();
    }

    /**
     * Start monitoring
     *
     * <p>This method might be called on a an already started monitor for updating monitor
     * properties (e.g. IpSecTransform, carrier config)
     *
     * <p>Subclasses MUST call super.start() when overriding this method
     */
    protected void start() {
        mIsStarted = true;
    }

    /**
     * Stop monitoring
     *
     * <p>Subclasses MUST call super.stop() when overriding this method
     */
    public void stop() {
        mIsStarted = false;
    }

    private static void rewind(ListIterator it) {
        while (it.hasPrevious()) it.previous();
    }

    public class ExitPenaltyBoxRunnable implements Runnable {
        @Override
        public void run() {
            if (!mIsPenalized) {
                logWtf("Monitor not being penalized but ExitPenaltyBoxRunnable was scheduled");
                return;
            }

            mIsPenalized = false;
            mCallback.onIsPenalizedChanged();
        }
    }

    /** Called by the subclasses when the validation result is ready */
    protected void onValidationResultReceivedInternal(boolean isSucceeded) {
        if (Flags.improvePacketLossDetector()) {
            final boolean oldIsPenalized = mIsPenalized;
            mIsPenalized = !isSucceeded;

            logV("#onValidationResultReceivedInternal: isSucceeded " + isSucceeded);

            if (!mIsPenalized) {
                // Regardless of the previous state, the network has affirmatively passed
                // validation, so it is known to be good/working. Reset all monitor state.
                rewind(mPenaltyTimeoutIterator);
                mHandler.removeCallbacksAndEqualMessages(mCancellationToken);
            } else if (!oldIsPenalized) {
                // The network transitions either from "unknown" or from "successful validation" to
                // "failing validation".

                // When max penalty reached, call previous() to return the last item and move the
                // cursor to immediately before the last item. This ensures the next call
                // to .next() returns this last item again.
                final long penaltyTimeoutMillis =
                        mPenaltyTimeoutIterator.hasNext()
                                ? mPenaltyTimeoutIterator.next()
                                : mPenaltyTimeoutIterator.previous();

                mHandler.postDelayed(
                        new ExitPenaltyBoxRunnable(), mCancellationToken, penaltyTimeoutMillis);
                logInfo(
                        "#onValidationResultReceivedInternal: Penalize for "
                                + penaltyTimeoutMillis
                                + "ms");
            }

            // Notify the callback if penalty state has changed
            if (oldIsPenalized != mIsPenalized) {
                mCallback.onIsPenalizedChanged();
            }
        } else {
            mIsValidationSucceeded = isSucceeded;
            mCallback.onValidationResultReceived();
        }
    }

    /** Called when the underlying network changes to selected or unselected */
    protected abstract void onSelectedUnderlyingNetworkChanged();

    /**
     * Mark the network being monitored selected or unselected
     *
     * <p>Subclasses MUST call super when overriding this method
     */
    public void setIsSelectedUnderlyingNetwork(boolean isSelectedUnderlyingNetwork) {
        if (mIsSelectedUnderlyingNetwork == isSelectedUnderlyingNetwork) {
            return;
        }

        mIsSelectedUnderlyingNetwork = isSelectedUnderlyingNetwork;
        onSelectedUnderlyingNetworkChanged();
    }

    /** Wrapper that allows injection for testing purposes */
    @VisibleForTesting(visibility = Visibility.PROTECTED)
    public static class IpSecTransformWrapper {
        @NonNull public final IpSecTransform ipSecTransform;

        public IpSecTransformWrapper(@NonNull IpSecTransform ipSecTransform) {
            this.ipSecTransform = ipSecTransform;
        }

        /** Poll an IpSecTransformState */
        public void requestIpSecTransformState(
                @NonNull Executor executor,
                @NonNull OutcomeReceiver<IpSecTransformState, RuntimeException> callback) {
            ipSecTransform.requestIpSecTransformState(executor, callback);
        }

        /** Close this instance and release the underlying resources */
        public void close() {
            ipSecTransform.close();
        }

        @Override
        public int hashCode() {
            return Objects.hash(ipSecTransform);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof IpSecTransformWrapper)) {
                return false;
            }

            final IpSecTransformWrapper other = (IpSecTransformWrapper) o;

            return Objects.equals(ipSecTransform, other.ipSecTransform);
        }
    }

    /** Set the IpSecTransform that is applied to the Network being monitored */
    public void setInboundTransform(@NonNull IpSecTransform inTransform) {
        setInboundTransformInternal(new IpSecTransformWrapper(inTransform));
    }

    /**
     * Set the IpSecTransform that applied to the Network being monitored *
     *
     * <p>Subclasses MUST call super when overriding this method
     */
    @VisibleForTesting(visibility = Visibility.PRIVATE)
    public void setInboundTransformInternal(@NonNull IpSecTransformWrapper inTransform) {
        // Subclasses MUST override it if they care
    }

    /** Update the carrierconfig */
    public void setCarrierConfig(@NonNull VcnCarrierConfig carrierConfig) {
        // Updating the penalty timeout will also mean that the next timeout will start over from
        // the first provided timeout in the new list even though the monitor might have been failed
        // multiple times
        mPenaltyTimeoutIterator = carrierConfig.getNwSelectPenaltyTimeoutMillis().listIterator();
        validate();
    }

    /** Called when LinkProperties have changed */
    public void onLinkPropertiesChanged(@NonNull LinkProperties lp) {
        // Subclasses MUST override it if they care
    }

    /** Called when NetworkCapabilities have changed */
    public void onNetworkCapabilitiesChanged(@NonNull NetworkCapabilities nc) {
        // Subclasses MUST override it if they care
    }

    public boolean isValidationSucceeded() {
        return mIsValidationSucceeded;
    }

    public boolean isPenalized() {
        return mIsPenalized;
    }

    public boolean isSelectedUnderlyingNetwork() {
        return mIsSelectedUnderlyingNetwork;
    }

    public boolean isStarted() {
        return mIsStarted;
    }

    @NonNull
    public VcnContext getVcnContext() {
        return mVcnContext;
    }

    @NonNull
    public Network getNetwork() {
        return mNetwork;
    }

    // Override methods for AutoCloseable. Subclasses MUST call super when overriding this method
    @Override
    public void close() {
        mCloseGuard.close();
        mHandler.removeCallbacksAndEqualMessages(mCancellationToken);

        stop();
    }

    // Override #finalize() to use closeGuard for flagging that #close() was not called
    @SuppressWarnings("Finalize")
    @Override
    protected void finalize() throws Throwable {
        try {
            if (mCloseGuard != null) {
                mCloseGuard.warnIfOpen();
            }
            close();
        } finally {
            super.finalize();
        }
    }

    private String getClassName() {
        return this.getClass().getSimpleName();
    }

    protected String getLogPrefix() {
        return " [Network " + mNetwork + "] ";
    }

    protected void logV(String msg) {
        if (VDBG) {
            Slog.v(getClassName(), getLogPrefix() + msg);
            LOCAL_LOG.log("[VERBOSE ] " + getClassName() + getLogPrefix() + msg);
        }
    }

    protected void logInfo(String msg) {
        Slog.i(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[INFO ] " + getClassName() + getLogPrefix() + msg);
    }

    protected void logW(String msg) {
        Slog.w(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[WARN ] " + getClassName() + getLogPrefix() + msg);
    }

    protected void logWtf(String msg) {
        Slog.wtf(getClassName(), getLogPrefix() + msg);
        LOCAL_LOG.log("[WTF ] " + getClassName() + getLogPrefix() + msg);
    }

    protected static void logV(String className, String msgWithPrefix) {
        if (VDBG) {
            Slog.wtf(className, msgWithPrefix);
            LOCAL_LOG.log("[VERBOSE ] " + className + msgWithPrefix);
        }
    }

    protected static void logE(String className, String msgWithPrefix) {
        Slog.w(className, msgWithPrefix);
        LOCAL_LOG.log("[ERROR ] " + className + msgWithPrefix);
    }

    protected static void logWtf(String className, String msgWithPrefix) {
        Slog.wtf(className, msgWithPrefix);
        LOCAL_LOG.log("[WTF ] " + className + msgWithPrefix);
    }
}
