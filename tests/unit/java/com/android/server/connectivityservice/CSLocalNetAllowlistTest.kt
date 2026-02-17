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

package com.android.server.connectivityservice

import android.net.InetAddresses.parseNumericAddress
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.server.CSTest
import com.android.server.HANDLER_TIMEOUT_MS
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.never
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

private const val WIFI_IFNAME = "wlan0"
private const val WIFI_IFINDEX = 100
private val WIFI_NC = NetworkCapabilities.Builder()
    .addTransportType(TRANSPORT_WIFI)
    .addCapability(NET_CAPABILITY_INTERNET)
    .addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
    .build()

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class CSLocalNetAllowlistTest : CSTest() {
    @Test
    fun testAllowLocalNetAccess() {
        val uid = 12345
        val addr1 = parseNumericAddress("fe80::123%123")
        val addr2 = parseNumericAddress("192.0.2.123")
        doReturn(true).`when`(interfaceTracker).hasInterface(WIFI_IFINDEX)

        cm.allowLocalNetAccess(uid, WIFI_IFINDEX, listOf(addr1, addr2))

        verify(bpfNetMaps).addLocalNetUidHostAccess(uid, WIFI_IFINDEX,
            parseNumericAddress("fe80::123"))
        verify(bpfNetMaps).addLocalNetUidHostAccess(uid, WIFI_IFINDEX, addr2)
        waitForIdle()
        verify(bpfNetMaps, never()).removeLocalNetHostAllowlistForInterface(anyInt())
    }

    @Test
    fun testAllowLocalNetAccess_UntrackedInterface() {
        val uid = 12345
        val addr = parseNumericAddress("fe80::123")
        doReturn(false).`when`(interfaceTracker).hasInterface(WIFI_IFINDEX)

        cm.allowLocalNetAccess(uid, WIFI_IFINDEX, listOf(addr))
        waitForIdle()

        val inOrder = Mockito.inOrder(bpfNetMaps)
        inOrder.verify(bpfNetMaps).addLocalNetUidHostAccess(uid, WIFI_IFINDEX, addr)
        inOrder.verify(bpfNetMaps).removeLocalNetHostAllowlistForInterface(WIFI_IFINDEX)
    }

    @Test
    fun testClearLocalNetAccessOnDisconnect() {
        val lp = LinkProperties().apply {
            interfaceName = WIFI_IFNAME
            addLinkAddress(LinkAddress("fe80::123/64"))
        }
        doReturn(WIFI_IFINDEX).`when`(interfaceTracker).removeInterface(WIFI_IFNAME)

        val agent = Agent(nc = WIFI_NC, lp = lp)
        agent.connect()
        agent.disconnect()

        verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS))
            .removeLocalNetHostAllowlistForInterface(WIFI_IFINDEX)
    }

    @Test
    fun testClearLocalNetAccessOnUpdateLinkProperties() {
        val lp1 = LinkProperties().apply {
            interfaceName = WIFI_IFNAME
            addLinkAddress(LinkAddress("fe80::123/64"))
        }
        val lp2 = LinkProperties().apply {
            interfaceName = "other0"
            addLinkAddress(LinkAddress("fe80::123/64"))
        }
        doReturn(WIFI_IFINDEX).`when`(interfaceTracker).removeInterface(WIFI_IFNAME)

        val agent = Agent(nc = WIFI_NC, lp = lp1)
        agent.connect()
        agent.sendLinkProperties(lp2)

        verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS))
            .removeLocalNetHostAllowlistForInterface(WIFI_IFINDEX)
    }
}
