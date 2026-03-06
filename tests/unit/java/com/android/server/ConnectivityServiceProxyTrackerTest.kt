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

package com.android.server

import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.ProxyInfo
import android.net.Uri
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val TIMEOUT_MS = 250L
private fun lp(proxy: ProxyInfo) = LinkProperties().apply { httpProxy = proxy }

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRunner.MonitorThreadLeak
class ConnectivityServiceProxyTrackerTest : CSTest() {

    private val proxyInfoCaptor = ArgumentCaptor.forClass(ProxyInfo::class.java)

    private val directProxy1 = ProxyInfo.buildDirectProxy("192.0.2.1", 8080)
    private val directProxy2 = ProxyInfo.buildDirectProxy("198.51.100.1", 9090)
    private val pacProxy = ProxyInfo.buildPacProxy(Uri.parse("http://pac.example.com"))

    // TODO(b/482006911): Re-enable this test after investigating issues with
    // triggering the ContentObserver for Settings.Global.HTTP_PROXY changes
    // in the HSUM test environment (when running as user 10).
    // @Test
    // fun testEventApplyGlobalHttpProxy_LoadsDeprecatedGlobalProxy() {
    //    Settings.Global.putString(context.contentResolver, HTTP_PROXY, "");
    //    verify(proxyTracker, timeout(TIMEOUT_MS)).loadDeprecatedGlobalHttpProxy()
    // }

    @Test
    fun testSystemReadyInternal_LoadsGlobalProxy() {
        // Triggered on CSTest.setUp() -> service.systemReadyInternal()
        verify(proxyTracker).loadGlobalProxy()
    }

    @Test
    fun testSetGlobalProxy_DelegatesToProxyTracker() {
        service.setGlobalProxy(directProxy1)
        verify(proxyTracker).setGlobalProxy(directProxy1)
    }

    @Test
    fun testGetGlobalProxy_DelegatesToProxyTracker() {
        doReturn(directProxy1).`when`(proxyTracker).globalProxy
        val result = service.globalProxy
        assertEquals(directProxy1, result)
        verify(proxyTracker).globalProxy
    }

    @Test
    fun testHandlePacProxyServiceStarted() {
        // Create a system-default network.
        val wifiCaps =
            NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val defaultNai = Agent(nc = wifiCaps, lp = lp(pacProxy))
        defaultNai.connect()

        verify(proxyTracker, timeout(TIMEOUT_MS)).updateDefaultNetworkState(
            defaultNai.network,
            pacProxy
        )
        verify(proxyTracker).updateNetworkProxy(defaultNai.network, pacProxy, null)

        val pacProxyWithPort =
            ProxyInfo.buildPacProxy(Uri.parse("http://pac.test.com/proxy.pac"), 8080)

        service.simulateUpdateProxyInfo(defaultNai.network, pacProxyWithPort)

        // When the PAC setup is finished, the ConnectivityService receives a new proxy
        // configuration  that has the port of the local proxy server set.
        // Verify that the ConnectivityService re-sets the default proxy to the resolved proxy
        // value.
        verify(proxyTracker, timeout(TIMEOUT_MS)).updateDefaultNetworkState(
            defaultNai.network,
            pacProxyWithPort
        )

        // Verify that the ConnectivityService requests the patching of the LinkProperties for the
        // default network.
        val lpCaptor = ArgumentCaptor.forClass(LinkProperties::class.java)
        verify(proxyTracker).updateDefaultNetworkProxyPortForPAC(lpCaptor.capture(), any())
        val capturedLp = lpCaptor.value
        assertEquals(pacProxy, capturedLp.httpProxy)
    }

    @Test
    fun testUpdateLinkProperties_ProxyChanged_SendsBroadcast() {
        val nai = Agent(lp = lp(directProxy1))
        nai.connect()

        verify(proxyTracker, timeout(TIMEOUT_MS).times(1)).updateNetworkProxy(
            nai.network,
            directProxy1,
            null
        )

        val newLp = lp(directProxy2)
        nai.sendLinkProperties(newLp)

        // Verify that the proxy was updated.
        verify(proxyTracker, timeout(TIMEOUT_MS).times(1)).updateNetworkProxy(
            nai.network,
            directProxy2,
            directProxy1
        )
    }

