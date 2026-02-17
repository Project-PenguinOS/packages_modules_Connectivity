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

package android.net.util;

import static android.net.DnsResolver.ERROR_PARSE;
import static android.net.DnsResolver.ERROR_SYSTEM;
import static android.net.DnsResolver.TYPE_A;
import static android.net.DnsResolver.TYPE_AAAA;
import static android.net.DnsResolver.TYPE_HTTPS;

import static com.android.net.module.util.DnsPacket.ANSECTION;
import static com.android.net.module.util.DnsPacket.QDSECTION;

import android.annotation.FlaggedApi;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
import android.net.DnsResolver.DnsException;
import android.net.Network;
import android.net.ParseException;
import android.net.dns.HttpsEndpoint;
import android.net.dns.HttpsRecord;
import android.util.Log;

import com.android.internal.annotations.GuardedBy;
import com.android.net.module.util.DnsHttpsRecord;
import com.android.net.module.util.DnsPacket;
import com.android.net.module.util.DnsPacket.DnsRecord;

import java.lang.ClassCastException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Accumulates the results of concurrent A/AAAA/HTTPS DNS queries.
 *
 * @hide
 */
public class HttpsEndpointAccumulator implements DnsResolver.Callback<byte[]> {

    private final Network mNetwork;
    private final DnsResolver.Callback<HttpsEndpoint> mUserCallback;
    private final int mTargetQueryCount;
    private final int mHttpsTimeoutMillis;
    private final boolean mHasIpv4;
    private final boolean mHasIpv6;

    @GuardedBy("mResult")
    private final HttpsEndpointAccumulatorResult mResult;
    private final AtomicBoolean mCallbackInvoked = new AtomicBoolean(false);

    /**
     * Nested class to group the results of the DNS queries that must be kept in sync.
     */
    private static class HttpsEndpointAccumulatorResult {
        final Set<Integer> mReceivedAnswersToQueryTypes;
        final Set<InetAddress> mAddresses;
        final List<HttpsRecord> mHttpsRecords;
        DnsException mDnsException;

        private HttpsEndpointAccumulatorResult() {
            mReceivedAnswersToQueryTypes = new HashSet<>();
            mAddresses = new LinkedHashSet<>();
            mHttpsRecords = new ArrayList<>();
        }
    }

    public HttpsEndpointAccumulator(@NonNull Network network,
            @NonNull DnsResolver.Callback<HttpsEndpoint> callback, int queryCount,
            int timeoutMillis, boolean hasIpv4, boolean hasIpv6) {
        mNetwork = network;
        mUserCallback = callback;
        mTargetQueryCount = queryCount;
        mHttpsTimeoutMillis = timeoutMillis;
        mHasIpv4 = hasIpv4;
        mHasIpv6 = hasIpv6;
        mResult = new HttpsEndpointAccumulatorResult();
    }

