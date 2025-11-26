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

import android.net.DnsResolver.CLASS_IN

import com.android.net.module.util.DnsPacketUtils.DnsRecordParser

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility class for testing out DNS SVCB & HTTPS response related classes.
 */
class DnsSvcbTestUtils {

    data class FakeDnsRecord(
        val usesNameCompression: Boolean = false,
        val recordName: String = "dns.com",
        val recordType: Short = DnsHttpsPacket.TYPE_HTTPS.toShort(),
        val recordClass: Short = CLASS_IN.toShort(),
        val recordTtl: Int = 10,
        var dataLength: Int = 0,
        val targetName: String = "",
        val svcPriority: Short = 1,
        val svcParams: List<ByteArray> = mutableListOf<ByteArray>()
    )

    companion object {
        private val TEST_TRANSACTION_ID: Short = 0x4321
        private val TEST_DNS_RESPONSE_HEADER_FLAG =  byteArrayOf(0x81.toByte(), 0x00)

        // A common DNS SVCB question section with Name = "_dns.resolver.arpa".
        @JvmField
        val TEST_DNS_SVCB_QUESTION_SECTION =
            byteArrayOf(0x04) + "_dns".toByteArray() + 0x08.toByte() + "resolver".toByteArray() +
                0x04.toByte() + "arpa".toByteArray() +
                    byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x01)

        @JvmField
        val TEST_MALFORMED_SVC_PARAM = byteArrayOf(0x00, 0x01, 0x02)

        // mandatory=ipv4hint,alpn,key333
        @JvmField
        val TEST_SVC_PARAM_MANDATORY = byteArrayOf(
            0x00, 0x00, 0x00, 0x06, 0x00, 0x04, 0x00, 0x01, 0x01, 0x4d)

        // alpn=doq
        @JvmField
        val TEST_SVC_PARAM_ALPN_DOQ = byteArrayOf(0x00, 0x01, 0x00, 0x04, 0x03) +
            "doq".toByteArray()

        // alpn=h2,http/1.1
        @JvmField
        val TEST_SVC_PARAM_ALPN_HTTPS = byteArrayOf(0x00, 0x01, 0x00, 0x0c, 0x02) +
            "h2".toByteArray() + 0x08.toByte() + "http/1.1".toByteArray()

        // alpn=h3,h2
        val TEST_SVC_PARAM_ALPN_QUIC =
            byteArrayOf(0x00, 0x01, 0x00, 0x06, 0x02, 0x68, 0x33, 0x02, 0x68, 0x32)

        // no-default-alpn
        @JvmField
        val TEST_SVC_PARAM_NO_DEFAULT_ALPN = byteArrayOf(0x00, 0x02, 0x00, 0x00)

        // port=5353
        @JvmField
        val TEST_SVC_PARAM_PORT = byteArrayOf(0x00, 0x03, 0x00, 0x02, 0x14, 0xe9.toByte())

        // ipv4hint=1.2.3.4,6.7.8.9
        @JvmField
        val TEST_SVC_PARAM_MULTIPLE_IPV4HINT = byteArrayOf(
            0x00, 0x04, 0x00, 0x08, 0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x08, 0x09)

        // ipv4hint=104.18.10.118,104.18.11.118
        @JvmField
        val TEST_SVC_PARAM_REAL_IPV4HINT = byteArrayOf(
            0x00, 0x04, 0x00, 0x08, 0x68, 0x12, 0x0a, 0x76, 0x68, 0x12, 0x0b, 0x76)

        // ipv4hint=4.3.2.1
        @JvmField
        val TEST_SVC_PARAM_SINGLE_IPV4HINT = byteArrayOf(
            0x00, 0x04, 0x00, 0x04, 0x04, 0x03, 0x02, 0x01)

