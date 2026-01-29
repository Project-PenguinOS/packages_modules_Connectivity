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
@file:Suppress("ktlint:standard:comment-wrapping")
package android.net.cts

import android.Manifest.permission
import android.content.pm.PackageManager.FEATURE_AUTOMOTIVE
import android.content.pm.PackageManager.FEATURE_LEANBACK
import android.content.pm.PackageManager.FEATURE_PC
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.apf.ApfCapabilities
import android.net.cts.util.CtsNetUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.SystemProperties
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.modules.utils.build.SdkLevel
import com.android.net.module.util.HexDump
import com.android.testutils.ConnectUtil
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.Available
import com.android.testutils.TestableNetworkCallback.Event.LinkPropertiesChanged
import com.android.testutils.pollingCheck
import com.android.testutils.runAsShell
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule

@kotlin.ExperimentalStdlibApi
abstract class ApfTestBase {
    companion object {
        private val TAG = "ApfIntegrationTest"
        private val TIMEOUT_MS = 2000L

        private val context = InstrumentationRegistry.getInstrumentation().context
        private val powerManager = context.getSystemService(PowerManager::class.java)!!
        private val ctsNetUtils = CtsNetUtils(context)
        private val connUtils = ConnectUtil(context)
        val pm = context.packageManager
        private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)
        private var isLowPowerStandbyOriginalEnabled: Boolean = false
        private var originalPolicy: FromU<PowerManager.LowPowerStandbyPolicy?>? = null

        fun turnScreenOff() {
            if (!wakeLock.isHeld()) wakeLock.acquire()
            runShellCommandOrThrow("input keyevent KEYCODE_SLEEP")
            waitForInteractiveState(false)
        }

        fun turnScreenOn() {
            if (wakeLock.isHeld()) wakeLock.release()
            runShellCommandOrThrow("input keyevent KEYCODE_WAKEUP")
            waitForInteractiveState(true)
        }

        private fun waitForInteractiveState(interactive: Boolean) {
            val result = pollingCheck(timeout_ms = 2000) {
                powerManager.isInteractive()
            }
            assertThat(result).isEqualTo(interactive)
        }

        private fun disableLowPowerStandby() {
            if (!SdkLevel.isAtLeastU()) {
                return
            }
            runAsShell(permission.DEVICE_POWER) {
                if (powerManager.isLowPowerStandbySupported) {
                    isLowPowerStandbyOriginalEnabled = powerManager.isLowPowerStandbyEnabled
                    originalPolicy = FromU(powerManager.lowPowerStandbyPolicy)
                    powerManager.isLowPowerStandbyEnabled = false
                    Log.i(TAG, "Low power standby is supported, disabling it temporary.")
                }
            }
        }

        private fun restoreLowPowerStandby() {
            if (!SdkLevel.isAtLeastU()) {
                return
            }
            runAsShell(permission.DEVICE_POWER) {
                if (powerManager.isLowPowerStandbySupported) {
                    powerManager.isLowPowerStandbyEnabled = isLowPowerStandbyOriginalEnabled
                    powerManager.lowPowerStandbyPolicy = originalPolicy?.value
                    Log.i(TAG, "Reset Low power standby to original state.")
                }
            }
        }

        @BeforeClass
        @JvmStatic
        @Suppress("ktlint:standard:no-multi-spaces")
        fun setupOnce() {
            // TODO: assertions thrown in @BeforeClass / @AfterClass are not well supported in the
            // test infrastructure. Consider saving exception and throwing it in setUp().

            if (pm.hasSystemFeature(FEATURE_AUTOMOTIVE)) {
                // Skip on Android Automotive to avoid running unnecessary SLEEP/WAKEUP logic.
                // Ideally, this would use assumeFalse(isAutomotive) here, but this isn't fully
                // supported by the test infra (see comment above). Thus, the proper assumption
                // check is later done in the #setup (@Before).
                return
            }

            // TODO(b/450670091): Run APF tests on desktop devices once the feature is ready.
            if (pm.hasSystemFeature(FEATURE_PC)) {
                return
            }

            // toggle Wi-Fi and ensure Wi-Fi is validated after reconnected
            ctsNetUtils.reconnectWifiIfSupported()
            connUtils.ensureWifiValidated()

            // APF must run when the screen is off and the device is not interactive.
            turnScreenOff()

            // Wait for APF to become active.
            Thread.sleep(1000)
            // TODO: check that there is no active wifi network. Otherwise, ApfFilter has already been
            // created.
            disableLowPowerStandby()
        }

