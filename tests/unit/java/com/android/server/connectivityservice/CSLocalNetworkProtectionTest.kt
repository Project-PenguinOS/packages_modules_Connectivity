/*
 * Copyright (C) 2023 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
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

package com.android.server.connectivityservice

import android.content.pm.PackageManager
import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.ProxyInfo
import android.net.RouteInfo
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.os.Build
import android.os.Process
import android.util.Range
import androidx.test.filters.SmallTest
import com.android.server.CSTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.waitForIdle
import java.net.Inet6Address
import java.net.InetAddress
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.InOrder
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.reset
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.verification.VerificationMode

private const val TIMEOUT_MS = 500
private const val PREFIX_LENGTH_IPV4 = 32 + 96
private const val PREFIX_LENGTH_IPV6 = 32
private const val WIFI_IFNAME = "wlan0"
private const val WIFI_IFNAME_2 = "wlan1"
private const val WIFI_IFNAME_3 = "wlan2"
private const val CELLULAR_IFNAME = "rmnet0"
private const val VPN_IFNAME = "tun0"

private val wifiNc = NetworkCapabilities.Builder()
        .addTransportType(TRANSPORT_WIFI)
        .addCapability(NET_CAPABILITY_INTERNET)
        .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        .build()

private fun lp(iface: String, vararg linkAddresses: LinkAddress) = LinkProperties().apply {
    interfaceName = iface
    for (linkAddress in linkAddresses) {
        addLinkAddress(linkAddress)
    }
}

private fun lpWithRoutes(
    iface: String,
    routes: List<RouteInfo>,
    vararg linkAddresses: LinkAddress
) = LinkProperties().apply {
    interfaceName = iface
    for (linkAddress in linkAddresses) {
        addLinkAddress(linkAddress)
    }
    for (route in routes) {
        addRoute(route)
    }
}

private fun nr(transport: Int) = NetworkRequest.Builder()
        .clearCapabilities()
        .addTransportType(transport).apply {
            if (transport != TRANSPORT_VPN) {
                addCapability(NET_CAPABILITY_NOT_VPN)
            }
        }.build()

private fun address(addressStr: String) = InetAddresses.parseNumericAddress(addressStr)

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalNetworkProtectionTest : CSTest() {
    private val MULTICAST_AND_BROADCAST_PREFIXES = listOf(
        IpPrefix("224.0.0.0/4"), // Multicast
        IpPrefix("ff00::/8"), // Multicast
        IpPrefix("255.255.255.255/32") // Broadcast
    )
    private val LINK_LOCAL_PREFIX = IpPrefix("fe80::/64")

    private val LINK_LOCAL_ADDRESS = LinkAddress("fe80::1cf1:35ff:fe8c:db87/64")

    private val IPV6_HOME_PREFIX = IpPrefix("2601:19b:67f:e200::/56")
    private val IPV6_ONLINK_PREFIX = IpPrefix("2601:19b:67f:e220::/64")
    private val IPV6_GLOBAL_ADDRESS = LinkAddress("2601:19b:67f:e220:1cf1:35ff:fe8c:db87/64")
    private val ULA_ONLINK_PREFIX = IpPrefix("fde8:9964:b018:1::/64")
    private val ULA_ADDRESS = LinkAddress("fde8:9964:b018:1::cafe/64")
    private val ULA_EXTERNAL_AGGREGATE = IpPrefix("fd9c:139a:42eb::/48")
    private val ULA_STUB_PREFIX = IpPrefix("fd0a:32d6:6277::/48")
    private val IPV6_CELLULAR_PREFIX = IpPrefix("2001:268:9889:f121::/64")
    private val IPV6_CELLULAR_ADDRESS = LinkAddress("2001:268:9889:f121:0:33:539b:c01/64")

    private val IPV6_HOME_PREFIX_2 = IpPrefix("2001:db8:1:a00::/56")
    private val IPV6_DEFAULT_ROUTE_PREFIX = IpPrefix("::/0")

    private val IPV4_ADDRESS_1 = LinkAddress("10.0.0.184/24")
    private val IPV4_PREFIX_1 = IpPrefix("10.0.0.0/24")
    private val IPV4_ADDRESS_2 = LinkAddress("10.0.255.184/24")
    private val IPV4_PREFIX_2 = IpPrefix("10.0.255.0/24")
    private val IPV4_ADDRESS_3 = LinkAddress("10.255.255.184/24")
    private val IPV4_PREFIX_3 = IpPrefix("10.255.255.0/24")
    private val IPV4_COVERING_PREFIX = IpPrefix("10.255.0.0/16")
    private val IPV4_DEFAULT_ROUTE_PREFIX = IpPrefix("0.0.0.0/0")

    private val IPV4_ROUTER = address("10.0.0.1")
    private val IPV6_ROUTER = address("fe80::1")
    private val IPV6_STUB_ROUTER = address("fe80::cafe")

    private fun triePrefixLength(prefix: IpPrefix) = if (prefix.address is Inet6Address) {
        prefix.prefixLength + PREFIX_LENGTH_IPV6
    } else {
        prefix.prefixLength + PREFIX_LENGTH_IPV4
    }

    private fun <T> verifyMock(
        mock: T,
        inOrder: InOrder? = null,
        mode: VerificationMode = atLeastOnce()
    ): T {
        return inOrder?.verify(mock, mode) ?: verify(mock, mode)
    }

    private fun verifyAddedToLocal(
        prefix: IpPrefix,
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        verifyMock(bpfNetMaps, inOrder).addLocalNetAccess(triePrefixLength(prefix), iface,
            prefix.address, 0 /* protocol */, 0 /* remoteport */, false)
    }

    private fun verifyRemovedFromLocal(
        prefix: IpPrefix,
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        verifyMock(bpfNetMaps, inOrder).removeLocalNetAccess(triePrefixLength(prefix), iface,
            prefix.address, 0 /* protocol */, 0 /* remoteport */)
    }

    private fun verifyPopulationOfMulticastAndBroadcastAddress(
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        for (prefix in MULTICAST_AND_BROADCAST_PREFIXES) {
            verifyAddedToLocal(prefix, iface, inOrder)
        }
    }

    private fun verifyRemovalOfMulticastAndBroadcastAddress(
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        for (prefix in MULTICAST_AND_BROADCAST_PREFIXES) {
            verifyRemovedFromLocal(prefix, iface, inOrder)
        }
    }

    private fun verifyNeverAddedToLocal(
        prefix: IpPrefix,
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        verifyMock(bpfNetMaps, inOrder, never()).addLocalNetAccess(triePrefixLength(prefix), iface,
            prefix.address, 0 /* protocol */, 0 /* remoteport */, false)
    }

    private fun verifyNeverRemovedFromLocal(
        prefix: IpPrefix,
        iface: String = WIFI_IFNAME,
        inOrder: InOrder? = null
    ) {
        verifyMock(bpfNetMaps, inOrder, never()).removeLocalNetAccess(triePrefixLength(prefix),
            iface, prefix.address, 0 /* protocol */, 0 /* remoteport */)
    }

    private fun verifyNothingRemovedFromLocal() {
        verify(bpfNetMaps, never()).removeLocalNetAccess(anyInt(), anyString(),
            any(InetAddress::class.java), anyInt(), anyInt())
    }

    @Test
    fun testNetworkWithProxy_AddressRemovedFromBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        // Verifying IPv4 matching prefix should be populated in local_net_access map
        val inOrder = inOrder(bpfNetMaps)

        // Connecting to network with IPv4 local address in LinkProperties
        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME, inOrder)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)

        // Add another network without a proxy to verify that proxy changes on one network do not
        // affect LNP enforcement for other networks
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_2)
        val wifiAgent2 = Agent(nc = wifiNc, lp = wifiLp2)
        wifiAgent2.connect()

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2, inOrder)
        verifyAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)

        // Adding the proxy, the local network protection should be disabled.
        wifiLp.httpProxy = ProxyInfo.buildDirectProxy("10.0.0.185", 8080)
        wifiAgent.sendLinkProperties(wifiLp)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should be removed in local_net_access map
        verifyRemovalOfMulticastAndBroadcastAddress(WIFI_IFNAME, inOrder)
        // Verifying IPv4 matching prefix should be removed from local_net_access map
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)

        // Verify that other networks are not affected
        verifyNeverRemovedFromLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)

        // Removing the proxy, the local network protection should be enabled.
        wifiLp.httpProxy = null
        wifiAgent.sendLinkProperties(wifiLp)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME, inOrder)
        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)

        // Verify that LNP bfp rules were added only once for the second wifi network.
        verifyNeverAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)
        verifyNeverRemovedFromLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)
    }

    @Test
    fun testNetworkWithGlobalProxy_AddressRemovedFromBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // A global proxy configuration affects all networks
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_2)
        val wifiAgent2 = Agent(nc = wifiNc, lp = wifiLp2)
        wifiAgent2.connect()

        // Initial state: Verify LNP rules are added for both networks
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        verifyAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2)
        clearInvocations(bpfNetMaps) // Clear invocations after setup

        // Adding the global proxy, the local network protection should be disabled for all networks.
        service.setGlobalProxy(ProxyInfo.buildDirectProxy("10.0.0.185", 8080))
        csHandler.waitForIdle(TIMEOUT_MS)

        verifyRemovalOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        verifyRemovalOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        verifyRemovedFromLocal(IPV4_PREFIX_2, WIFI_IFNAME_2)
        clearInvocations(bpfNetMaps)

        // Removing the global proxy, the local network protection should be enabled.
        service.setGlobalProxy(ProxyInfo.buildDirectProxy("", 0))
        csHandler.waitForIdle(TIMEOUT_MS)

        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        verifyAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2)
        verifyNoMoreInteractions(bpfNetMaps)
    }

    @Test
    fun testLinkPropertiesChangeWithProxy_LnpRulesSynchronizedCorrectly() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val initialLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = initialLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        val inOrder = inOrder(bpfNetMaps)
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME, inOrder)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)

        // Configure per-network proxy, verify LNP rules removed
        val lpWithProxy = LinkProperties(initialLp)
        lpWithProxy.httpProxy = ProxyInfo.buildDirectProxy("10.0.0.185", 8080)
        wifiAgent.sendLinkProperties(lpWithProxy)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        verifyRemovalOfMulticastAndBroadcastAddress(WIFI_IFNAME, inOrder)
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)

        // Configure a different proxy, verify that rules are not added or removed
        val updatedLp = lp(WIFI_IFNAME_2, IPV4_ADDRESS_2)
        updatedLp.httpProxy = ProxyInfo.buildDirectProxy("10.0.0.185", 8081)
        wifiAgent.sendLinkProperties(updatedLp)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verify that no LNP rules are added or removed
        verifyNeverAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)
        verifyNeverRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME, inOrder)
        verifyNeverAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)
        verifyNeverRemovedFromLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)

        // Unset the proxy on the new network
        val lpWithoutProxy = LinkProperties(updatedLp)
        lpWithoutProxy.httpProxy = null
        wifiAgent.sendLinkProperties(lpWithoutProxy)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verify LNP rules are populated using the updated LinkProperties
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2, inOrder)
        verifyAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2, inOrder)
    }

    @Test
    fun testGlobalAndPerNetworkProxyInteraction_LnpRulesClearedAndRestored() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        // Register network and verify LNP rules are populated.
        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        clearInvocations(bpfNetMaps)

        // Configure a per-network proxy and verify LNP rules are removed.
        val lpWithPerNetworkProxy = LinkProperties(wifiLp)
        lpWithPerNetworkProxy.httpProxy = ProxyInfo.buildDirectProxy("10.0.0.185", 8080)
        wifiAgent.sendLinkProperties(lpWithPerNetworkProxy)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        verifyRemovalOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        clearInvocations(bpfNetMaps)

        // Set a global proxy and verify that LNP rules remain cleared.
        service.setGlobalProxy(ProxyInfo.buildDirectProxy("global.proxy", 8000))
        csHandler.waitForIdle(TIMEOUT_MS)

        // Verify that no LNP rules are added or removed
        verifyNoMoreInteractions(bpfNetMaps)

        // Remove the per-network proxy.
        val lpWithoutPerNetworkProxy = LinkProperties(wifiLp)
        wifiAgent.sendLinkProperties(lpWithoutPerNetworkProxy)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verify LNP rules are not restored because the global proxy is still active.
        verifyNoMoreInteractions(bpfNetMaps)

        // Unset the global proxy.
        service.setGlobalProxy(ProxyInfo.buildDirectProxy("", 0))
        csHandler.waitForIdle(TIMEOUT_MS)

        // Verify that the LNP rules are correctly restored.
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME)
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME)
        verifyNoMoreInteractions(bpfNetMaps)
    }

    @Test
    fun testNetworkWithLinkLocalAddress_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)
    }

    @Test
    fun testNetworkWithIPv4Address_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1)
    }

    @Test
    fun testNetworkWithIPv4LocalAddressAndRoute_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        // This can't really happen, since there is no way to create an IPv4 route pointed directly
        // at an interface without a nexthop.  Still, arguably it should work.
        val routes = listOf(RouteInfo(
            IPV4_COVERING_PREFIX,
            null,
            WIFI_IFNAME
        ))
        val wifiLp = lpWithRoutes(
            WIFI_IFNAME,
            routes,
            IPV4_ADDRESS_3
        )
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // BUG: the directly-connected route to IPV4_COVERING_PREFIX should be marked local.
        verifyAddedToLocal(IPV4_PREFIX_3)
    }

    @Test
    fun testNetworkWithIPv4DefaultRoute_DefaultRouteNotAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val routes = listOf(RouteInfo(
                IPV4_DEFAULT_ROUTE_PREFIX,
                IPV4_ADDRESS_3.address,
                WIFI_IFNAME
        ))
        val wifiLp = lpWithRoutes(
                WIFI_IFNAME,
                routes,
                IPV4_ADDRESS_3
        )
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying default route(0.0.0.0/0) should not be populated in local_net_access map
        verifyNeverAddedToLocal(IPV4_DEFAULT_ROUTE_PREFIX)
    }

    @Test
    fun testChangeLinkPropertiesWithDifferentLinkAddresses_AddressReplacedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Updating Link Property from IPv6 in Link Address to IPv4 in Link Address
        val wifiLp2 = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1)
        // Verifying IPv6 address should be removed from local_net_access map
        verifyRemovedFromLocal(LINK_LOCAL_PREFIX)
    }

    @Test
    fun testAddingThenRemovingStackedLinkProperties_AddressAddedThenRemovedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        // Adding stacked link
        wifiLp.addStackedLink(wifiLp2)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 prefix should be populated as part of stacked link in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
        // As both addresses are in stacked links, so no address should be removed from the map.
        verifyNothingRemovedFromLocal()

        // replacing link properties without stacked links
        val wifiLp_3 = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        wifiAgent.sendLinkProperties(wifiLp_3)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // As both stacked links is removed, the IPv4 prefix should be removed from local_net_access map.
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
    }

    @Test
    fun testChangeStackedLinkProperties_AddressReplacedBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        // populating stacked link
        wifiLp.addStackedLink(wifiLp2)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix should be populated as part of stacked link
        // in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
        // As both addresses are in stacked links, so no address should be removed from the map.
        verifyNothingRemovedFromLocal()

        // replacing link properties multiple stacked links
        val wifiLp_3 = lp(WIFI_IFNAME, IPV6_GLOBAL_ADDRESS)
        val wifiLp_4 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_2)
        val wifiLp_5 = lp(WIFI_IFNAME_3, LINK_LOCAL_ADDRESS)
        wifiLp_3.addStackedLink(wifiLp_4)
        wifiLp_3.addStackedLink(wifiLp_5)
        wifiAgent.sendLinkProperties(wifiLp_3)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_3)
        // Verifying new base IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(IPV6_ONLINK_PREFIX, WIFI_IFNAME)
        verifyRemovedFromLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
        verifyAddedToLocal(IPV4_PREFIX_2, WIFI_IFNAME_2)
        // Verifying newly stacked IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX, WIFI_IFNAME_3)
        // Verifying old base IPv6 address should be removed from local_net_access map
        verifyRemovedFromLocal(LINK_LOCAL_PREFIX, WIFI_IFNAME)
    }

    @Test
    fun testChangeLinkPropertiesWithLinkAddressesInSameRange_AddressIntactInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1)

        // Updating Link Property from one IPv4 to another IPv4.
        val wifiLp2 = lp(WIFI_IFNAME, IPV4_ADDRESS_2)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Check that the old prefix is removed and the new one is added.
        verifyRemovedFromLocal(IPV4_PREFIX_1)
        verifyAddedToLocal(IPV4_PREFIX_2)
    }

    @Test
    fun testChangeLinkPropertiesWithDifferentInterface_AddressReplacedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Updating Link Property by changing interface name which has IPv4 instead of IPv6
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should be populated in local_net_access map for
        // new interface
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verifyRemovedFromLocal(LINK_LOCAL_PREFIX)
    }

    @Test
    fun testAddingAnotherNetwork_AllAddressesAddedInBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Adding another network with LinkProperty having IPv4 in LinkAddress
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        val wifiAgent2 = Agent(nc = wifiNc, lp = wifiLp2)
        wifiAgent2.connect()

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix should be populated in local_net_access map
        verifyAddedToLocal(IPV4_PREFIX_1, WIFI_IFNAME_2)
        // Verifying nothing should be removed from local_net_access map
        verifyNothingRemovedFromLocal()
    }

    @Test
    fun testDestroyingNetwork_AddressesRemovedFromBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)

        // Unregistering the network
        wifiAgent.unregisterAfterReplacement(TIMEOUT_MS)
        cb.expect<Lost>(wifiAgent.network)

        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verifyRemovedFromLocal(LINK_LOCAL_PREFIX)
    }

    @Test
    fun testNetworkWithIPv6DefaultRoutes_DefaultRouteNotAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val routes = listOf(RouteInfo(
            IPV6_DEFAULT_ROUTE_PREFIX,
            IPV6_HOME_PREFIX_2.getAddress(),
            WIFI_IFNAME
        ))

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lpWithRoutes(
            WIFI_IFNAME,
            routes,
            LINK_LOCAL_ADDRESS
        )
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv6 default route should not be populated in local_net_access map
        verifyNeverAddedToLocal(IPV6_DEFAULT_ROUTE_PREFIX)
    }

    @Test
    fun testNetworkWithIPv6LocalAddressAndRouteCoveringLinkAddress_RouteAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val routes = listOf(RouteInfo(
            LINK_LOCAL_PREFIX,
            null,
            WIFI_IFNAME
        ))

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lpWithRoutes(
            WIFI_IFNAME,
            routes,
            LINK_LOCAL_ADDRESS
        )
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv6 covering route should be populated in local_net_access map
        verifyAddedToLocal(LINK_LOCAL_PREFIX)
    }

    @Test
    fun testNetworkWithIPv6LocalAddressAndRedundantRoute_UniqueRoutesAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val routes = listOf(
            RouteInfo(
            IPV6_HOME_PREFIX_2,
            LINK_LOCAL_ADDRESS.address,
            WIFI_IFNAME
            ),
            RouteInfo(
                IPV6_HOME_PREFIX,
                LINK_LOCAL_ADDRESS.address,
                WIFI_IFNAME
            ),
            RouteInfo(
                IPV6_ONLINK_PREFIX,
                null,
                WIFI_IFNAME
            ),
            RouteInfo(
            LINK_LOCAL_PREFIX,
            null,
            WIFI_IFNAME
        )
        )

        // Connecting to network with IPv6 local address in LinkProperties
        val wifiLp = lpWithRoutes(
            WIFI_IFNAME,
            routes,
            LINK_LOCAL_ADDRESS,
            IPV6_GLOBAL_ADDRESS
        )
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv6 unique routes should be populated in local_net_access map
        verifyAddedToLocal(IPV6_HOME_PREFIX)
        verifyNeverAddedToLocal(IPV6_ONLINK_PREFIX) // covered by IPV6_HOME_PREFIX
        verifyAddedToLocal(LINK_LOCAL_PREFIX)
    }

    fun makeLp(iface: String, addresses: Set<LinkAddress>, routes: Set<RouteInfo>): LinkProperties {
        return LinkProperties().also {
            it.interfaceName = iface
            for (address in addresses) it.addLinkAddress(address)
            for (route in routes) it.addRoute(route)
        }
    }

    fun stackClatLp(lp: LinkProperties): LinkProperties {
        val clatIface = "v4-" + lp.interfaceName
        lp.addStackedLink(LinkProperties().also {
            it.interfaceName = clatIface
            it.addLinkAddress(LinkAddress("192.0.0.4/32"))
            it.addRoute(RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, null, clatIface))
        })
        return lp
    }

    fun doTestExpectedLocalPrefixes(lp: LinkProperties, vararg localPrefixes: IpPrefix) {
        csHandler.waitForIdle(TIMEOUT_MS)
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)
        reset(bpfNetMaps)

        val wifiAgent = Agent(nc = wifiNc, lp = lp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        verifyPopulationOfMulticastAndBroadcastAddress(lp.interfaceName!!)
        for (prefix in localPrefixes) {
            verifyAddedToLocal(prefix, lp.interfaceName!!)
        }
        for (stacked in lp.stackedLinks) {
            verifyPopulationOfMulticastAndBroadcastAddress(stacked.interfaceName!!)
        }
        verify(bpfNetMaps, atLeastOnce()).getNetPermForUid(anyInt())
        verifyNoMoreInteractions(bpfNetMaps)

        wifiAgent.disconnect()
        cb.expect<Lost>()
        csHandler.waitForIdle(TIMEOUT_MS)

        verifyRemovalOfMulticastAndBroadcastAddress(lp.interfaceName!!)
        for (prefix in localPrefixes) {
            verifyRemovedFromLocal(prefix, lp.interfaceName!!)
        }
        for (stacked in lp.stackedLinks) {
            verifyRemovalOfMulticastAndBroadcastAddress(stacked.interfaceName!!)
        }
    }
    @Test
    fun testDualStackCellular() {
        val lp = lpWithRoutes(
            CELLULAR_IFNAME,
            listOf(
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, null, CELLULAR_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, null, CELLULAR_IFNAME)
            ),
            IPV4_ADDRESS_1, IPV6_GLOBAL_ADDRESS
        )
        doTestExpectedLocalPrefixes(lp, IPV4_PREFIX_1, IPV6_ONLINK_PREFIX)
    }

    @Test
    fun testIpv6OnlyCellular() {
        val lp = stackClatLp(lpWithRoutes(
            CELLULAR_IFNAME,
            listOf(
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, null, CELLULAR_IFNAME)
            ),
            IPV6_CELLULAR_ADDRESS
        ))
        doTestExpectedLocalPrefixes(lp, IPV6_CELLULAR_PREFIX)
    }

    @Test
    fun testDualStackWifi() {
        val lp = lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(LINK_LOCAL_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV4_PREFIX_1, null, WIFI_IFNAME),
                RouteInfo(IPV6_ONLINK_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, IPV4_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, IPV6_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV6_GLOBAL_ADDRESS, IPV4_ADDRESS_1
        )
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV4_PREFIX_1, IPV6_ONLINK_PREFIX)
    }

    @Test
    fun testIpv6OnlyWifi() {
        val lp = stackClatLp(lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(LINK_LOCAL_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV6_ONLINK_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, IPV6_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV6_GLOBAL_ADDRESS
        ))
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV6_ONLINK_PREFIX)
    }

    @Test
    fun testDualStackWifiWithRioAndOfflinkUla() {
        val lp = lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(LINK_LOCAL_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV4_PREFIX_1, null, WIFI_IFNAME),
                // On-link /64 prefix is covered by home /56 prefix.
                RouteInfo(IPV6_HOME_PREFIX, IPV6_ROUTER, WIFI_IFNAME),
                RouteInfo(ULA_EXTERNAL_AGGREGATE, IPV6_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, IPV4_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, IPV6_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV6_GLOBAL_ADDRESS, IPV4_ADDRESS_1
        )
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV4_PREFIX_1, IPV6_HOME_PREFIX)
    }

    @Test
    fun testDualStackWifiWithImplicitRoutes() {
        val lp = lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, IPV4_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, IPV6_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV6_GLOBAL_ADDRESS, ULA_ADDRESS, IPV4_ADDRESS_1
        )
        // On-link routes marked as local even if they are not explicitly present in LinkProperties.
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV6_ONLINK_PREFIX, ULA_ONLINK_PREFIX,
            IPV4_PREFIX_1)
    }

    @Test
    fun testIpv4WifiWithStubNetwork() {
        val lp = lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(LINK_LOCAL_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV4_PREFIX_1, null, WIFI_IFNAME),
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, IPV4_ROUTER, WIFI_IFNAME),
                RouteInfo(ULA_STUB_PREFIX, IPV6_STUB_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV4_ADDRESS_1, ULA_ADDRESS
        )
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV4_PREFIX_1, ULA_ONLINK_PREFIX,
            ULA_STUB_PREFIX)
    }

    @Test
    fun testDualStackWifiWithUlaAndStubNetwork() {
        val lp = lpWithRoutes(
            WIFI_IFNAME,
            listOf(
                RouteInfo(LINK_LOCAL_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV6_ONLINK_PREFIX, null, WIFI_IFNAME),
                RouteInfo(IPV6_HOME_PREFIX, IPV6_ROUTER, WIFI_IFNAME),
                RouteInfo(ULA_ONLINK_PREFIX, null, WIFI_IFNAME),
                RouteInfo(ULA_STUB_PREFIX, IPV6_STUB_ROUTER, WIFI_IFNAME),
                RouteInfo(ULA_EXTERNAL_AGGREGATE, IPV6_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV6_DEFAULT_ROUTE_PREFIX, IPV6_ROUTER, WIFI_IFNAME),
                RouteInfo(IPV4_PREFIX_1, null, WIFI_IFNAME),
                RouteInfo(IPV4_DEFAULT_ROUTE_PREFIX, IPV4_ROUTER, WIFI_IFNAME)
            ),
            LINK_LOCAL_ADDRESS, IPV6_GLOBAL_ADDRESS, ULA_ADDRESS, IPV4_ADDRESS_1
        )
        doTestExpectedLocalPrefixes(lp, LINK_LOCAL_PREFIX, IPV6_HOME_PREFIX, ULA_ONLINK_PREFIX,
            ULA_STUB_PREFIX, IPV4_PREFIX_1)
    }

    fun doTestRestrictedNetwork(isRestricted: Boolean, expectedLnpApplied: Boolean) {
        val nr = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_WIFI)
                .build()
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiNcBuilder = NetworkCapabilities.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .addCapability(NET_CAPABILITY_INTERNET)
                .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
        if (isRestricted) {
            wifiNcBuilder.removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
        }
        val wifiNc = wifiNcBuilder.build()

        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        if (expectedLnpApplied) {
            verifyAddedToLocal(IPV4_PREFIX_1)
            verifyPopulationOfMulticastAndBroadcastAddress()
        } else {
            verifyNeverAddedToLocal(IPV4_PREFIX_1)
        }
    }

    @Test
    @CSTest.SystemFeature(name = PackageManager.FEATURE_AUTOMOTIVE, supported = true)
    fun testRestrictedNetworkOnAutomotive_LnpNotApplied() {
        doTestRestrictedNetwork(isRestricted = true, expectedLnpApplied = false)
    }

    @Test
    @CSTest.SystemFeature(name = PackageManager.FEATURE_AUTOMOTIVE, supported = true)
    fun testUnrestrictedNetworkOnAutomotive_LnpApplied() {
        doTestRestrictedNetwork(isRestricted = false, expectedLnpApplied = true)
    }

    @Test
    @CSTest.SystemFeature(name = PackageManager.FEATURE_AUTOMOTIVE, supported = false)
    fun testRestrictedNetworkOnNonAutomotive_LnpApplied() {
        doTestRestrictedNetwork(isRestricted = true, expectedLnpApplied = true)
    }

    @Test
    fun testSplitTunnelVpn() {
        val nr = nr(TRANSPORT_VPN)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val lp = lpWithRoutes(
            iface = VPN_IFNAME,
            routes = listOf(
                RouteInfo(IpPrefix("2001:db8::/32"), null, VPN_IFNAME),
                RouteInfo(IpPrefix("10.0.0.0/8"), null, VPN_IFNAME),
            ),
            LinkAddress("2001:db8:a:b::d00d/64"), LinkAddress("10.1.2.3/24")
        )
        val nc = NetworkCapabilities.Builder()
            .addTransportType(TRANSPORT_VPN)
            .addCapability(NET_CAPABILITY_INTERNET)
            .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            .removeCapability(NET_CAPABILITY_NOT_VPN)
            .setUids(setOf(Range<Int>(Process.myUid(), Process.myUid())))
            .setTransportInfo(VpnTransportInfo(VpnManager.TYPE_VPN_SERVICE, "mySessionId"))
            .build()

        val vpnAgent = Agent(nc = nc, lp = lp)
        vpnAgent.connect()
        cb.expectAvailableCallbacks(vpnAgent.network, validated = false)

        verifyAddedToLocal(IpPrefix("2001:db8:a:b::/64"), VPN_IFNAME)
        verifyNeverAddedToLocal(IpPrefix("2001:db8::/32"), VPN_IFNAME)
        verifyAddedToLocal(IpPrefix("10.1.2.0/24"), VPN_IFNAME)
        verifyNeverAddedToLocal(IpPrefix("10.0.0.0/8"), VPN_IFNAME)
    }
}
