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

import android.Manifest.permission.NETWORK_SETTINGS
import android.content.Context
import android.net.ConnectivitySettingsManager
import android.net.ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_AUTOMATIC
import android.net.ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_ENABLED
import android.os.Build
import android.os.SystemClock
import android.platform.test.annotations.AppModeFull
import com.android.net.module.util.NetworkStackConstants.TCPHDR_SYN
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.TcpHeader
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.DumpTestUtils
import com.android.testutils.runAsShell
import kotlin.test.assertNotNull
import kotlin.test.fail
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
@RunWith(DevSdkIgnoreRunner::class)
@AppModeFull(reason = "Cannot create test network in instant app mode")
class L4sTest : TestInterfaceBase() {

    // TODO: Make NetworkStackConstants.TCPHDR_* short instead of byte, and add TCPHDR_AE
    private val TCP_FLAGS_MASK = 0xfff
    private val L4S_SYN_FLAGS = 0x1c2

    private var l4sOptionval: Int? = null

    @Before
    fun ensureL4sEnabled() {
        assumeTrue("L4S not enabled", isL4sEnabled() != null)
        l4sOptionval = ConnectivitySettingsManager.getL4sDeveloperOption(context)
    }

    @After
    fun maybeRestoreL4sSetting() {
        if (l4sOptionval != null) {
            setL4sDeveloperOptionAndCheck(l4sOptionval!!)
        }
    }

    @Test
    fun testL4sSyn() {
        attemptTcpConnection(network, OFF_LINK_IPV4_ADDRESS)
        assertNotNull(packetReader.poll(TEST_TIMEOUT_MS, { packet ->
            matchTcpPacket(packet, OFF_LINK_IPV4_ADDRESS, TCPHDR_SYN.toInt())
        }), "Did not receive SYN with non-L4S flags")

        setL4sDeveloperOptionAndCheck(L4S_DEVELOPER_OPTION_ENABLED)

        attemptTcpConnection(network, OFF_LINK_IPV4_ADDRESS)
        assertNotNull(packetReader.poll(TEST_TIMEOUT_MS, { packet ->
            matchTcpPacket(packet, OFF_LINK_IPV4_ADDRESS, L4S_SYN_FLAGS)
        }), "Did not receive SYN with L4S flags")
    }

    private fun getTcpFlags(pkt: ByteArray): Int {
        val tcpHeader = Struct.parse(TcpHeader::class.java, pkt)
        return tcpHeader.dataOffsetAndControlBits.toInt() and TCP_FLAGS_MASK
    }

    private fun setL4sDeveloperOption(state: Int) {
        runAsShell(NETWORK_SETTINGS) {
            ConnectivitySettingsManager.setL4sDeveloperOption(context, state)
        }
    }

    private fun isL4sEnabled(): Boolean? {
        val dump = DumpTestUtils.dumpServiceWithShellPermission(
            Context.CONNECTIVITY_SERVICE,
            "trafficcontroller"
        )
        return when {
            dump.contains("sL4sEnabledMap: true") -> true
            dump.contains("sL4sEnabledMap: false") -> false
            else -> null
        }
    }

    private fun setL4sDeveloperOptionAndCheck(state: Int) {
        setL4sDeveloperOption(state)

        // We cannot check for AUTOMATIC because in CS L4S is either enabled or disabled.
        if (state == L4S_DEVELOPER_OPTION_AUTOMATIC) return

        val expectedEnabled = state == L4S_DEVELOPER_OPTION_ENABLED
        val startTime = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - startTime < TEST_TIMEOUT_MS) {
            if (isL4sEnabled() == expectedEnabled) {
                return
            }
            SystemClock.sleep(100)
        }
        fail("L4S state not set to to $expectedEnabled in bpf map after $TEST_TIMEOUT_MS ms")
    }
}