    @Override
    public void onAnswer(@NonNull byte[] answer, int rcode) {
        Objects.requireNonNull(answer, "Raw byte response for query cannot be null");

        if (mCallbackInvoked.get()) {
            // Callback has already been invoked, skip parsing this response.
            return;
        }

        DnsPacket dnsPacket = new DnsPacket(answer);
        final List<DnsRecord> questionRecords = dnsPacket.getRecords(QDSECTION);
        final int questionCount = questionRecords.size();
        if (questionCount != 1) {
            synchronized (mResult) {
                mResult.mDnsException = new DnsException(ERROR_PARSE,
                        new ParseException("Unexpected question count: " + questionCount));
            }
            return;
        }

        final int queryType = questionRecords.get(0).nsType;

        if (queryType != TYPE_A && queryType != TYPE_AAAA && queryType != TYPE_HTTPS) {
            // Don't try to parse any other types of records (e.g. CNAME), but don't mark this as an
            // error since as long as we get an answer for the types we're looking for, we're fine.
            return;
        }

        final List<DnsRecord> answerRecords = dnsPacket.getRecords(ANSECTION);
        Set<InetAddress> addresses = new LinkedHashSet<>();
        List<HttpsRecord> httpsRecords = new ArrayList<>();
        DnsException exception = null;

        // In the NODATA scenario, answerRecords will be empty and this whole loop will be skipped.
        for (DnsRecord record : answerRecords) {
            int answerType = record.nsType;

            try {
                switch (answerType) {
                    case TYPE_A, TYPE_AAAA -> {
                        addresses.add(InetAddress.getByAddress(record.getRR()));
                    }
                    case TYPE_HTTPS -> {
                        HttpsRecord httpsRecord =
                                new HttpsRecord(mNetwork, (DnsHttpsRecord) record);
                        httpsRecords.add(httpsRecord);

                        // Add only the relevant IP hints to the list of IP addresses.
                        if (mHasIpv4) addresses.addAll(httpsRecord.getIpv4Hints());
                        if (mHasIpv6) addresses.addAll(httpsRecord.getIpv6Hints());
                    }
                    default -> {
                        exception = new DnsException(ERROR_PARSE,
                                new ParseException("Unexpected answer type: " + answerType));
                    }
                }
            } catch (DnsPacket.ParseException e) {
                exception = new DnsException(ERROR_PARSE, new ParseException(e.reason, e));
            } catch (ClassCastException e) {
                exception = new DnsException(ERROR_PARSE, e);
            } catch (UnknownHostException e) {
                exception = new DnsException(ERROR_SYSTEM, e);
            }
        }

        synchronized (mResult) {
            mResult.mAddresses.addAll(addresses);
            mResult.mHttpsRecords.addAll(httpsRecords);
            // Add the query type even in the case of NODATA or mismatched query/response type,
            // since this indicates we received an answer for the type of record we were querying.
            mResult.mReceivedAnswersToQueryTypes.add(queryType);
            if (exception != null) {
                mResult.mDnsException = exception;
            }

            if (!mResult.mHttpsRecords.isEmpty() && hasEnoughAddressInfo()) {
                // Disregard any exceptions and return if we got valid HTTPS and IP data.
                reportAnswer(rcode);
            } else if (mTargetQueryCount == mResult.mReceivedAnswersToQueryTypes.size()) {
                // Return if we have gotten all the records as we expected.
                tryReportResult(rcode);
            }
        }
        // TODO(b/448882639): handle filtering out HTTPS records with specified mandatory values
        // that are absent
        // TODO(b/448882639): handle timeout logic for HTTPS record
        // TODO(b/448882639): handle if the HTTPS record name does not match the A/AAAA ones
        // TODO(b/448882639): handle parsing the CNAME chain for the A/AAAA records
    }

    @Override
    public void onError(@NonNull DnsException error) {
        synchronized (mResult) {
            mResult.mDnsException = error;
            // Doesn't matter what rcode we pass here, since we're reporting an error
            tryReportResult(/* rcode= */ 0);
        }
    }

    /**
     * Reports the DNS query successful results as an {@link HttpsEndpoint} to the user callback.
     *
     * <p>If the user callback has already been invoked (regardless of success or failure), this
     * method does nothing.
     */
    @GuardedBy("mResult")
    private void reportAnswer(int rcode) {
        if (mCallbackInvoked.compareAndSet(false, true)) {
            // Synchronize on the result object here as onError could also call this method.
            Collections.sort(mResult.mHttpsRecords,
                    Comparator.comparing(HttpsRecord::getPriority));
            mUserCallback.onAnswer(
                    new HttpsEndpoint(
                            mNetwork,
                            mResult.mHttpsRecords,
                            new ArrayList<>(mResult.mAddresses)), rcode);
        }
    }

    /**
     * Reports the DNS query results to the user callback, both success and failure.
     *
     * <p>If the user callback has already been invoked (regardless of success or failure), this
     * method does nothing.
     */
    @GuardedBy("mResult")
    private void tryReportResult(int rcode) {
        if (mResult.mDnsException != null && mCallbackInvoked.compareAndSet(false, true)) {
            mUserCallback.onError(mResult.mDnsException);
            return;
        }

        reportAnswer(rcode);
    }

    /**
     * Returns true if we've received enough address information to return the results we have.
     *
     * <p>This is the case if we have received at least one HTTPS record with the IP hints relevant
     * to network, or if we have received the A or AAAA records relevant to the network.
     */
    @GuardedBy("mResult")
    private boolean hasEnoughAddressInfo() {
        for (HttpsRecord record: mResult.mHttpsRecords) {
            boolean missingIpv4Hint = mHasIpv4 && record.getIpv4Hints().isEmpty();
            boolean missingIpv6Hint = mHasIpv6 && record.getIpv6Hints().isEmpty();
            if (!missingIpv4Hint && !missingIpv6Hint) {
                // If we have at least one HTTPS record with the relevant IP hints, don't bother
                // checking the other HTTPS records.
                return true;
            }
        }

        boolean hasIpv6IfNeeded = !mHasIpv6 ||
                mResult.mReceivedAnswersToQueryTypes.contains(TYPE_AAAA);
        boolean hasIpv4IfNeeded = !mHasIpv4 ||
                mResult.mReceivedAnswersToQueryTypes.contains(TYPE_A);

        return hasIpv6IfNeeded && hasIpv4IfNeeded;
    }
}
