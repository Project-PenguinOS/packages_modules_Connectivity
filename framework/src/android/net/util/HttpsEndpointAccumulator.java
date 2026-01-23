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
    private final Set<Integer> mReceivedAnswerTypes = new HashSet<>();

    private LinkedHashSet<InetAddress> mAddresses = new LinkedHashSet<>();
    private List<HttpsRecord> mHttpsRecords = new ArrayList<>();
    private DnsException mDnsException;

    public HttpsEndpointAccumulator(@NonNull Network network,
            @NonNull DnsResolver.Callback<HttpsEndpoint> callback, int queryCount,
            int timeoutMillis, boolean hasIpv4, boolean hasIpv6) {
        mNetwork = network;
        mUserCallback = callback;
        mTargetQueryCount = queryCount;
        mHttpsTimeoutMillis = timeoutMillis;
        mHasIpv4 = hasIpv4;
        mHasIpv6 = hasIpv6;
    }

    @Override
    public void onAnswer(@NonNull byte[] answer, int rcode) {
        Objects.requireNonNull(answer, "Raw byte response for query cannot be null");

        DnsPacket dnsPacket = new DnsPacket(answer);
        final List<DnsRecord> questionRecords = dnsPacket.getRecords(QDSECTION);
        final int questionCount = questionRecords.size();
        if (questionCount != 1) {
            reportError(
                    ERROR_PARSE, new ParseException("Unexpected question count: " + questionCount));
            return;
        }

        final int queryType = questionRecords.get(0).nsType;

        if (queryType != TYPE_A && queryType != TYPE_AAAA && queryType != TYPE_HTTPS) {
            reportError(ERROR_PARSE, new ParseException("Unsupported query type: " + queryType));
            return;
        }

        final List<DnsRecord> answerRecords = dnsPacket.getRecords(ANSECTION);
        // Handle the NODATA scenario and still record it as a received answer type, so we don't
        // wait indefinitely for a record that will never come.
        if (answerRecords.isEmpty()) {
            mReceivedAnswerTypes.add(queryType);
        }

        for (DnsRecord record : answerRecords) {
            int answerType = record.nsType;

            if (queryType != answerType) {
                // Ignore any answers where the query type doesn't match the answer type.
                continue;
            }

            try {
                switch (answerType) {
                    case TYPE_A, TYPE_AAAA -> {
                        mAddresses.add(InetAddress.getByAddress(record.getRR()));
                        mReceivedAnswerTypes.add(answerType);
                    }
                    case TYPE_HTTPS -> {
                        HttpsRecord httpsRecord =
                                new HttpsRecord(mNetwork, (DnsHttpsRecord) record);
                        mHttpsRecords.add(httpsRecord);

                        // Add only the relevant IP hints to the list of IP addresses.
                        if (mHasIpv4) mAddresses.addAll(httpsRecord.getIpv4Hints());
                        if (mHasIpv6) mAddresses.addAll(httpsRecord.getIpv6Hints());

                        mReceivedAnswerTypes.add(answerType);
                    }
                    default -> {
                        // This shouldn't happen, since we already checked that the answer type is
                        // in the supported set.
                        reportError(ERROR_PARSE,
                                new ParseException("Unexpected answer type: " + answerType));
                    }
                }
            } catch (DnsPacket.ParseException e) {
                reportError(ERROR_PARSE, new ParseException(e.reason, e.getCause()));
            } catch (ClassCastException e) {
                reportError(ERROR_PARSE, e);
            } catch (UnknownHostException e) {
                reportError(ERROR_SYSTEM, e);
            }
        }

        if (!mHttpsRecords.isEmpty() && !mAddresses.isEmpty()) {
            // Return early if we got the HTTPS record first, and it contained IP hints.
            reportAnswer(rcode);
        } else if (mTargetQueryCount == mReceivedAnswerTypes.size()) {
            // Return if we have gotten all the records we expected.
            reportAnswer(rcode);
        }
        // TODO(b/448882639): handle filtering out HTTPS records with specified mandatory values
        // that are absent
        // TODO(b/448882639): handle timeout logic for HTTPS record
        // TODO(b/448882639): handle if the HTTPS record name does not match the A/AAAA ones
        // TODO(b/448882639): handle parsing the CNAME chain for the A/AAAA records
        // TODO(b/448882639): ensure that reportError or reportAnswer is called exactly once
    }

    @Override
    public void onError(@NonNull DnsException error) {
        mDnsException = error;
        mUserCallback.onError(mDnsException);
    }

    private void reportAnswer(int rcode) {
        Collections.sort(mHttpsRecords, Comparator.comparing(HttpsRecord::getPriority));
        mUserCallback.onAnswer(
                new HttpsEndpoint(
                        mNetwork,
                        mHttpsRecords,
                        new ArrayList<>(mAddresses)), rcode);
    }

    private void reportError(int errorType, @NonNull Throwable cause) {
        mDnsException = new DnsException(errorType, cause);
        mUserCallback.onError(mDnsException);
    }
}
