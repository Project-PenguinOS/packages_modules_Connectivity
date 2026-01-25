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

import android.net.InetAddresses
import android.net.IpPrefix
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.RouteInfo
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.server.CSTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.TestableNetworkCallback.Event.Lost
import java.net.InetAddress
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

private const val LONG_TIMEOUT_MS = 5_000
private const val PREFIX_LENGTH_IPV4 = 32 + 96
private const val PREFIX_LENGTH_IPV6 = 32
private const val WIFI_IFNAME = "wlan0"
private const val WIFI_IFNAME_2 = "wlan1"
private const val WIFI_IFNAME_3 = "wlan2"

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

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalNetworkProtectionTest : CSTest() {
    private val LINK_LOCAL_PREFIX = IpPrefix("fe80::/64")

    private val LINK_LOCAL_ADDRESS = LinkAddress("fe80::1cf1:35ff:fe8c:db87/64")

    private val IPV6_HOME_PREFIX = IpPrefix("2601:19b:67f:e200::/56")
    private val IPV6_ONLINK_PREFIX = IpPrefix("2601:19b:67f:e220::/64")
    private val IPV6_GLOBAL_ADDRESS = LinkAddress("2601:19b:67f:e220:1cf1:35ff:fe8c:db87/64")

    private val IPV6_HOME_PREFIX_2 = IpPrefix("2001:db8:1:a00::/56")
    private val IPV6_DEFAULT_ROUTE_PREFIX = IpPrefix("::/0")

    private val IPV4_PREFIX_1 = IpPrefix("10.0.0.0/24")
    private val IPV4_ADDRESS_1 = LinkAddress("10.0.0.184/24")
    private val IPV4_ADDRESS_2 = LinkAddress("10.0.255.184/24")
    private val IPV4_ADDRESS_3 = LinkAddress("10.255.255.184/24")
    private val IPV4_COVERING_PREFIX = IpPrefix("10.255.0.0/16")
    private val IPV4_DEFAULT_ROUTE_PREFIX = IpPrefix("0.0.0.0/0")

    @Test
    fun testNetworkWithIPv6LocalAddress_AddressAddedToBpfMap() {
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    @Test
    fun testNetworkWithIPv4LocalAddress_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val wifiLp = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        val wifiAgent = Agent(nc = wifiNc, lp = wifiLp)
        wifiAgent.connect()
        cb.expectAvailableCallbacks(wifiAgent.network, validated = false)

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress()

        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    @Test
    fun testNetworkWithIPv4LocalAddressAndRoute_AddressAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

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

        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
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
        verify(bpfNetMaps, never()).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4),
                eq(WIFI_IFNAME),
                eq(IPV4_DEFAULT_ROUTE_PREFIX.address),
                eq(0),
                eq(0),
                eq(false)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property from IPv6 in Link Address to IPv4 in Link Address
        val wifiLp2 = lp(WIFI_IFNAME, IPV4_ADDRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LINK_LOCAL_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // As both addresses are in stacked links, so no address should be removed from the map.
        verify(
            bpfNetMaps,
            never()
        ).removeLocalNetAccess(
            anyInt(),
            anyString(),
            any(InetAddress::class.java),
            anyInt(),
            anyInt()
        )

        // replacing link properties without stacked links
        val wifiLp_3 = lp(WIFI_IFNAME, LINK_LOCAL_ADDRESS)
        wifiAgent.sendLinkProperties(wifiLp_3)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // As both stacked links is removed, 10.0.0.0/8 should be removed from local_net_access map.
        verify(bpfNetMaps).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LINK_LOCAL_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )

        // Multicast and Broadcast address should always be populated on stacked link
        // in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // As both addresses are in stacked links, so no address should be removed from the map.
        verify(
            bpfNetMaps,
            never()
        ).removeLocalNetAccess(
            anyInt(),
            anyString(),
            any(InetAddress::class.java),
            anyInt(),
            anyInt()
        )

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
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + IPV6_ONLINK_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(IPV6_ONLINK_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated as part of stacked link
        // in local_net_access map
        verify(bpfNetMaps, times(2)).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying newly stacked IPv6 address should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME_3),
                eq(LINK_LOCAL_PREFIX.getAddress()),
                eq(0),
                eq(0),
                eq(false)
        )
        // Verifying old base IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
                eq(WIFI_IFNAME),
                eq(LINK_LOCAL_PREFIX.getAddress()),
                eq(0),
                eq(0)
        )
        // As both stacked links is had same prefix, 10.0.0.0/8 should not be removed from
        // local_net_access map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(
                eq(PREFIX_LENGTH_IPV4 + 8),
                eq(WIFI_IFNAME_2),
                eq(InetAddresses.parseNumericAddress("10.0.0.0")),
                eq(0),
                eq(0)
        )
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
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(IPV4_PREFIX_1.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property from one IPv4 to another IPv4 within same range(10.0.0.0/8)
        val wifiLp2 = lp(WIFI_IFNAME, IPV4_ADDRESS_2)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // As both stacked links is had same prefix, 10.0.0.0/8 should not be removed from
        // local_net_access map.
        verify(bpfNetMaps, never()).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Updating Link Property by changing interface name which has IPv4 instead of IPv6
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        wifiAgent.sendLinkProperties(wifiLp2)
        cb.expect<LinkPropertiesChanged>(wifiAgent.network)

        // Multicast and Broadcast address should be populated in local_net_access map for
        // new interface
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME_2),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Adding another network with LinkProperty having IPv4 in LinkAddress
        val wifiLp2 = lp(WIFI_IFNAME_2, IPV4_ADDRESS_1)
        val wifiAgent2 = Agent(nc = wifiNc, lp = wifiLp2)
        wifiAgent2.connect()

        // Multicast and Broadcast address should always be populated in local_net_access map
        verifyPopulationOfMulticastAndBroadcastAddress(WIFI_IFNAME_2)
        // Verifying IPv4 matching prefix(10.0.0.0/8) should be populated in local_net_access map
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 8),
            eq(WIFI_IFNAME_2),
            eq(InetAddresses.parseNumericAddress("10.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        // Verifying nothing should be removed from local_net_access map
        verify(
            bpfNetMaps,
            never()
        ).removeLocalNetAccess(
            anyInt(),
            anyString(),
            any(InetAddress::class.java),
            anyInt(),
            anyInt()
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq( PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )

        // Unregistering the network
        wifiAgent.unregisterAfterReplacement(LONG_TIMEOUT_MS)
        cb.expect<Lost>(wifiAgent.network)

        // Multicast and Broadcast address should be removed in local_net_access map for
        // old interface
        verifyRemovalOfMulticastAndBroadcastAddress()
        // Verifying IPv6 address should be removed from local_net_access map
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + LINK_LOCAL_PREFIX.getPrefixLength()),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0)
        )
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
        verify(bpfNetMaps, never()).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6),
            eq(WIFI_IFNAME),
            eq(IPV6_DEFAULT_ROUTE_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(
                PREFIX_LENGTH_IPV6 +
                    LINK_LOCAL_PREFIX.getPrefixLength()
            ),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    @Test
    fun testNetworkWithIPv6LocalAddressAndRedundantRoute_UniqueRoutesAddedToBpfMap() {
        val nr = nr(TRANSPORT_WIFI)
        val cb = TestableNetworkCallback()
        cm.requestNetwork(nr, cb)

        val routes = listOf(
            RouteInfo(
            IPV6_HOME_PREFIX_2,
            null,
            WIFI_IFNAME
            ),
            RouteInfo(
                IPV6_HOME_PREFIX,
                null,
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
        verify(bpfNetMaps).addLocalNetAccess(
            eq(
                PREFIX_LENGTH_IPV6 +
                    IPV6_HOME_PREFIX.getPrefixLength()
            ),
            eq(WIFI_IFNAME),
            eq(IPV6_HOME_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
        verify(bpfNetMaps).addLocalNetAccess(
            eq(
                PREFIX_LENGTH_IPV6 +
                    LINK_LOCAL_PREFIX.getPrefixLength()
            ),
            eq(WIFI_IFNAME),
            eq(LINK_LOCAL_PREFIX.getAddress()),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    // Verify if multicast and broadcast addresses have been added using addLocalNetAccess
    fun verifyPopulationOfMulticastAndBroadcastAddress(
        interfaceName: String = WIFI_IFNAME
    ) {
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 4),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("224.0.0.0")),
            eq(0),
            eq(0),
            eq(false)
        )
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + 8),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("ff00::")),
            eq(0),
            eq(0),
            eq(false)
        )
        verify(bpfNetMaps).addLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 32),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("255.255.255.255")),
            eq(0),
            eq(0),
            eq(false)
        )
    }

    // Verify if multicast and broadcast addresses have been removed using removeLocalNetAccess
    fun verifyRemovalOfMulticastAndBroadcastAddress(
        interfaceName: String = WIFI_IFNAME
    ) {
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 4),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("224.0.0.0")),
            eq(0),
            eq(0)
        )
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV6 + 8),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("ff00::")),
            eq(0),
            eq(0)
        )
        verify(bpfNetMaps).removeLocalNetAccess(
            eq(PREFIX_LENGTH_IPV4 + 32),
            eq(interfaceName),
            eq(InetAddresses.parseNumericAddress("255.255.255.255")),
            eq(0),
            eq(0)
        )
    }
}