        // real-life ECH config list returned by cloudflare-ech.com
        @JvmField
        val TEST_SVC_PARAM_ECH = byteArrayOf(
            0x00, 0x05, 0x00, 0x47, 0x00, 0x45, 0xfe.toByte(), 0x0d, 0x00, 0x41, 0xf7.toByte(),
            0x00, 0x20, 0x00, 0x20, 0xfd.toByte(), 0x4b, 0x91.toByte(), 0x2a, 0xf0.toByte(),
            0xdc.toByte(), 0xba.toByte(), 0x52, 0xb5.toByte(), 0x98.toByte(), 0x8b.toByte(),
            0xea.toByte(), 0xb2.toByte(), 0x50, 0x7b, 0xfc.toByte(), 0x4f, 0x24, 0xea.toByte(),
            0xdb.toByte(), 0xf9.toByte(), 0x54, 0x3a, 0xa3.toByte(), 0x71, 0x34, 0xdd.toByte(),
            0xff.toByte(), 0x40, 0xcc.toByte(), 0xa8.toByte(), 0x68, 0x00, 0x04, 0x00, 0x01, 0x00,
            0x01, 0x00, 0x12, 0x63, 0x6c, 0x6f, 0x75, 0x64, 0x66, 0x6c, 0x61, 0x72, 0x65, 0x2d,
            0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00
        )

        @JvmField
        val TEST_ECH_CONFIG_LIST = TEST_SVC_PARAM_ECH.drop(4).toByteArray()

        // ipv6hint=2001:db8::1
        @JvmField
        val TEST_SVC_PARAM_SINGLE_IPV6HINT = byteArrayOf(
            0x00, 0x06, 0x00, 0x10, 0x20, 0x01, 0x0d, 0xb8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)

        // ipv6hint=2606:4700::6812:a76,2606:4700::6812:b76
        @JvmField
        val TEST_SVC_PARAM_MULTIPLE_IPV6HINT = byteArrayOf(
            0x00, 0x06, 0x00, 0x20, 0x26, 0x06, 0x47, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x68, 0x12, 0x0a, 0x76, 0x26, 0x06, 0x47, 0x00, 0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00, 0x68, 0x12, 0x0b, 0x76)

        // dohpath=/some-path{?dns}
        @JvmField
        val TEST_SVC_PARAM_DOHPATH = byteArrayOf(0x00, 0x07, 0x00, 0x10) +
            "/some-path{?dns}".toByteArray()

        // key12345=1A2B0C
        @JvmField
        val TEST_SVC_PARAM_GENERIC_WITH_VALUE = byteArrayOf(
            0x30, 0x39, 0x00, 0x03, 0x1a, 0x2b, 0x0c)

        // key12346
        @JvmField
        val TEST_SVC_PARAM_GENERIC_WITHOUT_VALUE = byteArrayOf(0x30, 0x3a, 0x00, 0x00)

        @JvmField
        // This is the exact HTTPS rawQuery response for cloudflare-ech.com.
        val VALID_SINGLE_HTTPS_RECORD_RESPONSE = HexDump.hexStringToByteArray(
        """
        |da68818000010001000000000e636c6f7564666c6172652d65636803636f6d00004100
        |01c00c004100010000012c0088000100000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041F700200020FD4B912AF0DCBA52B5988BEAB2507BFC4F
        |24EADBF9543AA37134DDFF40CCA8680004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        """.trimMargin().replace("\n", ""))

        @JvmField
        // Get only the answer section of the above HTTPS record.
        val VALID_SINGLE_HTTPS_RECORD = VALID_SINGLE_HTTPS_RECORD_RESPONSE.drop(36).toByteArray()

        @JvmField
        // This is a modified rawQuery cloudflare-ech.com response to have three HTTPS records of
        // different priorities.
        val VALID_MULTIPLE_HTTPS_RECORDS_RESPONSE = HexDump.hexStringToByteArray(
        """
        |da68818000010003000000000e636c6f7564666c6172652d65636803636f6d0000410001
        |c00c004100010000012c0088000100000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041F700200020FD4B912AF0DCBA52B5988BEAB2507BFC4F
        |24EADBF9543AA37134DDFF40CCA8680004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        |c00c004100010000012c0088000200000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041F700200020FD4B912AF0DCBA52B5988BEAB2507BFC4F
        |24EADBF9543AA37134DDFF40CCA8680004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        |c00c004100010000012c0088000300000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041F700200020FD4B912AF0DCBA52B5988BEAB2507BFC4F
        |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        """.trimMargin().replace("\n", ""))

        @JvmField
        // This is a modified rawQuery cloudflare-ech.com response to have a question count of 2.
        val INVALID_QUESTION_COUNT_RESPONSE = HexDump.hexStringToByteArray(
        """
        |da68818000020001000000000e636c6f7564666c6172652d65636803636f6d00004100
        |01c00c004100010000012c0088000100000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
        |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        """.trimMargin().replace("\n", ""))

