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
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH
import android.net.NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY
import android.net.NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_VPN
import android.net.VpnManager
import android.net.VpnTransportInfo
import android.net.platform.flags.Flags.FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT
import android.os.Build
import android.system.OsConstants.ETH_P_ALL
import com.android.server.ConnectivityService.BPF_L4S_EGRESS_ETH_PROG
import com.android.server.ConnectivityService.BPF_L4S_EGRESS_RAWIP_PROG
import com.android.server.ConnectivityService.PRIO_L4S
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_CELL_IFACE = "test_rmnet"
private const val TEST_CELL_IFACE_INDEX = 1000
private const val TEST_CELL_ETH_IFACE = "test_rmnet_eth"
private const val TEST_CELL_ETH_IFACE_INDEX = 1001

private fun cellNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .addCapability(NET_CAPABILITY_INTERNET)

private fun latencyNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .addCapability(NET_CAPABILITY_PRIORITIZE_LATENCY)

private fun unifiedCommunicationsNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .addCapability(NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS)

private fun bandwidthNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .addCapability(NET_CAPABILITY_PRIORITIZE_BANDWIDTH)

private fun nonL4sCapableNc() = defaultNc()
    .addTransportType(TRANSPORT_CELLULAR)
    .removeCapability(NET_CAPABILITY_INTERNET)

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

private fun makeLp(interfaceName: String) = LinkProperties().also {
    it.interfaceName = interfaceName
}

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
class CSL4sEgressProgramTest : CSTest() {
    override fun setUp() {
        super.setUp()
        deps.ifnameToIndexMap[TEST_CELL_IFACE] = TEST_CELL_IFACE_INDEX
        deps.ifnameToIndexMap[TEST_CELL_ETH_IFACE] = TEST_CELL_ETH_IFACE_INDEX
        deps.ethIntfIfname.add(TEST_CELL_ETH_IFACE)
    }

    private fun expectAttachL4sEgressProgram(ifIndex: Int, isEth: Boolean) {
        val progPath = if (isEth) BPF_L4S_EGRESS_ETH_PROG else BPF_L4S_EGRESS_RAWIP_PROG
        deps.expectAttachBpfProgram(
            ifIndex,
            false /* ingress */,
            PRIO_L4S,
            ETH_P_ALL.toShort(),
            progPath
        )
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyRawIpL4sEgressProgram() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()

        // Verify program attach
        expectAttachL4sEgressProgram(TEST_CELL_IFACE_INDEX, isEth = false)
        agent.sendLinkProperties(LinkProperties())

        // Verify detach, attached programs is cleared by removing qdisc clsact
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, add = false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyEthL4sEgressProgram() {
        val lp = makeLp(TEST_CELL_ETH_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()

        // Verify program attach
        expectAttachL4sEgressProgram(TEST_CELL_ETH_IFACE_INDEX, isEth = true)
        agent.sendLinkProperties(LinkProperties())

        // Verify detach, attached programs is cleared by removing qdisc clsact
        deps.expectRtmQdiscClsactRequest(TEST_CELL_ETH_IFACE_INDEX, add = false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyRawIpL4sEgressProgramWithoutLinkPropertiesChange() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()

        // Verify attach
        expectAttachL4sEgressProgram(TEST_CELL_IFACE_INDEX, isEth = false)

        // When a network disconnects, it will destroyNetwork without any
        // link properties change
        agent.disconnect()
        // Verify detach, attached programs is cleared by removing qdisc clsact
        deps.expectRtmQdiscClsactRequest(TEST_CELL_IFACE_INDEX, add = false)
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyEthL4sEgressProgramWithoutLinkPropertiesChange() {
        val lp = makeLp(TEST_CELL_ETH_IFACE)
        val agent = Agent(nc = cellNc(), lp = lp)
        agent.connect()

        // Verify attach
        expectAttachL4sEgressProgram(TEST_CELL_ETH_IFACE_INDEX, isEth = true)

        // When a network disconnects, it will destroyNetwork without any
        // link properties change
        agent.disconnect()
        // Verify detach, attached programs is cleared by removing qdisc clsact
        deps.expectRtmQdiscClsactRequest(TEST_CELL_ETH_IFACE_INDEX, add = false)
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyL4sEgressProgramWithLatencyCapability() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = latencyNc(), lp = lp)
        agent.connect()
        expectAttachL4sEgressProgram(TEST_CELL_IFACE_INDEX, isEth = false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyL4sEgressProgramWithUnifiedCommunicationsCapability() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = unifiedCommunicationsNc(), lp = lp)
        agent.connect()
        expectAttachL4sEgressProgram(TEST_CELL_IFACE_INDEX, isEth = false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyL4sEgressProgramWithBandwidthCapability() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = bandwidthNc(), lp = lp)
        agent.connect()
        expectAttachL4sEgressProgram(TEST_CELL_IFACE_INDEX, isEth = false)
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyL4sEgressProgramWithNonL4sCapability() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = nonL4sCapableNc(), lp = lp)
        agent.connect()
        deps.expectNoAttachBpfProgram()
        agent.disconnect()
    }

    @Test
    @FeatureFlags([Flag(FLAG_CONNECTIVITY_SERVICE_MODIFY_QDISC_CLSACT, true)])
    fun testCSApplyL4sEgressProgramWithVpn() {
        val lp = makeLp(TEST_CELL_IFACE)
        val agent = Agent(nc = vpnNc(), lp = lp)
        agent.connect()
        deps.expectNoAttachBpfProgram()
        agent.disconnect()
    }
}
