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

package com.android.net.module.util

import com.android.net.module.util.DnsPacket.ParseException
import com.android.net.module.util.DnsSvcbTestUtils.FakeDnsRecord

import android.net.DnsResolver.TYPE_A
import android.net.InetAddresses
import android.util.Log

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
/**
 * Tests for [DnsHttpsRecord].
 *
 * Build, install and run with:
 * atest NetworkStaticLibTests:com.android.net.moduletests.util.DnsHttpsRecordTest
 */
class DnsHttpsRecordTest {

    @Test
    fun constructor_whenInvalidType_throwsIllegalStateException() {
        val invalidTypeRecord = FakeDnsRecord(recordType = TYPE_A.toShort())

        assertFailsWith<IllegalStateException>("incorrect nsType: 1") {
            DnsHttpsRecord(DnsPacket.ANSECTION, DnsSvcbTestUtils.toByteBuffer(invalidTypeRecord))
        }
    }

    @Test
    fun constructor_whenInvalidNsClass_throwsParseException() {
        val invalidNsClassRecord = FakeDnsRecord(recordClass = 2)

        assertFailsWith<ParseException>("incorrect nsClass: 2") {
            DnsHttpsRecord(DnsPacket.ANSECTION, DnsSvcbTestUtils.toByteBuffer(invalidNsClassRecord))
        }
    }

    @Test
    fun constructor_whenEmptyData_throwsParseException() {
        val emptyDataRecord = ByteBuffer.wrap(DnsSvcbTestUtils.NO_RDATA_HTTPS_RECORD)

        assertFailsWith<ParseException>("HTTPS rdata is empty") {
            DnsHttpsRecord(DnsPacket.ANSECTION, emptyDataRecord)
        }
    }

    @Test
    fun constructor_whenMalformedRdata_throwsBufferUnderflowException() {
        val malformedRdataRecord = ByteBuffer.wrap(DnsSvcbTestUtils.MALFORMED_RDATA_HTTPS_RECORD)

        assertFailsWith<BufferUnderflowException> {
            DnsHttpsRecord(DnsPacket.ANSECTION, malformedRdataRecord)
        }
    }

    @Test
    fun constructor_whenMalformedSvcParam_throwsParseException() {
        val invalidRdataLengthRecord = FakeDnsRecord(
            svcParams = listOf(DnsSvcbTestUtils.TEST_MALFORMED_SVC_PARAM))

        assertFailsWith<ParseException>("Malformed packet") {
            DnsHttpsRecord(
                DnsPacket.ANSECTION,
                DnsSvcbTestUtils.toByteBuffer(invalidRdataLengthRecord)
            )
        }
    }

    @Test
    fun constructor_whenDuplicateSvcParamKey_throwsParseException() {
        val recordBytes = DnsSvcbTestUtils.toByteBuffer(
            FakeDnsRecord(
                svcParams = listOf(
                    DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_HTTPS,
                    DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_DOQ,
                )))

        assertFailsWith<ParseException>("Invalid DnsHttpsRecord: key 1 is repeated") {
            DnsHttpsRecord(DnsPacket.ANSECTION, recordBytes)
        }
    }

    // SvcParamKeys must appear in strict increasing numeric order.
    // See RFC 9460 section 2.2-5 for more details.
    @Test
    fun constructor_whenSvcParamKeyNotIncreasing_throwsParseException() {
        val recordBytes = DnsSvcbTestUtils.toByteBuffer(
            FakeDnsRecord(
                svcParams = listOf(
                    DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV4HINT,
                    DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_HTTPS,
                )))

        assertFailsWith<ParseException>("Invalid DnsHttpsRecord: keys must be in increasing order")
            { DnsHttpsRecord(DnsPacket.ANSECTION, recordBytes) }
    }

