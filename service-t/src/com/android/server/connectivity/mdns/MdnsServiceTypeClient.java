/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.connectivity.mdns;

import static com.android.net.module.util.CollectionUtils.prependArray;
import static com.android.net.module.util.HandlerUtils.ensureRunningOnHandlerThread;
import static com.android.server.connectivity.mdns.MdnsConstants.SERVICE_REMOVED_BY_GOODBYE_RECEIVED;
import static com.android.server.connectivity.mdns.MdnsConstants.SERVICE_REMOVED_BY_SOCKET_DESTROYED;
import static com.android.server.connectivity.mdns.MdnsConstants.SERVICE_REMOVED_BY_TTL_EXPIRED;
import static com.android.server.connectivity.mdns.MdnsConstants.getServiceRemovedMessage;
import static com.android.server.connectivity.mdns.MdnsQueryScheduler.ScheduledQueryTaskArgs;
import static com.android.server.connectivity.mdns.MdnsSearchOptions.AGGRESSIVE_QUERY_MODE;
import static com.android.server.connectivity.mdns.MdnsServiceCache.ServiceExpiredCallback;
import static com.android.server.connectivity.mdns.MdnsServiceCache.findMatchedResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.Clock;
import static com.android.server.connectivity.mdns.util.MdnsUtils.buildMdnsServiceInfoFromResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.convertNsdServiceInfoToMdnsResponse;
import static com.android.server.connectivity.mdns.util.MdnsUtils.createOffloadServiceInfoFromDiscoveryOffload;
import static com.android.server.connectivity.mdns.util.MdnsUtils.responseMatchesInstanceNameAndSubtypes;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.nsd.NsdServiceInfo;
import android.net.nsd.OffloadEngine;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.DnsUtils;
import com.android.net.module.util.SharedLog;
import com.android.server.connectivity.mdns.util.MdnsUtils;

import java.io.IOException;
import java.io.PrintWriter;
import java.net.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.function.Function;

/**
 * Instance of this class sends and receives mDNS packets of a given service type and invoke
 * registered {@link MdnsServiceBrowserListener} instances.
 */
public class MdnsServiceTypeClient {

    private static final String TAG = MdnsServiceTypeClient.class.getSimpleName();
    private static final boolean DBG = MdnsDiscoveryManager.DBG;
    @VisibleForTesting
    static final int EVENT_START_QUERYTASK = 1;
    static final int EVENT_QUERY_RESULT = 2;
    static final int EVENT_REMOVE_EXPIRED_SERVICES = 3;
    static final int INVALID_TRANSACTION_ID = -1;
    static final long REMOVE_SERVICE_AFTER_QUERY_SENT_TIME = 2000L;
    static final String SERVICE_NAME_DISCOVERY = "";
    static final String NO_HOSTNAME = "";
    static final String NO_SUBTYPE = "";
    private final String serviceType;
    private final String[] serviceTypeLabels;
    private final MdnsSocketClientBase socketClient;
    private final MdnsResponseDecoder responseDecoder;
    private final ScheduledExecutorService executor;
    @NonNull private final SocketKey socketKey;
    @NonNull private final SharedLog sharedLog;
    @NonNull private final Handler handler;
    @Nullable private final MdnsQueryScheduler mdnsQueryScheduler;
    @NonNull private final Dependencies dependencies;
    /**
     * The service caches for each socket. It should be accessed from looper thread only.
     */
    @NonNull private final MdnsServiceCache serviceCache;
    @NonNull private final MdnsServiceCache.CacheKey cacheKey;
    @NonNull private final ServiceExpiredCallback serviceExpiredCallback =
            new ServiceExpiredCallback() {
                @Override
                public void onServiceRecordExpired(@NonNull MdnsResponse previousResponse,
                        @Nullable MdnsResponse newResponse) {
                    notifyRemovedServiceToListeners(
                            previousResponse, SERVICE_REMOVED_BY_TTL_EXPIRED);
                }
            };
    @NonNull private final MdnsFeatureFlags featureFlags;
    @NonNull private final OffloadCallback offloadCallback;
    private final ArrayMap<MdnsServiceBrowserListener, ListenerInfo> listeners =
            new ArrayMap<>();
    /**
     * Information used for offloaded mDNS discovery.
     *
     * <p>This map is keyed by service name (or SERVICE_NAME_DISCOVERY for discovery).
     */
    private final ArrayMap<String, DiscoveryOffloadInfo> offloadInfo = new ArrayMap<>();
    private final boolean removeServiceAfterTtlExpires =
            MdnsConfigs.removeServiceAfterTtlExpires();
    private final Clock clock;
    // Use MdnsRealtimeScheduler for query scheduling, which allows for more accurate sending of
    // queries.
    @Nullable private final Scheduler scheduler;

    @Nullable private MdnsSearchOptions searchOptions;

    // The session ID increases when startSendAndReceive() is called where we schedule a
    // QueryTask for
    // new subtypes. It stays the same between packets for same subtypes.
    private long currentSessionId = 0;
    private long lastSentTime;

    /**
     * Represents information used for offloaded discovery.
     */
    public static class DiscoveryOffloadInfo {

        /**
         * The combined offload type for offloaded discovery, indicating filtering replies
         * and handling queries.
         */
        public static final int OFFLOAD_TYPE =
                OffloadEngine.OFFLOAD_TYPE_FILTER_REPLIES | OffloadEngine.OFFLOAD_TYPE_QUERY;

        /**
         * The name of the service to filter for.
         */
        public final String serviceName;
        /**
         * The type of the service to filter for (e.g., "_http._tcp.").
         */
        public final String serviceType;
        /**
         * A list of service subtypes to filter for.
         * Can be an empty list if no specific subtypes are desired.
         */
        public final List<String> subtypes;
        /**
         * The hostname of the service to filter for.
         * Can be empty if filtering is not based on hostname.
         */
        public final String hostname;

        public DiscoveryOffloadInfo(@NonNull String serviceName, @NonNull String serviceType,
                @NonNull List<String> subtypes, @NonNull String hostname) {
            this.serviceName = serviceName;
            this.serviceType = serviceType;
            this.subtypes = Collections.unmodifiableList(subtypes);
            this.hostname = hostname;
        }

