/*
 * Copyright (C) 2024 The Android Open Source Project
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
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.net.platform.flags.Flags.FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT
import android.os.Build
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_CELL_IFACE = "test_rmnet"
private const val TEST_CELL_IFACE_INDEX = 1000
private const val TEST_CELL_CLAT_IFACE = "v4-test_rmnet"
private const val TEST_CELL_CLAT_IFACE_INDEX = 1001

private fun cellNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .addCapability(NET_CAPABILITY_INTERNET)

private fun vpnNc() = defaultNc()
    .addTransportType(TRANSPORT_VPN)
    .setTransportInfo(
        VpnTransportInfo(
            VpnManager.TYPE_VPN_PLATFORM,
            null /* sessionId */,
            false /* bypassable */,
            false /* longLivedTcpConnectionsExpensive */
        )
    )
    .addCapability(NET_CAPABILITY_INTERNET)
    .removeCapability(NET_CAPABILITY_NOT_VPN)
    .addCapability(NET_CAPABILITY_NOT_SUSPENDED)
    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)

private fun makeLp(interfaceName: String) = LinkProperties().also {
    it.interfaceName = interfaceName
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
class CSTcQdiscClsactTest : CSTest() {
    override fun setUp() {
        super.setUp()
        deps.ifnameToIndexMap.put(TEST_CELL_IFACE, TEST_CELL_IFACE_INDEX)
        deps.ifnameToIndexMap.put(TEST_CELL_CLAT_IFACE, TEST_CELL_CLAT_IFACE_INDEX)
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCsApplyTcQdiscClsactOnNormalIface() {
        // Adding an interface is triggered when connecting an agent with
        // LinkProperties that contain an interface name.
        // This calls updateInterfaces() as part of the connection process.
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, true)

        // Removing an interface is triggered by sending empty LinkProperties.
        // This also results in a call to updateInterfaces().
        agent.sendLinkProperties(LinkProperties())
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCsApplyTcQdiscClsactOnNormalIfaceWithoutLinkPropertiesChange() {
        // Adding an interface is triggered when connecting an agent with
        // LinkProperties that contain an interface name.
        // This calls updateInterfaces() as part of the connection process.
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, true)

        // When a network disconnects, it will destroyNetwork without any
        // link properties change
        agent.disconnect()
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, false)
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCsApplyTcQdiscClsactOnClatIface() {
        // The implementation in ConnectivityService should skip adding
        // clsact for "v4-" interfaces.
        val lpV4 = makeLp(TEST_CELL_CLAT_IFACE)
        val agent = Agent(nc = cellNc(), lp = lpV4)
        agent.connect()
        deps.expectNoRtmQdiscClsactRequest()

        // Removing a "v4-" interface should still trigger the removal
        // of clsact.
        agent.sendLinkProperties(LinkProperties())
        deps.expectRtmQdiscClsactRequest(TEST_CELL_CLAT_IFACE_INDEX, false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCsApplyTcQdiscClsactOnVpn() {
        val agent = Agent(nc = vpnNc(), lp = LinkProperties())
        agent.connect()
        val lp = makeLp(TEST_CELL_IFACE)
        agent.sendLinkProperties(lp)
        deps.expectNoRtmQdiscClsactRequest()

        agent.sendLinkProperties(LinkProperties())
        deps.expectNoRtmQdiscClsactRequest()
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, false)])
    fun testNetdApplyTcQdiscClsact() {
        val agent = Agent(nc = cellNc(), lp = LinkProperties())
        agent.connect()
        val lp = makeLp(TEST_CELL_IFACE)
        agent.sendLinkProperties(lp)
        deps.expectNoRtmQdiscClsactRequest()

        agent.sendLinkProperties(LinkProperties())
        deps.expectNoRtmQdiscClsactRequest()
        agent.disconnect()
    }
}
