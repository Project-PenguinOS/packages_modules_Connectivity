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

package android.net.cts

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.permission.flags.Flags
import android.platform.test.annotations.AppModeFull
import android.system.Os
import android.system.OsConstants
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.filters.CtsNetTestCasesLocalNetNoPermissions
import kotlin.test.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@AppModeFull(reason = "Cannot create test network in instant app mode")
@IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
@RunWith(DevSdkIgnoreRunner::class)
class LocalNetworkTest : TestInterfaceBase() {

    @Before
    fun ensureLnpSupported() {
        assumeTrue(Flags.accessLocalNetworkPermissionEnabled())
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_dropsLocalEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV4_ADDRESS, false)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV6_ADDRESS, false)
        sendUdpPacketAndCheckSuccess(linkLocalIpv6Address, false)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_allowsOffLinkEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendUdpPacketAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    fun testPermissionGranted_sendsLocalEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV4_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV6_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(linkLocalIpv6Address, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_reuseUdpSocket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)

        val sock = Os.socket(
            OsConstants.AF_INET6,
            OsConstants.SOCK_DGRAM,
            OsConstants.IPPROTO_UDP
        )
        network.bindSocket(sock)
        try {
            sendUdpPacketAndCheckSuccess(sock, ON_LINK_IPV6_ADDRESS, false)
            sendUdpPacketAndCheckSuccess(sock, ON_LINK_IPV6_ADDRESS, false)
            sendUdpPacketAndCheckSuccess(sock, OFF_LINK_IPV6_ADDRESS, true)
            sendUdpPacketAndCheckSuccess(sock, OFF_LINK_IPV6_ADDRESS, true)
            sendUdpPacketAndCheckSuccess(sock, ON_LINK_IPV6_ADDRESS, false)
            sendUdpPacketAndCheckSuccess(sock, ON_LINK_IPV6_ADDRESS, false)
        } finally {
            Os.close(sock)
        }
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_dropsLocalEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendTcpPacketAndAssertPermissionDenied(ON_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertPermissionDenied(ON_LINK_IPV6_ADDRESS)
        sendTcpPacketAndAssertPermissionDenied(linkLocalIpv6Address)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_allowsOffLinkEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendTcpPacketAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    @Test
    fun testPermissionGranted_sendsLocalEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        sendTcpPacketAndAssertSuccess(ON_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertSuccess(ON_LINK_IPV6_ADDRESS)
        sendTcpPacketAndAssertSuccess(linkLocalIpv6Address)
    }

    @Test
    fun testPermissionGranted_receivesLocalIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV6_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(linkLocalIpv6Address, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_dropsLocalIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV4_ADDRESS, false)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV6_ADDRESS, false)
        writeIngressUdpAndCheckSuccess(linkLocalIpv6Address, false)
    }

    @Test
    fun testPermissionGranted_receivesOffLinkIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_receivesOffLinkIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    fun testPermissionGranted_receivesLocalIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressTcpAndAssertSuccess(ON_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(ON_LINK_IPV6_ADDRESS)
        writeIngressTcpAndAssertSuccess(linkLocalIpv6Address)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_dropsLocalIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressTcpAndAssertPermissionDenied(ON_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertPermissionDenied(ON_LINK_IPV6_ADDRESS)
        writeIngressTcpAndAssertPermissionDenied(linkLocalIpv6Address)
    }

    @Test
    fun testPermissionGranted_receivesOffLinkIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    fun testMissingPermission_receivesOffLinkIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    private fun assertLocalNetworkPermissions(expected: Int) {
        assertEquals(
            expected,
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        )
    }
}
