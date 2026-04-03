/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server

import android.net.ConnectivityManager.ACTION_CLEAR_DNS_CACHE
import android.net.ConnectivitySettingsManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_TRUSTED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.ResolverParamsParcel
import android.net.RouteInfo
import android.net.shared.PrivateDnsConfig
import android.os.Build
import com.android.net.module.util.ArrayTrackRecord
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.parcelingRoundTrip
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doAnswer

private const val DNS_ADDR = "8.8.8.8"
private const val IPV4_ADDR = "192.168.2.1"
private const val PRIVATE_DNS_HOSTNAME = "ignore.example.com"
private const val DEFAULT_ROUTE_PREFIX = "0.0.0.0/0"
private const val TLS_IP_ADDR = "1.1.1.1"

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.S)
class CSDnsManagerTest : CSTest() {

    private fun setupNetworkWithDnsServerReachableAfterRouteUpdate(
        capturedResolverConfigs: ArrayTrackRecord<ResolverParamsParcel>.ReadHead
    ): CSAgentWrapper {
        ConnectivitySettingsManager.setPrivateDnsMode(
            context,
            ConnectivitySettingsManager.PRIVATE_DNS_MODE_PROVIDER_HOSTNAME,
        )
        val nc =
            defaultNc().apply {
                addTransportType(TRANSPORT_WIFI)
                addCapability(NET_CAPABILITY_TRUSTED)
                addCapability(NET_CAPABILITY_INTERNET)
                addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            }
        // Initial LinkProperties: DNS server set but NO route to it.
        val lp =
            LinkProperties().apply {
                interfaceName = "wlan0"
                addDnsServer(InetAddresses.parseNumericAddress(DNS_ADDR))
            }
        val clearDnsCacheIntent = context.nextBroadcastIntent(ACTION_CLEAR_DNS_CACHE)
        val agent = Agent(nc = nc, lp = lp)
        agent.connect()
        // Wait for the first configuration push (from connect).
        with(assertNotNull(capturedResolverConfigs.poll(HANDLER_TIMEOUT_MS))) {
            assertEquals(agent.network.netId, netId)
            assertEquals(DNS_ADDR, servers[0])
            assertTrue(tlsServers.isEmpty())
        }
        with(assertNotNull(capturedResolverConfigs.poll(HANDLER_TIMEOUT_MS))) {
            assertEquals(agent.network.netId, netId)
            assertEquals(DNS_ADDR, servers[0])
            assertTrue(tlsServers.isEmpty())
        }
        // Verify ACTION_CLEAR_DNS_CACHE broadcast.
        assertNotNull(clearDnsCacheIntent.get(HANDLER_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        val tlsIp = InetAddresses.parseNumericAddress(TLS_IP_ADDR)
        val privateDnsConfig = PrivateDnsConfig(PRIVATE_DNS_HOSTNAME, arrayOf(tlsIp))
        val clearDnsCacheIntent2 = context.nextBroadcastIntent(ACTION_CLEAR_DNS_CACHE)
        agent.nmCallbacks.notifyPrivateDnsConfigResolved(privateDnsConfig.toParcel())

        // The private DNS has been set. As the TLS server is unreachable via the existing routes,
        // the TLS server is still empty.
        with(assertNotNull(capturedResolverConfigs.poll(HANDLER_TIMEOUT_MS))) {
            assertEquals(agent.network.netId, netId)
            assertEquals(DNS_ADDR, servers[0])
            assertTrue(tlsServers.isEmpty())
        }
        // Verify ACTION_CLEAR_DNS_CACHE broadcast.
        assertNotNull(clearDnsCacheIntent2.get(HANDLER_TIMEOUT_MS, TimeUnit.MILLISECONDS))

        // Update LinkProperties to add a route which makes the TLS server reachable.
        // The DNS servers remain exactly the same.
        val lpWithRoute =
            LinkProperties(lp).apply {
                addLinkAddress(LinkAddress(InetAddresses.parseNumericAddress(IPV4_ADDR), 24))
                addRoute(RouteInfo(IpPrefix(DEFAULT_ROUTE_PREFIX), null, "wlan0"))
            }
        val clearDnsCacheIntent3 = context.nextBroadcastIntent(ACTION_CLEAR_DNS_CACHE)
        agent.sendLinkProperties(lpWithRoute)

        // TODO: b/465367823 - Verify DNS resolver receives a new configuration push due to the
        // route change.
        // Before the fix of b/465367823, this DNS configuration update would be skipped and the DNS
        // cache is NOT cleared because the DNS servers didn't change.
        assertNull(capturedResolverConfigs.poll(SHORT_TIMEOUT_MS))
        clearDnsCacheIntent3.assertNotReceived(SHORT_TIMEOUT_MS, TimeUnit.MILLISECONDS)

        return agent
    }

    @ConnectivityModuleTest
    @Test
    fun testDnsReachabilityUpdate() {
        val capturedResolverConfigs = ArrayTrackRecord<ResolverParamsParcel>().newReadHead()
        doAnswer { inv ->
                capturedResolverConfigs.add(inv.getArgument<ResolverParamsParcel>(0).copy())
                null
            }
            .`when`(dnsResolver)
            .setResolverConfiguration(any())

        setupNetworkWithDnsServerReachableAfterRouteUpdate(capturedResolverConfigs)
    }

    @ConnectivityModuleTest
    @Test
    fun testPrivateDnsResolvedTriggersPushAfterRouteUpdate() {
        val capturedResolverConfigs = ArrayTrackRecord<ResolverParamsParcel>().newReadHead()
        doAnswer { inv ->
                capturedResolverConfigs.add(inv.getArgument<ResolverParamsParcel>(0).copy())
                null
            }
            .`when`(dnsResolver)
            .setResolverConfiguration(any())

        val agent = setupNetworkWithDnsServerReachableAfterRouteUpdate(capturedResolverConfigs)
        // notifyPrivateDnsConfigResolved
        val tlsIp = InetAddresses.parseNumericAddress(TLS_IP_ADDR)
        val privateDnsConfig = PrivateDnsConfig(PRIVATE_DNS_HOSTNAME, arrayOf(tlsIp))
        val clearDnsCacheIntent4 = context.nextBroadcastIntent(ACTION_CLEAR_DNS_CACHE)
        agent.nmCallbacks.notifyPrivateDnsConfigResolved(privateDnsConfig.toParcel())

        with(assertNotNull(capturedResolverConfigs.poll(HANDLER_TIMEOUT_MS))) {
            assertEquals(agent.network.netId, netId)
            assertEquals(DNS_ADDR, servers[0])
            assertEquals(TLS_IP_ADDR, tlsServers[0])
        }
        // Verify ACTION_CLEAR_DNS_CACHE broadcast.
        assertNotNull(clearDnsCacheIntent4.get(HANDLER_TIMEOUT_MS, TimeUnit.MILLISECONDS))
    }
}

private fun ResolverParamsParcel.copy(): ResolverParamsParcel = parcelingRoundTrip(this)