        /**
         * Returns a string representation of the FilterRepliesInfo object.
         *
         * @return A string containing the serviceName, serviceType, subtypes, and hostname.
         */
        @Override
        public String toString() {
            return "FilterRepliesInfo{serviceName='" + serviceName
                    + ", serviceType='" + serviceType
                    + ", subtypes=" + subtypes
                    + ", hostname='" + hostname + '}';
        }

        /**
         * Indicates whether some other object is "equal to" this one.
         * Two FilterRepliesInfo objects are considered equal if all their fields
         * (serviceName, serviceType, subtypes, and hostname) are equal.
         *
         * @param other The reference object with which to compare.
         * @return true if this object is the same as the obj argument; false otherwise.
         */
        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof DiscoveryOffloadInfo)) {
                return false;
            }
            final DiscoveryOffloadInfo that = (DiscoveryOffloadInfo) other;
            return serviceName.equals(that.serviceName)
                    && serviceType.equals(that.serviceType)
                    && Objects.equals(subtypes, that.subtypes)
                    && hostname.equals(that.hostname);
        }

        /**
         * Returns a hash code value for the object. This method is supported for the benefit of
         * hash tables such as those provided by {@link java.util.HashMap}.
         *
         * @return A hash code value for this object.
         */
        @Override
        public int hashCode() {
            return Objects.hash(serviceName, serviceType, subtypes, hostname);
        }
    }

    private static class ListenerInfo {
        @NonNull
        final MdnsSearchOptions searchOptions;
        final Set<String> discoveredServiceNames;
        DiscoveryOffloadInfo mDiscoveryOffloadInfo;

        ListenerInfo(@NonNull MdnsSearchOptions searchOptions,
                @Nullable ListenerInfo previousInfo, @NonNull String serviceType) {
            this.searchOptions = searchOptions;
            this.discoveredServiceNames = previousInfo == null
                    ? MdnsUtils.newSet() : previousInfo.discoveredServiceNames;
            final String resolveName = searchOptions.getResolveInstanceName();
            this.mDiscoveryOffloadInfo = new DiscoveryOffloadInfo(
                    resolveName != null ? resolveName : SERVICE_NAME_DISCOVERY, serviceType,
                    searchOptions.getSubtypes(), NO_HOSTNAME);
        }

        /**
         * Set the given service name as discovered.
         *
         * @return true if the service name was not discovered before.
         */
        boolean setServiceDiscovered(@NonNull String serviceName) {
            return discoveredServiceNames.add(DnsUtils.toDnsUpperCase(serviceName));
        }

        /**
         * Unset the given service name as discovered.
         *
         * @return true if the service name was discovered before.
         */
        boolean unsetServiceDiscovered(@NonNull String serviceName) {
            return discoveredServiceNames.remove(DnsUtils.toDnsUpperCase(serviceName));
        }

        /**
         * Updates the hostname used for discovery offload.
         * <p>
         * The hostname is only set if the search options specify a particular service
         * instance name to resolve. Otherwise, it remains empty.
         *
         * @param hostname The new hostname to use for filtering.
         */
        void updateDiscoveryOffloadHostname(@NonNull String hostname) {
            // The hostname is only set for resolution or service information callback requests.
            // Since discovery can find numerous services, replies for other services could be
            // blocked if a hostname is assigned to specific one. Consequently, for discovery
            // requests, the hostname should remain empty.
            mDiscoveryOffloadInfo = new DiscoveryOffloadInfo(
                    mDiscoveryOffloadInfo.serviceName,
                    mDiscoveryOffloadInfo.serviceType,
                    mDiscoveryOffloadInfo.subtypes,
                    searchOptions.getResolveInstanceName() != null ? hostname : NO_HOSTNAME);
        }
    }

    private class QueryTaskHandler extends Handler {
        QueryTaskHandler(Looper looper) {
            super(looper);
        }

        @Override
        @SuppressWarnings("FutureReturnValueIgnored")
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case EVENT_START_QUERYTASK: {
                    if (mdnsQueryScheduler == null) {
                        Log.wtf(TAG, "Query task should not be triggered in receive-only mode");
                        return;
                    }
                    final ScheduledQueryTaskArgs taskArgs = (ScheduledQueryTaskArgs) msg.obj;
                    // QueryTask should be run immediately after being created (not be scheduled in
                    // advance). Because the result of "makeResponsesForResolve" depends on answers
                    // that were received before it is called, so to take into account all answers
                    // before sending the query, it needs to be called just before sending it.
                    final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(
                            /* resolveAllInCache= */hasResolveAllQuery());
                    final QueryTask queryTask = new QueryTask(taskArgs, servicesToResolve,
                            getAllDiscoverySubtypes(), needSendDiscoveryQueries(listeners),
                            getExistingServices(), searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                            socketKey);
                    try {
                        executor.submit(queryTask);
                    } catch (RejectedExecutionException exception) {
                        sharedLog.e("Submit a query task after the executor service is shut down");
                    }
                    break;
                }
                case EVENT_QUERY_RESULT: {
                    if (mdnsQueryScheduler == null) {
                        Log.wtf(TAG, "Query result should not be triggered in receive-only mode");
                        return;
                    }
                    final QuerySentResult sentResult = (QuerySentResult) msg.obj;
                    // If a task is cancelled while the Executor is running it, EVENT_QUERY_RESULT
                    // will still be sent when it ends. So use session ID to check if this task
                    // should continue to schedule more.
                    if (sentResult.taskArgs.sessionId != currentSessionId) {
                        break;
                    }

                    if ((sentResult.transactionId != INVALID_TRANSACTION_ID)) {
                        for (int i = 0; i < listeners.size(); i++) {
                            listeners.keyAt(i).onDiscoveryQuerySent(
                                    sentResult.queriedSubtypes, sentResult.transactionId);
                        }
                    }

                    if (!featureFlags.mIsOptimizedExpiredServiceRemovalEnabled) {
                        tryRemoveServiceAfterTtlExpires();
                    }


                    final long now = clock.elapsedRealtime();
                    lastSentTime = now;
                    final long minRemainingTtl = getMinRemainingTtl(now);
                    final ScheduledQueryTaskArgs args =
                            mdnsQueryScheduler.scheduleNextRun(
                                    sentResult.taskArgs.config,
                                    minRemainingTtl,
                                    now,
                                    lastSentTime,
                                    sentResult.taskArgs.sessionId,
                                    searchOptions.getQueryMode(),
                                    searchOptions.numOfQueriesBeforeBackoff(),
                                    false /* forceEnableBackoff */
                            );
                    final long timeToNextTaskMs = calculateTimeToNextTask(args, now);
                    sharedLog.log(String.format("Query sent with transactionId: %d. "
                                    + "Next run: sessionId: %d, in %d ms",
                            sentResult.transactionId, args.sessionId, timeToNextTaskMs));
                    if (scheduler != null) {
                        setDelayedTask(args, timeToNextTaskMs);
                    } else {
                        dependencies.sendMessageDelayed(
                                handler,
                                handler.obtainMessage(EVENT_START_QUERYTASK, args),
                                timeToNextTaskMs);
                    }
                    if (featureFlags.mIsOptimizedExpiredServiceRemovalEnabled) {
                        // Update the first query time based on the query result
                        if (sentResult.queriedBaseType) {
                            serviceCache.updateFirstQueryTimeForCachedServices(
                                    null /* serviceName */,
                                    Collections.emptyList() /* subtypes */, cacheKey, now);
                        }
                        if (sentResult.queriedSubtypes.size() != 0) {
                            serviceCache.updateFirstQueryTimeForCachedServices(
                                    null /* serviceName */,
                                    sentResult.queriedSubtypes, cacheKey, now);
                        }
                        for (MdnsResponse service : sentResult.resolvedServices) {
                            serviceCache.updateFirstQueryTimeForCachedServices(
                                    service.getServiceInstanceName(),
                                    Collections.emptyList() /* subtypes */, cacheKey, now);
                        }

                        // A query is sent. Schedule a task with a delay to wait for responses, and
                        // then remove expired services, and notify listeners.
                        if (scheduler != null) {
                            scheduler.sendDelayedMessage(
                                    EVENT_REMOVE_EXPIRED_SERVICES, 0, 0, null /* obj */,
                                    REMOVE_SERVICE_AFTER_QUERY_SENT_TIME);
                        } else {
                            dependencies.sendMessageDelayed(
                                    handler,
                                    handler.obtainMessage(EVENT_REMOVE_EXPIRED_SERVICES),
                                    REMOVE_SERVICE_AFTER_QUERY_SENT_TIME);
                        }
                    }
                    break;
                }
                case EVENT_REMOVE_EXPIRED_SERVICES: {
                    serviceCache.removeExpiredServicesAndNotifyListeners(
                            cacheKey, clock.elapsedRealtime());
                    break;
                }
                default:
                    sharedLog.e("Unrecognized event " + msg.what);
                    break;
            }
        }
    }

    /**
     * Dependencies of MdnsServiceTypeClient, for injection in tests.
     */
    @VisibleForTesting(otherwise = VisibleForTesting.PACKAGE_PRIVATE)
    public static class Dependencies {
        /**
         * @see Handler#sendMessageDelayed(Message, long)
         */
        public void sendMessageDelayed(@NonNull Handler handler, @NonNull Message message,
                long delayMillis) {
            handler.sendMessageDelayed(message, delayMillis);
        }

        /**
         * @see Handler#removeMessages(int)
         */
        public void removeMessages(@NonNull Handler handler, int what) {
            handler.removeMessages(what);
        }

        /**
         * @see Handler#hasMessages(int)
         */
        public boolean hasMessages(@NonNull Handler handler, int what) {
            return handler.hasMessages(what);
        }

        /**
         * @see Handler#post(Runnable)
         */
        public void sendMessage(@NonNull Handler handler, @NonNull Message message) {
            handler.sendMessage(message);
        }

        /**
         * Generate the DatagramPackets from given MdnsPacket and InetSocketAddress.
         *
         * <p> If the query with known answer feature is enabled and the MdnsPacket is too large for
         *     a single DatagramPacket, it will be split into multiple DatagramPackets.
         */
        public List<DatagramPacket> getDatagramPacketsFromMdnsPacket(
                @NonNull byte[] packetCreationBuffer, @NonNull MdnsPacket packet,
                @NonNull InetSocketAddress address, boolean isQueryWithKnownAnswer)
                throws IOException {
            if (isQueryWithKnownAnswer) {
                return MdnsUtils.createQueryDatagramPackets(packetCreationBuffer, packet, address);
            } else {
                final byte[] queryBuffer =
                        MdnsUtils.createRawDnsPacket(packetCreationBuffer, packet);
                return List.of(new DatagramPacket(queryBuffer, 0, queryBuffer.length, address));
            }
        }

        /**
         * @see Scheduler
         */
        @Nullable
        public Scheduler createScheduler(@NonNull Handler handler) {
            return SchedulerFactory.createScheduler(handler);
        }
    }

    /**
     * Constructor of {@link MdnsServiceTypeClient}.
     *
     * @param socketClient Sends and receives mDNS packet.
     * @param executor         A {@link ScheduledExecutorService} used to schedule query tasks.
     */
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull MdnsServiceCache serviceCache,
            @NonNull MdnsFeatureFlags featureFlags,
            @NonNull OffloadCallback offloadCallback,
            boolean isReceiveOnly) {
        this(serviceType, socketClient, executor, new Clock(), socketKey, sharedLog, looper,
                new Dependencies(), serviceCache, featureFlags, offloadCallback, isReceiveOnly);
    }

    @VisibleForTesting
    public MdnsServiceTypeClient(
            @NonNull String serviceType,
            @NonNull MdnsSocketClientBase socketClient,
            @NonNull ScheduledExecutorService executor,
            @NonNull Clock clock,
            @NonNull SocketKey socketKey,
            @NonNull SharedLog sharedLog,
            @NonNull Looper looper,
            @NonNull Dependencies dependencies,
            @NonNull MdnsServiceCache serviceCache,
            @NonNull MdnsFeatureFlags featureFlags,
            @NonNull OffloadCallback offloadCallback,
            boolean isReceiveOnly) {
        this.serviceType = serviceType;
        this.socketClient = socketClient;
        this.executor = executor;
        this.serviceTypeLabels = TextUtils.split(serviceType, "\\.");
        this.responseDecoder = new MdnsResponseDecoder(clock, serviceTypeLabels);
        this.clock = clock;
        this.socketKey = socketKey;
        this.sharedLog = sharedLog;
        this.handler = new QueryTaskHandler(looper);
        this.dependencies = dependencies;
        this.serviceCache = serviceCache;
        this.mdnsQueryScheduler = !isReceiveOnly ? new MdnsQueryScheduler() : null;
        this.cacheKey = new MdnsServiceCache.CacheKey(serviceType, socketKey);
        this.featureFlags = featureFlags;
        this.scheduler = featureFlags.isAccurateDelayCallbackEnabled()
                ? dependencies.createScheduler(handler) : null;
        this.offloadCallback = offloadCallback;
    }

    /**
     * Do the cleanup of the MdnsServiceTypeClient
     */
    private void shutDown() {
        serviceCache.unregisterServiceExpiredCallback(cacheKey);
        if (mdnsQueryScheduler != null) {
            removeScheduledTask();
            mdnsQueryScheduler.cancelScheduledRun();
        }
        if (scheduler != null) {
            scheduler.close();
        }
    }

    private List<MdnsResponse> getExistingServices() {
        return featureFlags.isQueryWithKnownAnswerEnabled()
                ? serviceCache.getCachedServices(cacheKey, true /* excludeExpiredServices */)
                : Collections.emptyList();
    }

    private void setDelayedTask(ScheduledQueryTaskArgs args, long timeToNextTaskMs) {
        scheduler.removeDelayedMessage(EVENT_START_QUERYTASK);
        scheduler.sendDelayedMessage(EVENT_START_QUERYTASK, 0, 0, args,
                timeToNextTaskMs);
    }

    private DiscoveryOffloadInfo getDiscoveryOffloadInfo() {
        final Set<String> combinedSubtypes = new ArraySet<>();
        for (int i = 0; i < listeners.size(); i++) {
            final DiscoveryOffloadInfo info = listeners.valueAt(i).mDiscoveryOffloadInfo;
            if (!info.serviceName.equals(SERVICE_NAME_DISCOVERY)) continue;

            // The discovery requests for the base type are represented by an empty value in the
            // subtype list.  This empty value in the subtype list means the offload engine should
            // also offload queries for the base type.
            if (info.subtypes.isEmpty()) {
                combinedSubtypes.add(NO_SUBTYPE);
            } else {
                combinedSubtypes.addAll(info.subtypes);
            }
        }

        // Update the info with combined subtypes
        if (!combinedSubtypes.isEmpty()) {
            return new DiscoveryOffloadInfo(SERVICE_NAME_DISCOVERY, serviceType,
                    new ArrayList<>(combinedSubtypes), NO_HOSTNAME);
        } else {
            return null;
        }
    }

    private void updateOffloadInfo(@NonNull String serviceName,
            @Nullable DiscoveryOffloadInfo newInfo) {
        if (!featureFlags.mIsSelectiveMdnsResponseOffloadEnabled) return;

        final DiscoveryOffloadInfo combinedInfo;
        if (!serviceName.equals(SERVICE_NAME_DISCOVERY)) { // Resolution
            combinedInfo = newInfo;
        } else { // Discovery
            combinedInfo = getDiscoveryOffloadInfo();
        }

        final DiscoveryOffloadInfo oldInfo = offloadInfo.get(serviceName);
        if (combinedInfo == null) {
            offloadInfo.remove(serviceName);
            if (oldInfo != null) {
                offloadCallback.onOffloadStop(
                        socketKey.getInterfaceName(),
                        createOffloadServiceInfoFromDiscoveryOffload(
                                oldInfo,
                                DiscoveryOffloadInfo.OFFLOAD_TYPE
                        )
                );
            }
        } else {
            offloadInfo.put(serviceName, combinedInfo);
            if (oldInfo == null || !oldInfo.equals(combinedInfo)) {
                offloadCallback.onOffloadStartOrUpdate(
                        socketKey.getInterfaceName(),
                        createOffloadServiceInfoFromDiscoveryOffload(
                                combinedInfo,
                                DiscoveryOffloadInfo.OFFLOAD_TYPE
                        )
                );
            }
        }
    }

    /**
     * Registers {@code listener} for receiving discovery event of mDNS service instances, and
     * starts
     * (or continue) to send mDNS queries periodically.
     *
     * @param listener      The {@link MdnsServiceBrowserListener} to register.
     * @param searchOptions {@link MdnsSearchOptions} contains the list of subtypes to discover.
     */
    @SuppressWarnings("FutureReturnValueIgnored")
    public void startSendAndReceive(
            @NonNull MdnsServiceBrowserListener listener,
            @NonNull MdnsSearchOptions searchOptions) {
        ensureRunningOnHandlerThread(handler);
        this.searchOptions = searchOptions;
        boolean hadReply = false;
        final ListenerInfo existingInfo = listeners.get(listener);
        final ListenerInfo listenerInfo =
                new ListenerInfo(searchOptions, existingInfo, serviceType);
        listeners.put(listener, listenerInfo);
        if (existingInfo == null) {
            for (MdnsResponse existingResponse : serviceCache.getCachedServices(
                    cacheKey, true /* excludeExpiredServices */)) {
                if (!responseMatchesInstanceNameAndSubtypes(existingResponse,
                        searchOptions.getResolveInstanceName(), searchOptions.getSubtypes())) {
                    continue;
                }
                final MdnsServiceInfo info = buildMdnsServiceInfoFromResponse(
                        existingResponse, serviceTypeLabels, clock.elapsedRealtime(), socketKey);
                listener.onServiceNameDiscovered(info, true /* isServiceFromCache */);
                listenerInfo.setServiceDiscovered(info.getServiceInstanceName());
                if (existingResponse.isComplete()) {
                    listener.onServiceFound(info, true /* isServiceFromCache */);
                    listenerInfo.updateDiscoveryOffloadHostname(MdnsRecord.labelsToString(
                            existingResponse.getServiceRecord().getServiceHost()));
                    hadReply = true;
                }
            }
        }
        serviceCache.registerServiceExpiredCallback(cacheKey, serviceExpiredCallback);
        updateOffloadInfo(
                listenerInfo.mDiscoveryOffloadInfo.serviceName,
                listenerInfo.mDiscoveryOffloadInfo);
        if (mdnsQueryScheduler != null) {
            scheduleInitialQuery(searchOptions, hadReply);
        }
    }

    private void scheduleInitialQuery(@androidx.annotation.NonNull MdnsSearchOptions searchOptions,
            boolean hadReply) {
        // Remove the next scheduled periodical task.
        removeScheduledTask();
        final boolean forceEnableBackoff =
                (searchOptions.getQueryMode() == AGGRESSIVE_QUERY_MODE && hadReply);
        // Keep the latest scheduled run for rescheduling if there is a service in the cache.
        if (!(forceEnableBackoff)) {
            mdnsQueryScheduler.cancelScheduledRun();
        }
        final QueryTaskConfig taskConfig = new QueryTaskConfig(searchOptions.getQueryMode());
        final long now = clock.elapsedRealtime();
        if (lastSentTime == 0) {
            lastSentTime = now;
        }
        final long minRemainingTtl = getMinRemainingTtl(now);
        if (hadReply) {
            final ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.scheduleNextRun(
                            taskConfig,
                            minRemainingTtl,
                            now,
                            lastSentTime,
                            currentSessionId,
                            searchOptions.getQueryMode(),
                            searchOptions.numOfQueriesBeforeBackoff(),
                            forceEnableBackoff
                    );
            final long timeToNextTaskMs = calculateTimeToNextTask(args, now);
            sharedLog.log(String.format("Schedule a query. Next run: sessionId: %d, in %d ms",
                    args.sessionId, timeToNextTaskMs));
            if (scheduler != null) {
                setDelayedTask(args, timeToNextTaskMs);
            } else {
                dependencies.sendMessageDelayed(
                        handler,
                        handler.obtainMessage(EVENT_START_QUERYTASK, args),
                        timeToNextTaskMs);
            }
        } else {
            final List<MdnsResponse> servicesToResolve = makeResponsesForResolve(
                    /* resolveAllInCache= */hasResolveAllQuery());
            final QueryTask queryTask = new QueryTask(
                    mdnsQueryScheduler.scheduleFirstRun(taskConfig, now,
                            minRemainingTtl, currentSessionId), servicesToResolve,
                    getAllDiscoverySubtypes(), needSendDiscoveryQueries(listeners),
                    getExistingServices(), searchOptions.onlyUseIpv6OnIpv6OnlyNetworks(),
                    socketKey);
            executor.submit(queryTask);
        }
    }

    private Set<String> getAllDiscoverySubtypes() {
        final Set<String> subtypes = MdnsUtils.newSet();
        for (int i = 0; i < listeners.size(); i++) {
            final MdnsSearchOptions listenerOptions = listeners.valueAt(i).searchOptions;
            subtypes.addAll(listenerOptions.getSubtypes());
        }
        return subtypes;
    }

    /**
     * Get the executor service.
     */
    public ScheduledExecutorService getExecutor() {
        return executor;
    }

    /**
     * Get the cache key for this service type client.
     */
    @NonNull
    public MdnsServiceCache.CacheKey getCacheKey() {
        return cacheKey;
    }

    private void removeScheduledTask() {
        if (scheduler != null) {
            scheduler.removeDelayedMessage(EVENT_START_QUERYTASK);
        } else {
            dependencies.removeMessages(handler, EVENT_START_QUERYTASK);
        }
        sharedLog.log("Remove EVENT_START_QUERYTASK"
                + ", current session: " + currentSessionId);
        ++currentSessionId;
    }

    /**
     * Unregisters {@code listener} from receiving discovery event of mDNS service instances.
     *
     * @param listener The {@link MdnsServiceBrowserListener} to unregister.
     * @return {@code true} if no listener is registered with this client after unregistering {@code
     * listener}. Otherwise returns {@code false}.
     */
    public boolean stopSendAndReceive(@NonNull MdnsServiceBrowserListener listener) {
        ensureRunningOnHandlerThread(handler);
        final ListenerInfo listenerInfo = listeners.remove(listener);
        if (listenerInfo == null) {
            return listeners.isEmpty();
        }
        updateOffloadInfo(listenerInfo.mDiscoveryOffloadInfo.serviceName, null /* newInfo */);
        if (listeners.isEmpty()) {
            shutDown();
        }
        return listeners.isEmpty();
    }

    /**
     * Process an incoming response from the proxy offload engine
     * @param serviceInfo The {@link NsdServiceInfo} information for network service discovery
     * @param isServiceLost If true, this indicates that service is no longer valid and
     * should be removed
     */
    public void processProxyOffloadEngineResponse(
            @NonNull NsdServiceInfo serviceInfo, boolean isServiceLost) {
        ensureRunningOnHandlerThread(handler);
        long responseReceiveTime = clock.elapsedRealtime();
        MdnsResponse response = convertNsdServiceInfoToMdnsResponse(serviceInfo, isServiceLost,
                socketKey, responseReceiveTime, featureFlags);

        if (response == null) {
            return;
        }
        if (response.isGoodbye()) {
            onGoodbyeReceived(response.getServiceInstanceName());
        } else {
            onResponseModified(response);
        }
    }

    /**
     * Process an incoming response packet.
     */
    public synchronized void processResponse(@NonNull MdnsPacket packet,
            @NonNull SocketKey socketKey) {
        ensureRunningOnHandlerThread(handler);
        // Combine the received answer with everything that is already known, meaning every service
        // in cache + responses generated from resolve requests.
        // Expired services are also needed because the response may include them.
        final List<MdnsResponse> cachedList =
                serviceCache.getCachedServices(cacheKey, false /* excludeExpiredServices */);
        final List<MdnsResponse> currentList = makeResponsesFromCacheAndResolveQueries(cachedList);
        final Pair<Set<MdnsResponse>, ArrayList<MdnsResponse>> augmentedResult =
                responseDecoder.augmentResponses(packet, currentList,
                        socketKey.getInterfaceIndex(), socketKey.getNetwork(), featureFlags);

        final Set<MdnsResponse> modifiedResponse = augmentedResult.first;
        final ArrayList<MdnsResponse> allResponses = augmentedResult.second;

        for (MdnsResponse response : allResponses) {
            final String serviceInstanceName = response.getServiceInstanceName();
            if (modifiedResponse.contains(response)) {
                if (response.isGoodbye()) {
                    onGoodbyeReceived(serviceInstanceName);
                } else {
                    onResponseModified(response);
                }
            } else if (findMatchedResponse(cachedList, serviceInstanceName) != null) {
                // If the response is not modified and already in the cache. The cache will
                // need to be updated to refresh the last receipt time.
                serviceCache.addOrUpdateService(cacheKey, response);
                if (DBG) {
                    sharedLog.v("Update the last receipt time for service:"
                            + serviceInstanceName);
                }
            }
        }
        final boolean hasScheduledTask = scheduler != null
                ? scheduler.hasDelayedMessage(EVENT_START_QUERYTASK)
                : dependencies.hasMessages(handler, EVENT_START_QUERYTASK);
        if (hasScheduledTask) {
            final long now = clock.elapsedRealtime();
            final long minRemainingTtl = getMinRemainingTtl(now);
            final ScheduledQueryTaskArgs args =
                    mdnsQueryScheduler.maybeRescheduleCurrentRun(now, minRemainingTtl,
                            lastSentTime, currentSessionId + 1,
                            searchOptions.numOfQueriesBeforeBackoff());
            if (args != null) {
                removeScheduledTask();
                final long timeToNextTaskMs = calculateTimeToNextTask(args, now);
                sharedLog.log(String.format("Reschedule a query. Next run: sessionId: %d, in %d ms",
                        args.sessionId, timeToNextTaskMs));
                if (scheduler != null) {
                    setDelayedTask(args, timeToNextTaskMs);
                } else {
                    dependencies.sendMessageDelayed(
                            handler,
                            handler.obtainMessage(EVENT_START_QUERYTASK, args),
                            timeToNextTaskMs);
                }
            }
        }
    }

    public synchronized void onFailedToParseMdnsResponse(int receivedPacketNumber, int errorCode) {
        ensureRunningOnHandlerThread(handler);
        for (int i = 0; i < listeners.size(); i++) {
            listeners.keyAt(i).onFailedToParseMdnsResponse(receivedPacketNumber, errorCode);
        }
    }

    private void notifyRemovedServiceToListeners(@NonNull MdnsResponse response,
            int serviceRemovedReason) {
        final String serviceInstanceName = response.getServiceInstanceName();
        if (serviceInstanceName == null) {
            return;
        }

        for (int i = 0; i < listeners.size(); i++) {
            final ListenerInfo listenerInfo = listeners.valueAt(i);
            if (!responseMatchesInstanceNameAndSubtypes(response,
                    listenerInfo.searchOptions.getResolveInstanceName(),
                    listenerInfo.searchOptions.getSubtypes())) {
                continue;
            }

            if (!listenerInfo.unsetServiceDiscovered(serviceInstanceName)) {
                // Skip the lost callback if this service has not been notified previously
                continue;
            }

            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                    response, serviceTypeLabels, clock.elapsedRealtime(), socketKey);

            if (response.isComplete()) {
                sharedLog.log(getServiceRemovedMessage(serviceRemovedReason)
                        + ". onServiceRemoved: " + serviceInfo);
                listener.onServiceRemoved(serviceInfo, serviceRemovedReason);
            }
            sharedLog.log(getServiceRemovedMessage(serviceRemovedReason)
                    + ". onServiceNameRemoved: " + serviceInfo);
            listener.onServiceNameRemoved(serviceInfo, serviceRemovedReason);
        }
    }

    /** Notify all services are removed because the socket is destroyed. */
    public void notifySocketDestroyed() {
        ensureRunningOnHandlerThread(handler);
        for (MdnsResponse response : serviceCache.getCachedServices(
                cacheKey, false /* excludeExpiredServices */)) {
            notifyRemovedServiceToListeners(response, SERVICE_REMOVED_BY_SOCKET_DESTROYED);
        }
        // Remove all listeners and update offload info.
        while (!listeners.isEmpty()) {
            final ListenerInfo listenerInfo = listeners.removeAt(listeners.size() - 1);
            updateOffloadInfo(listenerInfo.mDiscoveryOffloadInfo.serviceName, null /* newInfo */);
        }
        shutDown();
    }

    private void onResponseModified(@NonNull MdnsResponse response) {
        final String serviceInstanceName = response.getServiceInstanceName();
        final MdnsResponse currentResponse = serviceCache.getCachedService(serviceInstanceName,
                cacheKey, false /* excludeExpiredServices */);

        final boolean newInCache = currentResponse == null;
        boolean serviceBecomesComplete = false;
        if (newInCache) {
            if (serviceInstanceName != null) {
                serviceCache.addOrUpdateService(cacheKey, response);
            }
        } else {
            boolean before = currentResponse.isComplete();
            serviceCache.addOrUpdateService(cacheKey, response);
            boolean after = response.isComplete();
            serviceBecomesComplete = !before && after;
        }
        sharedLog.i(String.format(
                "Handling response from service: %s, newInCache: %b, serviceBecomesComplete:"
                        + " %b, responseIsComplete: %b",
                serviceInstanceName, newInCache, serviceBecomesComplete,
                response.isComplete()));
        final MdnsServiceInfo serviceInfo = buildMdnsServiceInfoFromResponse(
                response, serviceTypeLabels, clock.elapsedRealtime(), socketKey);

        for (int i = 0; i < listeners.size(); i++) {
            // If a service stops matching the options (currently can only happen if it loses a
            // subtype), service lost callbacks should also be sent; this is not done today as
            // only expiration of SRV records is used, not PTR records used for subtypes, so
            // services never lose PTR record subtypes.
            if (!responseMatchesInstanceNameAndSubtypes(response,
                    listeners.valueAt(i).searchOptions.getResolveInstanceName(),
                    listeners.valueAt(i).searchOptions.getSubtypes())) {
                continue;
            }
            final MdnsServiceBrowserListener listener = listeners.keyAt(i);
            final ListenerInfo listenerInfo = listeners.valueAt(i);
            final boolean newServiceFound = listenerInfo.setServiceDiscovered(serviceInstanceName);
            if (newServiceFound) {
                sharedLog.log("onServiceNameDiscovered: " + serviceInfo);
                listener.onServiceNameDiscovered(serviceInfo, false /* isServiceFromCache */);
            }

            if (response.isComplete()) {
                if (newServiceFound || serviceBecomesComplete) {
                    sharedLog.log("onServiceFound: " + serviceInfo);
                    listener.onServiceFound(serviceInfo, false /* isServiceFromCache */);
                } else {
                    sharedLog.log("onServiceUpdated: " + serviceInfo);
                    listener.onServiceUpdated(serviceInfo);
                }
                listenerInfo.updateDiscoveryOffloadHostname(MdnsRecord.labelsToString(
                        response.getServiceRecord().getServiceHost()));
                updateOffloadInfo(
                        listenerInfo.mDiscoveryOffloadInfo.serviceName,
                        listenerInfo.mDiscoveryOffloadInfo);
            }
        }
    }

    private void onGoodbyeReceived(@Nullable String serviceInstanceName) {
        final MdnsResponse response =
                serviceCache.removeService(serviceInstanceName, cacheKey);
        if (response == null) {
            return;
        }
        notifyRemovedServiceToListeners(response, SERVICE_REMOVED_BY_GOODBYE_RECEIVED);
    }

    private boolean shouldRemoveServiceAfterTtlExpires() {
        if (removeServiceAfterTtlExpires) {
            return true;
        }
        return searchOptions != null && searchOptions.removeExpiredService();
    }

    private boolean hasResolveAllQuery() {
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.valueAt(i).searchOptions.resolveAllServices()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Generate a list of {@link MdnsResponse} representing services to resolve.
     *
     * <p>Resolve queries specify the service name so querying the PTR record is not necessary, and
     * only SRV/TXT/address records may be received. However MdnsResponse objects are normally only
     * created when a PTR record is received, so if only SRV/TXT/address records are received, they
     * cannot be added to a service.
     *
     * <p>This method generates a list of MdnsResponse for services that should be resolved, using
     * records from the cache, but creating a MdnsResponse without a PTR record if there is a
     * resolve query but no information in cache for the service.
     *
     * <p>The list can be used to track which SRV/TXT/address records need to be queried for
     * resolving.
     */
    private List<MdnsResponse> makeResponsesForResolve(boolean resolveAllInCache) {
        // Known responses are used by queries to understand what information the cache already
        // holds, allowing it to determine which records need to be renewed. Therefore, expired
        // services should always be included in the returned responses to ensure all their records
        // are renewed.
        final List<MdnsResponse> cachedServices =
                serviceCache.getCachedServices(cacheKey, false /* excludeExpiredServices */);
        if (resolveAllInCache) {
            return makeResponsesFromCacheAndResolveQueries(cachedServices);
        }

        final ArrayMap<String, MdnsResponse> resolveResponses = new ArrayMap<>();
        addUniqueResponsesForResolveListeners(resolveResponses, serviceName -> {
            MdnsResponse response = findMatchedResponse(cachedServices, serviceName);
            if (response == null) {
                return makeResolveResponse(serviceName);
            }
            return response;
        });

        return makeValuesList(resolveResponses);
    }

    /**
     * Generate a map of (uppercase service name) -> MdnsResponse representing all known services.
     *
     * <p>Services may be known by being in cache, or be inferred as documented in
     * {@link #makeResponsesForResolve(boolean)}.
     *
     * <p>The list can be used as a base list of services to update with the received records and
     * add to the cache. Expired services in the cache are also included as the received records
     * may reference / refresh them.
     */
    private List<MdnsResponse> makeResponsesFromCacheAndResolveQueries(
            @NonNull List<MdnsResponse> cachedServices) {
        final ArrayMap<String, MdnsResponse> resolveResponses = new ArrayMap<>();
        for (MdnsResponse response : cachedServices) {
            resolveResponses.put(
                    DnsUtils.toDnsUpperCase(response.getServiceInstanceName()), response);
        }
        addUniqueResponsesForResolveListeners(resolveResponses, this::makeResolveResponse);

        return makeValuesList(resolveResponses);
    }

    /**
     * For each resolve listener, add a response to a (resolve instance name) -> MdnsResponse map.
     *
     * <p>No duplicates are added, so if a resolve instance name is already in a map, it will not
     * be overwritten.
     *
     * @param responses Map of (DNS uppercase instance name) -> MdnsResponse
     * @param responseProvider Provider of the MdnsResponse based on the resolve instance name
     */
    private void addUniqueResponsesForResolveListeners(ArrayMap<String, MdnsResponse> responses,
            Function<String, MdnsResponse> responseProvider) {
        for (int i = 0; i < listeners.size(); i++) {
            final String resolveName = listeners.valueAt(i).searchOptions.getResolveInstanceName();
            if (resolveName == null) {
                continue;
            }
            final String uppercaseResolveName = DnsUtils.toDnsUpperCase(resolveName);
            if (responses.containsKey(uppercaseResolveName)) {
                continue;
            }
            responses.put(uppercaseResolveName, responseProvider.apply(resolveName));
        }
    }

    /**
     * Make a list of values from an {@link ArrayMap}.
     *
     * <p>Iterators are documented to be inefficient for {@link ArrayMap}, so this leverages
     * more efficient {@link ArrayMap} methods.
     */
    private <T> List<T> makeValuesList(ArrayMap<?, T> map) {
        final ArrayList<T> list = new ArrayList<>(map.size());
        map.forEach((k, v) -> list.add(v));
        return list;
    }

    private MdnsResponse makeResolveResponse(String resolveName) {
        final String[] instanceFullName = prependArray(
                String.class, serviceTypeLabels, resolveName);
        return new MdnsResponse(0L /* lastUpdateTime */, instanceFullName,
                socketKey.getInterfaceIndex(), socketKey.getNetwork());
    }

    private static boolean needSendDiscoveryQueries(
            @NonNull ArrayMap<MdnsServiceBrowserListener, ListenerInfo> listeners) {
        // Note iterators are discouraged on ArrayMap as per its documentation
        for (int i = 0; i < listeners.size(); i++) {
            if (listeners.valueAt(i).searchOptions.getResolveInstanceName() == null) {
                return true;
            }
        }
        return false;
    }

    private void tryRemoveServiceAfterTtlExpires() {
        if (!shouldRemoveServiceAfterTtlExpires()) return;

        final Iterator<MdnsResponse> iter = serviceCache.getCachedServices(
                cacheKey, false /* excludeExpiredServices */).iterator();
        while (iter.hasNext()) {
            MdnsResponse existingResponse = iter.next();
            if (existingResponse.hasServiceRecord()
                    && existingResponse.getServiceRecord()
                    .getRemainingTTL(clock.elapsedRealtime()) == 0) {
                serviceCache.removeService(existingResponse.getServiceInstanceName(), cacheKey);
                notifyRemovedServiceToListeners(existingResponse, SERVICE_REMOVED_BY_TTL_EXPIRED);
            }
        }
    }

    public static class QuerySentResult {
        private final int transactionId;
        private final List<String> queriedSubtypes = new ArrayList<>();
        private final ScheduledQueryTaskArgs taskArgs;
        private final boolean queriedBaseType;
        private final Collection<MdnsResponse> resolvedServices = new ArrayList<>();

        QuerySentResult(int transactionId, @NonNull List<String> subTypes,
                @NonNull ScheduledQueryTaskArgs taskArgs, boolean queriedBaseType,
                @NonNull Collection<MdnsResponse> resolvedServices) {
            this.transactionId = transactionId;
            this.queriedSubtypes.addAll(subTypes);
            this.taskArgs = taskArgs;
            this.queriedBaseType = queriedBaseType;
            this.resolvedServices.addAll(resolvedServices);
        }


        /**
         * Creates a QuerySentResult instance representing a failed query attempt.
         *
         * This factory method is used when the query packet could not be sent successfully.
         *
         * @param taskArgs The arguments of the task that attempted the query.
         * @return A QuerySentResult configured to indicate failure.
         */
        public static QuerySentResult createFailedQueryResult(
                @NonNull ScheduledQueryTaskArgs taskArgs) {
            return new QuerySentResult(INVALID_TRANSACTION_ID, new ArrayList<>(), taskArgs,
                    false /* queriedBaseType */, Collections.emptyList() /* resolvedServices */);
        }
    }

    // A FutureTask that enqueues a single query, and schedule a new FutureTask for the next task.
    private class QueryTask implements Runnable {
        private final ScheduledQueryTaskArgs taskArgs;
        private final List<MdnsResponse> servicesToResolve = new ArrayList<>();
        private final List<String> subtypes = new ArrayList<>();
        private final boolean sendDiscoveryQueries;
        private final List<MdnsResponse> existingServices = new ArrayList<>();
        private final boolean onlyUseIpv6OnIpv6OnlyNetworks;
        private final SocketKey socketKey;
        QueryTask(@NonNull ScheduledQueryTaskArgs taskArgs,
                @NonNull Collection<MdnsResponse> servicesToResolve,
                @NonNull Collection<String> subtypes, boolean sendDiscoveryQueries,
                @NonNull Collection<MdnsResponse> existingServices,
                boolean onlyUseIpv6OnIpv6OnlyNetworks,
                @NonNull SocketKey socketKey) {
            this.taskArgs = taskArgs;
            this.servicesToResolve.addAll(servicesToResolve);
            this.subtypes.addAll(subtypes);
            this.sendDiscoveryQueries = sendDiscoveryQueries;
            this.existingServices.addAll(existingServices);
            this.onlyUseIpv6OnIpv6OnlyNetworks = onlyUseIpv6OnIpv6OnlyNetworks;
            this.socketKey = socketKey;
        }

        @Override
        public void run() {
            QuerySentResult result;
            try {
                result =
                        new EnqueueMdnsQueryCallable(
                                socketClient,
                                serviceType,
                                subtypes,
                                taskArgs,
                                socketKey,
                                onlyUseIpv6OnIpv6OnlyNetworks,
                                sendDiscoveryQueries,
                                servicesToResolve,
                                clock,
                                sharedLog,
                                dependencies,
                                existingServices,
                                featureFlags.isQueryWithKnownAnswerEnabled())
                                .call();
            } catch (RuntimeException e) {
                sharedLog.e(String.format("Failed to run EnqueueMdnsQueryCallable for subtype: %s",
                        TextUtils.join(",", subtypes)), e);
                result = QuerySentResult.createFailedQueryResult(taskArgs);
            }
            dependencies.sendMessage(handler, handler.obtainMessage(EVENT_QUERY_RESULT, result));
        }
    }

    private long getMinRemainingTtl(long now) {
        long minRemainingTtl = Long.MAX_VALUE;
        for (MdnsResponse response : serviceCache.getCachedServices(
                cacheKey, false /* excludeExpiredServices */)) {
            if (!response.isComplete()) {
                continue;
            }
            long remainingTtl =
                    response.getServiceRecord().getRemainingTTL(now);
            // remainingTtl is <= 0 means the service expired.
            if (remainingTtl <= 0) {
                return 0;
            }
            if (remainingTtl < minRemainingTtl) {
                minRemainingTtl = remainingTtl;
            }
        }
        return minRemainingTtl == Long.MAX_VALUE ? 0 : minRemainingTtl;
    }

    private static long calculateTimeToNextTask(ScheduledQueryTaskArgs args,
            long now) {
        return Math.max(args.timeToRun - now, 0);
    }

    /**
     * Retrieves a list of {@link DiscoveryOffloadInfo} objects based on the currently registered
     * listeners and their associated search options.
     *
     * @return A Set of {@link DiscoveryOffloadInfo} objects, each representing a service to be
     *         offloaded for reply filtering, derived from the current listener configurations.
     */
    public Set<DiscoveryOffloadInfo> getAllDiscoveryOffloadInfos() {
        ensureRunningOnHandlerThread(handler);
        final Set<DiscoveryOffloadInfo> info = new ArraySet<>();
        info.addAll(offloadInfo.values());
        return info;
    }

    /**
     * Dump ServiceTypeClient state.
     */
    public void dump(PrintWriter pw) {
        ensureRunningOnHandlerThread(handler);
        pw.println("ServiceTypeClient: Type{" + serviceType + "} " + socketKey + " with "
                + listeners.size() + " listeners.");
    }
}