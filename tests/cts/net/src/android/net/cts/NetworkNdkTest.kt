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

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.net.LinkAddress
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.TestNetworkManager
import android.net.TestNetworkSpecifier
import android.os.Binder
import android.os.Build
import android.os.Process
import android.platform.test.annotations.AppModeFull
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.runAsShell
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.filters.CtsNetTestCasesLocalNetNoPermissions
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
@RunWith(DevSdkIgnoreRunner::class)
class NetworkNdkTest {
    @get:Rule
    val networkCallbackRule = AutoReleaseNetworkCallbackRule()

    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val tnm by lazy { context.getSystemService(TestNetworkManager::class.java)!! }

    init {
        System.loadLibrary("nativemultinetwork_jni")
    }

    private external fun nativeTestGetBlockedReason(
        netHandle: Long,
        addrStr: String,
        socketType: Int,
        useDynamicLoading: Boolean,
        expectBlocked: Boolean
    )

    enum class SocketType(val value: Int) {
        TCP(0),
        UDP_UNCONNECTED(1),
        UDP_CONNECTED(2),
    }

    fun doTestGetBlockedReason(
        socketType: SocketType,
        expectBlocked: Boolean
    ) {
        // Skip compat mode, as the getsockopt hook is not called in this environment.
        // The 26Q2 release requires a 64-bit kernel, so a 32-bit userspace
        // implies the device is running in compat mode.
        assumeTrue(Process.is64Bit())

        val iface = runAsShell(MANAGE_TEST_NETWORKS) {
            val linkAddresses = listOf(
                LinkAddress("192.0.2.2/24"),
                LinkAddress("2001:db8:1:2::2/64")
            )
            val iface = tnm.createTunInterface(linkAddresses).interfaceName
            tnm.setupTestNetwork(iface, Binder())
            iface
        }
        val cb = networkCallbackRule.requestNetwork(
            NetworkRequest.Builder()
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                .addTransportType(NetworkCapabilities.TRANSPORT_TEST)
                .setNetworkSpecifier(TestNetworkSpecifier(iface))
                .build()
        )
        val network = cb.eventuallyExpect<Available>().network

        try {
            nativeTestGetBlockedReason(
                network.networkHandle,
                "2001:db8:1:2::3",
                socketType.value,
                useDynamicLoading = true,
                expectBlocked
            )
            nativeTestGetBlockedReason(
                network.networkHandle,
                "2001:db8:1:2::3",
                socketType.value,
                useDynamicLoading = false,
                expectBlocked
            )
        } finally {
            runAsShell(MANAGE_TEST_NETWORKS) {
                tnm.teardownTestNetwork(network)
            }
            cb.eventuallyExpect<Lost>()
        }
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    fun testBlockedReason_Udp_Unconnected_NotBlocked() {
        doTestGetBlockedReason(SocketType.UDP_UNCONNECTED, expectBlocked = false)
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    fun testBlockedReason_Udp_Connected_NotBlocked() {
        doTestGetBlockedReason(SocketType.UDP_CONNECTED, expectBlocked = false)
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    fun testBlockedReason_Tcp_NotBlocked() {
        doTestGetBlockedReason(SocketType.TCP, expectBlocked = false)
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    @CtsNetTestCasesLocalNetNoPermissions
    fun testBlockedReason_Udp_Unconnected_Blocked() {
        doTestGetBlockedReason(SocketType.UDP_UNCONNECTED, expectBlocked = true)
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    @CtsNetTestCasesLocalNetNoPermissions
    fun testBlockedReason_Udp_Connected_Blocked() {
        doTestGetBlockedReason(SocketType.UDP_CONNECTED, expectBlocked = true)
    }

    @Test
    @ConnectivityModuleTest
    @AppModeFull(reason = "Cannot create test network in instant app mode")
    @CtsNetTestCasesLocalNetNoPermissions
    fun testBlockedReason_Tcp_Blocked() {
        doTestGetBlockedReason(SocketType.TCP, expectBlocked = true)
    }
}
