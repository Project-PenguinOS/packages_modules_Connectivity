/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.net.Network
import android.net.nsd.DiscoveryRequest
import android.net.nsd.DiscoveryRequest.FLAG_NO_PICKER
import android.os.Build
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertParcelingIsLossless
import com.android.testutils.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith

/** CTS tests for {@link DiscoveryRequest}. */
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@ConnectivityModuleTest
class DiscoveryRequestTest {
    @Test
    fun testParcelingIsLossLess() {
        val requestWithNullFields =
                DiscoveryRequest.Builder("_ipps._tcp").build()
        val requestWithAllFields =
                DiscoveryRequest.Builder("_ipps._tcp")
                                .setSubtype("_xyz")
                                .setNetwork(Network(1))
                                .setFlags(FLAG_NO_PICKER)
                                .build()

        assertParcelingIsLossless(requestWithNullFields)
        assertParcelingIsLossless(requestWithAllFields)
    }

    @Test
    fun testBuilder_success() {
        val request = DiscoveryRequest.Builder("_ipps._tcp")
                                      .setSubtype("_xyz")
                                      .setNetwork(Network(1))
                                      .setFlags(FLAG_NO_PICKER)
                                      .build()

        assertEquals("_ipps._tcp", request.serviceType)
        assertEquals("_xyz", request.subtype)
        assertEquals(Network(1), request.network)
        assertEquals(FLAG_NO_PICKER, request.flags)
    }

    @Test
    fun testBuilderConstructor_emptyServiceType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException::class.java) {
            DiscoveryRequest.Builder("")
        }
    }

    @Test
    fun testEquality() {
        val request1 = DiscoveryRequest.Builder("_ipps._tcp").build()
        val request2 = DiscoveryRequest.Builder("_ipps._tcp").build()
        val request3 = DiscoveryRequest.Builder("_ipps._tcp")
                .setSubtype("_xyz")
                .setNetwork(Network(1))
                .setFlags(FLAG_NO_PICKER)
                .build()
        val request4 = DiscoveryRequest.Builder("_ipps._tcp")
                .setSubtype("_xyz")
                .setNetwork(Network(1))
                .setFlags(FLAG_NO_PICKER)
                .build()

        assertEquals(request1, request2)
        assertEquals(request3, request4)
        assertNotEquals(request1, request3)
        assertNotEquals(request2, request4)
    }

    @Test
    fun testEquality_differentFlags_notEqual() {
        val request1 = DiscoveryRequest.Builder("_ipps._tcp").setFlags(0L).build()
        val request2 = DiscoveryRequest.Builder("_ipps._tcp").setFlags(FLAG_NO_PICKER).build()

        assertNotEquals(request1, request2)
    }

    @Test
    fun testSetFlags_useMask_selectedBitsModified() {
        val request = DiscoveryRequest.Builder("_ipps._tcp")
            .setFlags(0b10101)
            .setFlags(/* flags=*/0b01000L, /* mask=*/0b11000L)
            .build()

        assertEquals(0b01101, request.flags)
    }
}
