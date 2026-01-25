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
import android.os.PatternMatcher
import android.os.PatternMatcher.PATTERN_ADVANCED_GLOB
import android.os.PatternMatcher.PATTERN_PREFIX
import android.os.PatternMatcher.PATTERN_SIMPLE_GLOB
import androidx.test.filters.SmallTest
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.assertFieldCountEquals
import com.android.testutils.assertParcelingIsLossless
import com.android.testutils.assertThrows
import kotlin.test.assertContains
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
        val requestWithAllFields = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(PatternMatcher("pattern1", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("attr3")
            .build()

        assertParcelingIsLossless(requestWithNullFields)
        assertParcelingIsLossless(requestWithAllFields)

        // The test must be updated if fields are added
        assertFieldCountEquals(8, DiscoveryRequest::class.java)
    }

    @Test
    fun testBuilder_success() {
        val serviceNamePattern = PatternMatcher("pattern1", PATTERN_PREFIX)
        val attrFilter1 = PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB)
        val attrFilter2 = PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
        val request = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(serviceNamePattern)
            .setAttributeFilters(mapOf(
                "attr1" to attrFilter1,
                "attr2" to attrFilter2
            ))
            .setDisplayNameAttribute("attr3")
            .build()

        assertEquals("_ipps._tcp", request.serviceType)
        assertEquals("_xyz", request.subtype)
        assertEquals(Network(1), request.network)
        assertEquals(FLAG_NO_PICKER, request.flags)
        assertEquals(serviceNamePattern.toString(), request.serviceNameFilter.toString())
        assertEquals(2, request.attributeFilters.size)
        assertEquals(attrFilter1.toString(), request.attributeFilters["attr1"].toString())
        assertEquals(attrFilter2.toString(), request.attributeFilters["attr2"].toString())
        assertEquals("attr3", request.displayNameAttribute)

        // The test must be updated if fields are added
        assertFieldCountEquals(8, DiscoveryRequest::class.java)
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
            .setServiceNameFilter(PatternMatcher("pattern1", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("attr3")
            .build()
        val request4 = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(PatternMatcher("pattern1", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("attr3")
            .build()

        assertEquals(request1, request2)
        assertEquals(request3, request4)
        assertNotEquals(request1, request3)
        assertNotEquals(request2, request4)

        // The test must be updated if fields are added
        assertFieldCountEquals(8, DiscoveryRequest::class.java)
    }

    @Test
    fun testEquality_differentFlags_notEqual() {
        val request1 = DiscoveryRequest.Builder("_ipps._tcp").setFlags(0L).build()
        val request2 = DiscoveryRequest.Builder("_ipps._tcp").setFlags(FLAG_NO_PICKER).build()

        assertNotEquals(request1, request2)
    }

    @Test
    fun testEquality_differentAttributeFilter_notEqual() {
        val request1 = DiscoveryRequest.Builder("_ipps._tcp")
            .setAttributeFilters(mapOf("attr1" to PatternMatcher("pattern1", PATTERN_PREFIX)))
            .build()
        val request2 = DiscoveryRequest.Builder("_ipps._tcp")
            .setAttributeFilters(mapOf("attr1" to PatternMatcher("pattern2", PATTERN_PREFIX)))
            .build()
        val request3 = DiscoveryRequest.Builder("_ipps._tcp")
            .setAttributeFilters(mapOf("attr2" to PatternMatcher("pattern1", PATTERN_PREFIX)))
            .build()

        assertNotEquals(request1, request2)
        assertNotEquals(request2, request3)
        assertNotEquals(request1, request3)
    }

    @Test
    fun testHashCode() {
        val request1 = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(PatternMatcher("pattern1", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("attr3")
            .build()
        val request2 = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(PatternMatcher("pattern1", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("pattern2", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("pattern3", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("attr3")
            .build()

        assertEquals(request1.hashCode(), request2.hashCode())

        // The test must be updated if fields are added
        assertFieldCountEquals(8, DiscoveryRequest::class.java)
    }

    @Test
    fun testToString() {
        val request = DiscoveryRequest.Builder("_ipps._tcp")
            .setSubtype("_xyz")
            .setNetwork(Network(1))
            .setFlags(FLAG_NO_PICKER)
            .setServiceNameFilter(PatternMatcher("namepattern", PATTERN_PREFIX))
            .setAttributeFilters(mapOf(
                "attr1" to PatternMatcher("attrpattern1", PATTERN_SIMPLE_GLOB),
                "attr2" to PatternMatcher("attrpattern2", PATTERN_ADVANCED_GLOB)
            ))
            .setDisplayNameAttribute("displaynameattr")
            .build()
        val str = request.toString()
        assertContains(str, request.serviceType)
        assertContains(str, request.subtype!!)
        assertContains(str, request.network.toString())
        assertContains(str, "0x" + java.lang.Long.toHexString(request.flags))
        assertContains(str, "namepattern")
        assertContains(str, "attrpattern1")
        assertContains(str, "attrpattern2")
        assertContains(str, request.displayNameAttribute!!)

        // The test must be updated if fields are added
        assertFieldCountEquals(8, DiscoveryRequest::class.java)
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
