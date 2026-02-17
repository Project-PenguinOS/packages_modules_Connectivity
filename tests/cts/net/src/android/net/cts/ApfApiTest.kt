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
// ktlint does not allow annotating function argument literals inline. Disable the specific rule
// since this negatively affects readability.
@file:Suppress("ktlint:standard:comment-wrapping")

package android.net.cts

import android.content.pm.PackageManager.FEATURE_WATCH
import android.os.Build
import android.platform.test.annotations.AppModeFull
import android.system.OsConstants
import androidx.test.filters.RequiresDevice
import com.android.compatibility.common.util.PropertyUtil.getFirstApiLevel
import com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel
import com.android.compatibility.common.util.VsrTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.SkipPresubmit
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlin.random.Random
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

open class FromU<Type>(val value: Type)

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@RequiresDevice
@NetworkStackModuleTest
// ByteArray.toHexString is experimental API
@ExperimentalStdlibApi
class ApfApiTest : ApfTestBase() {

    private fun shouldEnforceApfSupport(vsrApiLevel: Int): Boolean {
        // Note: GMS-VSR requirements related to APFv4/APFv6 are only applicable to handheld
        // and tablet devices. GTVS requirements related to APFv6 are only applicable to TV devices.
        // For Wear OS devices, APFv4/APFv6 will not be enforced until Wear OS 7.
        if (pm.hasSystemFeature(FEATURE_WATCH)) {
            // Enforce APF on watch post VSR-16.
            return vsrApiLevel > 202504
        }
        return vsrApiLevel >= 34
    }

    @VsrTest(
        requirements = ["VSR-5.3.12-001", "VSR-5.3.12-003", "VSR-5.3.12-004", "VSR-5.3.12-009",
            "VSR-5.3.12-012"]
    )
    @Test
    fun testApfCapabilities() {
        // If APF is supported, the version must be valid.
        assertThat(caps.apfVersionSupported).isAnyOf(0, 2, 3, 4, 6000, 6100)
        // APF became mandatory in Android 14 VSR.
        val vsrApiLevel = getVsrApiLevel()
        // If the firmware declares a version greater than or equal to 6000, it must properly
        // support APFv6+.
        if (caps.apfVersionSupported < 6000) {
            assumeTrue(shouldEnforceApfSupport(vsrApiLevel))
        }

        // DEVICEs launching with Android 14 with CHIPSETs that set ro.board.first_api_level to 34:
        // - [GMS-VSR-5.3.12-003] MUST return 4 or higher as the APF version number from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // - [GMS-VSR-5.3.12-004] MUST indicate at least 1024 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // TODO: check whether above text should be changed "34 or higher"
        assertThat(caps.apfVersionSupported).isAtLeast(4)
        assertThat(caps.maximumApfProgramSize).isAtLeast(1024)

        if (caps.apfVersionSupported > 4) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2048)
            assertThat(caps.apfVersionSupported).isAnyOf(6000, 6100) // v6.000 or v6.100
        }

        // DEVICEs launching with Android 15 (AOSP experimental) or higher with CHIPSETs that set
        // ro.board.first_api_level or ro.board.api_level to 202404 or higher:
        // - [GMS-VSR-5.3.12-009] MUST indicate at least 2048 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        if (vsrApiLevel >= 202404) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2048)
        }

        // DEVICEs with CHIPSETs that set ro.board.first_api_level or ro.board.api_level to 202504
        // or higher:
        // - [VSR-5.3.12-018] MUST implement version 6 or version 6.1 of the Android Packet
        //   Filtering (APF) interpreter in the Wi-Fi firmware.
        // - [VSR-5.3.12-019] MUST provide at least 4000 bytes of APF RAM when version 6 is
        //   implemented OR 3000 bytes when version 6.1 is implemented.
        // - Note, the APF RAM requirement for APF version 6.1 will become 4000 bytes in Android 17
        //   with CHIPSETs that set ro.board.first_api_level or ro.board.api_level to 202604 or
        //   higher.
        if (vsrApiLevel >= 202504) {
            assertThat(caps.apfVersionSupported).isAnyOf(6000, 6100)
            if (caps.apfVersionSupported == 6000) {
                assertThat(caps.maximumApfProgramSize).isAtLeast(4000)
            } else {
                assertThat(caps.maximumApfProgramSize).isAtLeast(3000)
            }
        }

        // DEVICEs with CHIPSETs that set ro.board.first_api_level or ro.board.api_level to 202604
        // or higher:
        // - [GMS-VSR-5.3.12-020] MUST implement version 6.1 of the Android Packet Filtering (APF)
        //   interpreter in the Wi-Fi firmware.
        // - [GMS-VSR-5.3.12-021] MUST provide at least 4000 bytes of APF RAM.
        if (vsrApiLevel >= 202604) {
            assertThat(caps.apfVersionSupported).isEqualTo(6100)
            assertThat(caps.maximumApfProgramSize).isAtLeast(4000)
        }

        // ApfFilter does not support anything but ARPHRD_ETHER.
        assertThat(caps.apfPacketFormat).isEqualTo(OsConstants.ARPHRD_ETHER)
    }

    @VsrTest(
            requirements = ["VSR-5.3.12-007", "VSR-5.3.12-008", "VSR-5.3.12-010", "VSR-5.3.12-011"]
    )
    @SkipPresubmit(reason = "This test takes longer than 1 minute, do not run it on presubmit.")
    // APF integration is mostly broken before V, only run the full read / write test on V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    // Increase timeout for test to 20 minutes to accommodate device with large APF RAM.
    @Test(timeout = 20 * 60 * 1000)
    fun testReadWriteProgram() {
        assumeApfVersionSupportAtLeast(4)

        val minReadWriteSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            2
        } else {
            8
        }

        val program = ByteArray(caps.maximumApfProgramSize)

        // On userdebug builds, iterate in steps of 31 to make the test run faster.
        // On user builds, iterate through every value for full coverage.
        val testSizes = if (Build.isDebuggable()) {
            // For userdebug: iterate in steps of 31, but always include min and max values
            val sizes = mutableListOf<Int>()
            sizes.add(caps.maximumApfProgramSize)
            var current = caps.maximumApfProgramSize - 31
            while (current > minReadWriteSize) {
                sizes.add(current)
                current -= 31
            }
            if (sizes.last() != minReadWriteSize) {
                sizes.add(minReadWriteSize)
            }
            sizes
        } else {
            // For user builds: test every size for full coverage
            (caps.maximumApfProgramSize downTo minReadWriteSize).toList()
        }

        // The minReadWriteSize is 2 bytes. The first byte always stays PASS.
        for (i in testSizes) {
            // Randomize bytes in range [1, i). And install first [0, i) bytes of program.
            // Note that only the very first instruction (PASS) is valid APF bytecode.
            Random.nextBytes(program, 1 /* fromIndex */, i /* toIndex */)
            installProgram(program.sliceArray(0..<i))

            // Compare entire memory region.
            val readResult = readProgram()
            val errMsg = """
                read/write $i byte prog failed.
                In APFv4, the APF memory region MUST NOT be modified or cleared except by APF
                instructions executed by the interpreter or by Android OS calls to the HAL. If this
                requirement cannot be met, the firmware cannot declare that it supports APFv4 and
                it should declare that it only supports APFv3(if counter is partially supported) or
                APFv2.
            """.trimIndent()
            assertWithMessage(errMsg).that(readResult).isEqualTo(program)
        }
    }
}