        @AfterClass
        @JvmStatic
        fun tearDownOnce() {
            turnScreenOn()
            restoreLowPowerStandby()
        }
    }

    @get:Rule val ignoreRule = DevSdkIgnoreRule()
    @get:Rule val expect = Expect.create()

    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java)!! }
    protected lateinit var network: Network
    protected lateinit var ifname: String
    protected lateinit var networkCallback: TestableNetworkCallback
    protected lateinit var caps: ApfCapabilities
    protected val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    protected val handler = Handler(handlerThread.looper)

    fun getApfCapabilities(): ApfCapabilities {
        val caps = runShellCommand("cmd network_stack apf $ifname capabilities").trim()
        if (caps.isEmpty()) {
            return ApfCapabilities(0, 0, 0)
        }
        val (version, maxLen, packetFormat) = caps.split(",").map { it.toInt() }
        return ApfCapabilities(version, maxLen, packetFormat)
    }

    private fun isTvDeviceSupportFullNetworkingUnder2w(): Boolean {
        return (pm.hasSystemFeature(FEATURE_LEANBACK) &&
                pm.hasSystemFeature("com.google.android.tv.full_networking_under_2w"))
    }

    @Before
    open fun setUp() {
        assume().that(pm.hasSystemFeature(FEATURE_WIFI)).isTrue()

        // Based on GTVS-16, Android Packet Filtering (APF) is OPTIONAL for devices that fully
        // process all network packets on CPU at all times, even in standby, while meeting
        // the <= 2W standby power demand requirement.
        assumeFalse(
            "Skipping test: TV device process full networking on CPU under 2W",
            isTvDeviceSupportFullNetworkingUnder2w()
        )

        // APF GMS-VSR requirements don't apply to automotive devices. There is no power benefit to
        // running APF on automotive as the device has almost infinite battery power.
        assumeFalse("Skip test: automotive device", pm.hasSystemFeature(FEATURE_AUTOMOTIVE))

        // TODO(b/450670091): Run APF tests on desktop devices once the feature is ready.
        assumeFalse("Skip test: desktop device", pm.hasSystemFeature(FEATURE_PC))

        networkCallback = TestableNetworkCallback()
        cm.requestNetwork(
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build(),
            networkCallback
        )
        network = networkCallback.expect<Available>().network
        networkCallback.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            ifname = assertNotNull(it.lp.interfaceName)
            true
        }
        // It's possible the device does not support APF, in which case this command will not be
        // successful. Ignore the error as testApfCapabilities() already asserts APF support on the
        // respective VSR releases and all other tests are based on the capabilities indicated.
        runShellCommand("cmd network_stack apf $ifname pause")
        caps = getApfCapabilities()
    }

    @After
    open fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join()

        if (::ifname.isInitialized) {
            runShellCommand("cmd network_stack apf $ifname resume")
        }
        if (::networkCallback.isInitialized) {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    fun installProgram(bytes: ByteArray) {
        val prog = bytes.toHexString()
        val result = runShellCommandOrThrow("cmd network_stack apf $ifname install $prog").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(result).isEqualTo("success")
    }

    fun readProgram(): ByteArray {
        val progHexString = runShellCommandOrThrow("cmd network_stack apf $ifname read").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(progHexString).isNotEmpty()
        return HexDump.hexStringToByteArray(progHexString)
    }

    // APF is backwards compatible, i.e. a v6 interpreter supports both v2 and v4 functionality.
    fun assumeApfVersionSupportAtLeast(version: Int) {
        assume().that(caps.apfVersionSupported).isAtLeast(version)
    }

    fun assumeNotCuttlefish() {
        assume().that(SystemProperties.get("ro.product.board", "")).isNotEqualTo("cutf")
    }
}