        @JvmField
        // This is the exact SVCB rawQuery response for cloudflare-ech.com.
        val SVCB_QUERY_TYPE_RESPONSE = HexDump.hexStringToByteArray(
        """
        |8b8c818000010000000100000e636c6f7564666c6172652d65636803636f6d00004000
        |01c00c0006000100000708002f0466726564026e730a636c6f7564666c617265c01b03
        |646e73c0388e3df333000027100000096000093a8000000708
        """.trimMargin().replace("\n", ""))

        @JvmField
        // This is a modified rawQuery cloudflare-ech.com response to have an invalid response type
        // of SVCB (64) instead of HTTPS (65).
        val NO_HTTPS_TYPE_RESPONSE = HexDump.hexStringToByteArray(
        """
        |da68818000010001000000000e636c6f7564666c6172652d65636803636f6d00004100
        |01c00c004000010000012c0088000100000100060268330268320004000868120a7668
        |120b76000500470045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
        |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
        |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
        |000000000068120b76
        """.trimMargin().replace("\n", ""))

        @JvmField
        val NO_RDATA_HTTPS_RECORD = HexDump.hexStringToByteArray("c00c000410010000012c0000")

        @JvmField
        val MALFORMED_RDATA_HTTPS_RECORD = HexDump.hexStringToByteArray(
            "03646E7303636F6D00004100010000000A011C0001FF00")

        @JvmStatic
        fun makeDnsResponseHeaderAsByteArray(qdcount: Int, ancount: Int, nscount: Int,
            arcount: Int): ByteArray {
            val buffer = ByteBuffer.wrap(ByteArray(12))
            with(buffer) {
                putShort(TEST_TRANSACTION_ID)
                put(TEST_DNS_RESPONSE_HEADER_FLAG)
                putShort(qdcount.toShort())
                putShort(ancount.toShort())
                putShort(nscount.toShort())
                putShort(arcount.toShort())
            }
            return buffer.array()
        }

        @Throws(IOException::class)
        @JvmStatic
        fun makeDnsSvcbRecordFromByteArray(data: ByteArray): DnsSvcbRecord {
            return DnsSvcbRecord(DnsPacket.ANSECTION, ByteBuffer.wrap(data))
        }

        /** Converts a Short to a byte array in big endian. */
        @JvmStatic
        fun shortToByteArray(value: Short): ByteArray {
            val buffer = ByteBuffer.allocate(2)
            buffer.order(ByteOrder.BIG_ENDIAN)
            buffer.putShort(value)
            return buffer.array()
        }

        @JvmStatic
        fun getRemainingByteArray(buffer: ByteBuffer): ByteArray {
            val out = ByteArray(buffer.remaining())
            buffer.get(out)
            return out
        }

        /**
         * Extension function to convert a [FakeDnsRecord] into a byte buffer to be parsed.
         */
        @JvmStatic
        fun toByteBuffer(record: FakeDnsRecord): ByteBuffer {
            with (record) {
                val name = if (usesNameCompression) byteArrayOf(0xc0.toByte(), 0x0c)
                    else DnsRecordParser.domainNameToLabels(recordName)

                var recordAsBytes = name + shortToByteArray(recordType) +
                    shortToByteArray(recordClass) +
                    HexDump.toByteArray(recordTtl)

                // Add the length of the SvcPriority
                dataLength += Short.SIZE_BYTES

                // Calculate the length of all the SvcParams
                dataLength += svcParams.sumBy { it.size }

                // Cover RFC 9460 2.5 special case where an empty target name has special handling
                if (targetName.isEmpty()) {
                    dataLength += 1 // Add 1 for the zero-length label
                    recordAsBytes += shortToByteArray(dataLength.toShort()) +
                        shortToByteArray(svcPriority) + 0x00.toByte()
                } else {
                    val targetNameLabels = DnsRecordParser.domainNameToLabels(targetName)
                    dataLength += targetNameLabels.size
                    recordAsBytes += shortToByteArray(dataLength.toShort()) +
                        shortToByteArray(svcPriority) + targetNameLabels
                }

                svcParams.forEach {
                    recordAsBytes += it
                }

                return ByteBuffer.wrap(recordAsBytes)
            }

        }
    }
}