    @Test
    fun testUpdateLinkProperties_ProxyUnchanged_NoBroadcast() {
        val nai = Agent(lp = lp(directProxy1))
        nai.connect()

        verify(proxyTracker, times(1)).updateNetworkProxy(
            nai.network,
            directProxy1,
            null
        )

        val newLp = lp(directProxy1)
        nai.sendLinkProperties(newLp)

        // Verify that the update call was still only called once, as the proxy did not change.
        verify(proxyTracker, times(1)).updateNetworkProxy(any(), any(), eq(null))
    }

    @Test
    fun testDefaultNetworkSwitch_SetsDefaultProxy() {
        val cellCaps =
            NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_CELLULAR)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .build()

        val wifiCaps =
            NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val defaultNai = Agent(nc = cellCaps, lp = lp(directProxy1))
        defaultNai.connect()

        verify(proxyTracker, timeout(TIMEOUT_MS)).updateDefaultNetworkState(
            eq(defaultNai.network),
            proxyInfoCaptor.capture()
        )

        assertEquals(directProxy1, proxyInfoCaptor.value)

        // Switch the default network and verify the default proxy is correctly set.
        val newDefaultNai = Agent(nc = wifiCaps, lp = lp(directProxy2))
        newDefaultNai.connect()

        verify(proxyTracker, times(1)).updateDefaultNetworkState(
            eq(newDefaultNai.network),
            proxyInfoCaptor.capture()
        )

        assertEquals(directProxy2, proxyInfoCaptor.value)
    }

    @Test
    fun testVpnDisconnect_SendsProxyBroadcast() {
        val baseNet = Agent(transports = intArrayOf(TRANSPORT_WIFI))
        baseNet.connect()

        val vpnNc =
            NetworkCapabilities.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .addCapability(NET_CAPABILITY_INTERNET)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .build()

        val vpnNai = Agent(nc = vpnNc, lp = lp(directProxy1))
        vpnNai.connect(expectAvailable = false)

        // Connecting will not trigger an update since there are no apps using the fake VPN
        // connection.
        verify(proxyTracker, never()).updateNetworkProxy(any(), any(), any())

        vpnNai.disconnect(expectAvailable = false)
        // Disconnecting from the VPN triggers an update, as some apps may need to update their
        // proxy data.
        // TODO(b/122649188): send the broadcast only to VPN users.
        verify(proxyTracker, timeout(TIMEOUT_MS)).updateNetworkProxy(
            vpnNai.network,
            null,
            directProxy1
        )
    }

    @Test
    fun testGetProxyForNetwork_NoGlobal_NetworkBound() {
        val nai = Agent(lp = lp(directProxy1))
        nai.connect()
        doReturn(null).`when`(proxyTracker).globalProxy

        val result = service.getProxyForNetwork(nai.network)
        assertNotNull(result)
        assertEquals(directProxy1.host, result.host)
        assertEquals(directProxy1.port, result.port)
    }

    @Test
    fun testGetProxyForNetwork_NoGlobal_NullNetwork_UsesDefault() {
        val wifiCaps =
            NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
                .addCapability(NET_CAPABILITY_INTERNET)
                .build()
        val newDefaultNai = Agent(nc = wifiCaps, lp = lp(directProxy1))
        newDefaultNai.connect()

        doReturn(null).`when`(proxyTracker).globalProxy

        val result = service.getProxyForNetwork(null)
        assertNotNull(result)
        assertEquals(directProxy1.host, result.host)
        assertEquals(directProxy1.port, result.port)
    }

    @Test
    fun testGetProxyForNetwork_GlobalProxySet_ReturnsGlobal() {
        doReturn(directProxy1).`when`(proxyTracker).globalProxy

        val nai = Agent(lp = lp(directProxy2))
        nai.connect()

        val result = service.getProxyForNetwork(nai.network)

        // Global proxy has priority, regardless of the bound network.
        assertEquals(directProxy1, result)
    }
}
