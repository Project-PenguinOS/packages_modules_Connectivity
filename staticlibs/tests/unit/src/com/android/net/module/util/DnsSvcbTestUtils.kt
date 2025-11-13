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

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility class for testing out DNS SVCB & HTTPS response related classes.
 */
class DnsSvcbTestUtils {

    companion object {
        private val TEST_TRANSACTION_ID: Short = 0x4321
        private val TEST_DNS_RESPONSE_HEADER_FLAG =  byteArrayOf(0x81.toByte(), 0x00)

        // A common DNS SVCB question section with Name = "_dns.resolver.arpa".
        @JvmField
        val TEST_DNS_SVCB_QUESTION_SECTION =
            byteArrayOf(0x04) + "_dns".toByteArray() + 0x08.toByte() + "resolver".toByteArray() +
                0x04.toByte() + "arpa".toByteArray() +
                    byteArrayOf(0x00, 0x00, 0x40, 0x00, 0x01)

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

        // no-default-alpn
        @JvmField
        val TEST_SVC_PARAM_NO_DEFAULT_ALPN = byteArrayOf(0x00, 0x02, 0x00, 0x00)

        // port=5353
        @JvmField
        val TEST_SVC_PARAM_PORT = byteArrayOf(0x00, 0x03, 0x00, 0x02, 0x14, 0xe9.toByte())

        // ipv4hint=1.2.3.4,6.7.8.9
        @JvmField
        val TEST_SVC_PARAM_IPV4HINT_1 = byteArrayOf(
            0x00, 0x04, 0x00, 0x08, 0x01, 0x02, 0x03, 0x04, 0x06, 0x07, 0x08, 0x09)

        // ipv4hint=4.3.2.1
        @JvmField
        val TEST_SVC_PARAM_IPV4HINT_2 = byteArrayOf(
            0x00, 0x04, 0x00, 0x04, 0x04, 0x03, 0x02, 0x01)

        // ech=aBcDe
        @JvmField
        val TEST_SVC_PARAM_ECH = byteArrayOf(0x00, 0x05, 0x00, 0x05) + "aBcDe".toByteArray()

        // ipv6hint=2001:db8::1
        @JvmField
        val TEST_SVC_PARAM_IPV6HINT = byteArrayOf(
            0x00, 0x06, 0x00, 0x10, 0x20, 0x01, 0x0d, 0xb8.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x01)

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
    }
}