    @Test
    fun getPriority_returnsCorrectValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(FakeDnsRecord(svcPriority = 1234))
        )

        assertEquals(1234, record.priority)
    }

    // Special scenario, see RFC 9460 2.5
    @Test
    fun getTargetName_whenEmptyString_returnsPeriod() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(FakeDnsRecord(targetName = ""))
        )

        assertEquals(DnsHttpsRecord.ZERO_LENGTH_TARGET_NAME, record.targetName)
    }

    @Test
    fun getTargetName_whenNonEmpty_returnsCorrectValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(FakeDnsRecord(targetName = "www.example.com"))
        )

        assertEquals("www.example.com", record.targetName)
    }

    @Test
    fun getMandatory_returnsEmptyList() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))
        // TODO(b/454544870): update this test once SvcParamMandatory is fixed

        assertTrue(record.mandatory.isEmpty())
    }

    @Test
    fun getAlpn_whenAbsent_returnsEmptyList() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertTrue(record.alpn.isEmpty())
    }

    @Test
    fun getAlpn_whenSingleAlpn_returnsSingleAlpn() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_DOQ))))

        assertEquals(listOf("doq"), record.alpn)
    }

    @Test
    fun getAlpn_whenMultipleAlpns_returnsMultipleAlpns() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_HTTPS))))

        assertEquals(listOf("h2", "http/1.1"), record.alpn)
    }

    @Test
    fun getNoDefaultAlpn_whenAbsent_returnsFalse() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertFalse(record.isNoDefaultAlpn())
    }

    @Test
    fun getNoDefaultAlpn_whenPresent_returnsTrue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_NO_DEFAULT_ALPN))))

        assertTrue(record.isNoDefaultAlpn())
    }

    @Test
    fun getPort_whenAbsent_returnsDefaultValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertEquals(DnsHttpsRecord.DEFAULT_PORT_VALUE, record.port)
    }

    @Test
    fun getPort_whenPresent_returnsCorrectValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_PORT))))

        assertEquals(5353, record.port)
    }

    @Test
    fun getIpv4Hint_whenEmpty_returnsEmptyList() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertTrue(record.ipv4Hint.isEmpty())
    }

    @Test
    fun getIpv4Hint_whenSingleIpv4Hint_returnsSingleIpv4Hint() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(
                    svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_SINGLE_IPV4HINT))))

        assertEquals(listOf(InetAddresses.parseNumericAddress("4.3.2.1")), record.ipv4Hint)
    }

    @Test
    fun getIpv4Hint_whenMultipleIpv4Hints_returnsMultipleIpv4Hints() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(
                    svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV4HINT))))

        val expectedIpv4Hints = listOf(
            InetAddresses.parseNumericAddress("1.2.3.4"),
            InetAddresses.parseNumericAddress("6.7.8.9")
        )
        assertEquals(expectedIpv4Hints, record.ipv4Hint)
    }

    @Test
    fun getEchConfigList_whenAbsent_returnsNull() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertNull(record.echConfigList)
    }

    @Test
    fun getEchConfigList_whenPresent_returnsCorrectValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_ECH))))

        assertContentEquals(DnsSvcbTestUtils.TEST_ECH_CONFIG_LIST, record.echConfigList)
    }

    @Test
    fun getIpv6Hint_whenEmpty_returnsEmptyList() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertTrue(record.ipv6Hint.isEmpty())
    }

    @Test
    fun getIpv6Hint_whenSingleIpv6Hint_returnsSingleIpv6Hint() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(
                    svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_SINGLE_IPV6HINT))))

        assertEquals(listOf(InetAddresses.parseNumericAddress("2001:db8::1")), record.ipv6Hint)
    }

    @Test
    fun getIpv6Hint_whenMultipleIpv6Hints_returnsMultipleIpv6Hints() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(
                    svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV6HINT))))

        val expectedIpv6Hints = listOf(
            InetAddresses.parseNumericAddress("2606:4700::6812:a76"),
            InetAddresses.parseNumericAddress("2606:4700::6812:b76")
        )
        assertEquals(expectedIpv6Hints, record.ipv6Hint)
    }

    @Test
    fun getDohPath_whenAbsent_returnsNull() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY))))

        assertNull(record.dohPath)
    }

    @Test
    fun getDohPath_whenPresent_returnsCorrectValue() {
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = listOf(DnsSvcbTestUtils.TEST_SVC_PARAM_DOHPATH))))

        assertEquals("/some-path{?dns}", record.dohPath)
    }

    @Test
    fun whenMultipleSvcParams_returnsCorrectValues() {
        val svcParams = listOf(
            DnsSvcbTestUtils.TEST_SVC_PARAM_MANDATORY,
            DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_HTTPS,
            DnsSvcbTestUtils.TEST_SVC_PARAM_NO_DEFAULT_ALPN,
            DnsSvcbTestUtils.TEST_SVC_PARAM_PORT,
            DnsSvcbTestUtils.TEST_SVC_PARAM_SINGLE_IPV4HINT,
            DnsSvcbTestUtils.TEST_SVC_PARAM_ECH,
            DnsSvcbTestUtils.TEST_SVC_PARAM_SINGLE_IPV6HINT,
            DnsSvcbTestUtils.TEST_SVC_PARAM_DOHPATH,
        )
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(svcParams = svcParams)))

        assertTrue(record.mandatory.isEmpty())
        assertEquals(listOf("h2", "http/1.1"), record.alpn)
        assertTrue(record.isNoDefaultAlpn())
        assertEquals(5353, record.port)
        assertEquals(listOf(InetAddresses.parseNumericAddress("4.3.2.1")), record.ipv4Hint)
        assertContentEquals(DnsSvcbTestUtils.TEST_ECH_CONFIG_LIST, record.echConfigList)
        assertEquals(listOf(InetAddresses.parseNumericAddress("2001:db8::1")), record.ipv6Hint)
        assertEquals("/some-path{?dns}", record.dohPath)
    }

    @Test
    fun whenTargetNameSpecified_andMultipleSvcParams_returnsCorrectValues() {
        val svcParams = listOf(
            DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_HTTPS,
            DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV4HINT,
            DnsSvcbTestUtils.TEST_SVC_PARAM_ECH,
            DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV6HINT
        )
        val record = DnsHttpsRecord(
            DnsPacket.ANSECTION,
            DnsSvcbTestUtils.toByteBuffer(
                FakeDnsRecord(
                    svcParams = svcParams,
                    targetName = "www.example.com",
                )))

        assertEquals("www.example.com", record.targetName)
        assertEquals(listOf("h2", "http/1.1"), record.alpn)
        assertEquals(
            listOf(
                InetAddresses.parseNumericAddress("1.2.3.4"),
                InetAddresses.parseNumericAddress("6.7.8.9")
            ),
            record.ipv4Hint
        )
        assertContentEquals(DnsSvcbTestUtils.TEST_ECH_CONFIG_LIST, record.echConfigList)
        assertEquals(
            listOf(
                InetAddresses.parseNumericAddress("2606:4700::6812:a76"),
                InetAddresses.parseNumericAddress("2606:4700::6812:b76")
            ),
            record.ipv6Hint
        )
    }

    // Try to construct a FakeDnsRecord and convert it to a ByteBuffer.
    // Check that it matches the actual bytes of a HTTPS packet from cloudflare-ech.com.
    @Test
    fun fakeDnsRecordToByteBuffer_returnsExpectedBytes() {
        val svcParams = listOf(
            DnsSvcbTestUtils.TEST_SVC_PARAM_ALPN_QUIC,
            DnsSvcbTestUtils.TEST_SVC_PARAM_REAL_IPV4HINT,
            DnsSvcbTestUtils.TEST_SVC_PARAM_ECH,
            DnsSvcbTestUtils.TEST_SVC_PARAM_MULTIPLE_IPV6HINT,
        )

        val record = DnsSvcbTestUtils.toByteBuffer(FakeDnsRecord(
            usesNameCompression = true,
            recordName = "cloudflare-ech.com",
            recordTtl = 300,
            targetName = "",
            svcParams = svcParams,
        ))

        assertContentEquals(DnsSvcbTestUtils.VALID_SINGLE_HTTPS_RECORD, record.array())
    }
}
