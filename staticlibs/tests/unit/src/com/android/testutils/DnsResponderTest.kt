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

package com.android.testutils

import android.net.InetAddresses.parseNumericAddress
import android.net.MacAddress
import android.os.Handler
import android.os.HandlerThread
import android.system.Os
import android.system.OsConstants.AF_UNIX
import android.system.OsConstants.F_GETFL
import android.system.OsConstants.F_SETFL
import android.system.OsConstants.IPPROTO_UDP
import android.system.OsConstants.O_NONBLOCK
import android.system.OsConstants.SOCK_DGRAM
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.DnsPacket.DnsHeader
import com.android.net.module.util.DnsPacket.DnsRecord
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6
import com.android.net.module.util.NetworkStackConstants.IPV4_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN
import com.android.net.module.util.NetworkStackConstants.IPV4_PROTOCOL_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV6_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_PROTOCOL_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV6_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import java.io.FileDescriptor
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import libcore.io.IoUtils
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DnsResponderTest {
    private val TEST_HOST = "www.google.com"
    private val TEST_IPV4_ADDR = parseNumericAddress("192.0.2.1")
    private val TEST_IPV6_ADDR = parseNumericAddress("2001:db8::1")
    private val TYPE_A = 1
    private val TYPE_AAAA = 28
    private val CLASS_IN = 1

    private val TEST_SRC_MAC = MacAddress.fromString("11:22:33:44:55:66")
    private val TEST_DST_MAC = MacAddress.fromString("66:55:44:33:22:11")
    private val TEST_SRC_IPV4: Inet4Address = parseNumericAddress("192.0.2.100") as Inet4Address
    private val TEST_DST_IPV4: Inet4Address = parseNumericAddress("192.0.2.200") as Inet4Address
    private val TEST_SRC_IPV6: Inet6Address = parseNumericAddress("2001:db8::100") as Inet6Address
    private val TEST_DST_IPV6: Inet6Address = parseNumericAddress("2001:db8::200") as Inet6Address

    private lateinit var handlerThread: HandlerThread
    private lateinit var readerFd: FileDescriptor
    private lateinit var senderFd: FileDescriptor
    private lateinit var packetReader: PollPacketReader
    private lateinit var dnsResponder: DnsResponder

    @Before
    fun setUp() {
        handlerThread = HandlerThread(DnsResponderTest::class.java.simpleName)
        handlerThread.start()

        readerFd = FileDescriptor()
        senderFd = FileDescriptor()
        Os.socketpair(AF_UNIX, SOCK_DGRAM, 0, readerFd, senderFd)
        // make readerFd non-blocking
        val flags = Os.fcntlInt(readerFd, F_GETFL, 0)
        Os.fcntlInt(readerFd, F_SETFL, flags or O_NONBLOCK)

        packetReader = PollPacketReader(Handler(handlerThread.looper), readerFd, 1500)
        packetReader.startAsyncForTest()

        val answers = mapOf(TEST_HOST to listOf(TEST_IPV4_ADDR, TEST_IPV6_ADDR))
        dnsResponder = DnsResponder(packetReader, answers)
        dnsResponder.start()
    }

    @After
    fun tearDown() {
        dnsResponder.stop()
        packetReader.handler.post { packetReader.stop() }
        handlerThread.quitSafely()
        handlerThread.join()
        IoUtils.closeQuietly(senderFd)
    }

    private fun buildDnsPacket(
        qname: String,
        qtype: Int,
        isIpv4: Boolean = true,
    ): ByteArray {
        val ether = EtherPkt(TEST_DST_MAC, TEST_SRC_MAC)
        val ip = if (isIpv4) {
            Ip4Pkt(TEST_SRC_IPV4, TEST_DST_IPV4)
        } else {
            Ip6Pkt(TEST_SRC_IPV6, TEST_DST_IPV6)
        }
        val udp = UdpPkt(12345, 53)

        val flags = 0x0100
        val header = DnsHeader(1234, flags, 1, 0)
        val record = DnsRecord.makeQuestion(qname, qtype, CLASS_IN)
        val dnsPacket = DnsPacket(header, listOf(record), listOf())

        val data = DataPkt(dnsPacket.bytes)
        return (ether / ip / udp / data).build()
    }

    private fun readResponse(): ByteArray {
        val buffer = ByteArray(1500)
        val readLen = Os.read(senderFd, buffer, 0, buffer.size)
        assertTrue(readLen > 0, "No response received")
        return buffer.copyOf(readLen)
    }

    @Test
    fun testIpv4DnsReply() {
        val qPacket = buildDnsPacket(TEST_HOST, TYPE_A, isIpv4 = true)

        // Send packet
        Os.write(senderFd, qPacket, 0, qPacket.size)

        // Read response
        val responseBytes = readResponse()
        val buffer = ByteBuffer.wrap(responseBytes)

        // Parse and verify Ethernet header (14 bytes)
        // Dst Mac: 0-5. Should be source of request
        val dstMacBytes = ByteArray(6)
        buffer.get(dstMacBytes)
        assertEquals(TEST_SRC_MAC, MacAddress.fromBytes(dstMacBytes))

        // Src Mac: 6-11. Should be dest of request
        val srcMacBytes = ByteArray(6)
        buffer.get(srcMacBytes)
        assertEquals(TEST_DST_MAC, MacAddress.fromBytes(srcMacBytes))

        // EtherType: 12-13. Should be IPv4 (0x0800)
        assertEquals(ETHER_TYPE_IPV4.toShort(), buffer.short)

        // Parse and verify IPv4 header (20 bytes)
        val ipStart = ETHER_HEADER_LEN
        // Protocol at offset 9 (14+9 = 23). Should be UDP (17)
        assertEquals(IPPROTO_UDP.toByte(), responseBytes[ipStart + IPV4_PROTOCOL_OFFSET])

        // Src IP at offset 12 (14+12 = 26). Should be dst of request
        val srcIpBytes = responseBytes.copyOfRange(
            ipStart + IPV4_SRC_ADDR_OFFSET,
            ipStart + IPV4_SRC_ADDR_OFFSET + 4
        )
        assertEquals(TEST_DST_IPV4, InetAddress.getByAddress(srcIpBytes))

        // Dst IP at offset 16 (14+16 = 30). Should be src of request
        val dstIpBytes = responseBytes.copyOfRange(
            ipStart + IPV4_DST_ADDR_OFFSET,
            ipStart + IPV4_DST_ADDR_OFFSET + 4
        )
        assertEquals(TEST_SRC_IPV4, InetAddress.getByAddress(dstIpBytes))

        // Parse and verify UDP header (8 bytes)
        val udpStart = ETHER_HEADER_LEN + IPV4_HEADER_MIN_LEN
        buffer.position(udpStart)
        // Src Port at offset 0. Should be dst of request: 53
        assertEquals(53.toShort(), buffer.short)
        // Dst Port at offset 2. Should be src of request: 12345
        assertEquals(12345.toShort(), buffer.short)

        // Parse response
        // Skip Ether/IP/UDP headers to get to DNS
        // 14 (Ether) + 20 (IP) + 8 (UDP) = 42 bytes for IPv4
        val dnsBytes = responseBytes.copyOfRange(
            ETHER_HEADER_LEN + IPV4_HEADER_MIN_LEN + UDP_HEADER_LEN,
            responseBytes.size
        )
        val dnsPacket = DnsPacket(dnsBytes)

        assertTrue(dnsPacket.header.isResponse)
        assertEquals(1, dnsPacket.header.getRecordCount(DnsPacket.ANSECTION))
        val answer = dnsPacket.getRecords(DnsPacket.ANSECTION)[0]
        // Verify answer IP
        assertEquals(TEST_IPV4_ADDR, InetAddress.getByAddress(answer.rr))

        // Verify Flags: 0x8000 (QR) | 0x0100 (RD) = 0x8100
        assertEquals(0x8100, dnsPacket.header.flags)
    }

    @Test
    fun testIpv6DnsReply() {
        val qPacket = buildDnsPacket(TEST_HOST, TYPE_AAAA, isIpv4 = false)

        Os.write(senderFd, qPacket, 0, qPacket.size)

        val responseBytes = readResponse()
        val buffer = ByteBuffer.wrap(responseBytes)

        // Parse and verify Ethernet header (14 bytes)
        // Dst Mac: 0-5. Should be source of request
        val dstMacBytes = ByteArray(6)
        buffer.get(dstMacBytes)
        assertEquals(TEST_SRC_MAC, MacAddress.fromBytes(dstMacBytes))

        // Src Mac: 6-11. Should be dest of request
        val srcMacBytes = ByteArray(6)
        buffer.get(srcMacBytes)
        assertEquals(TEST_DST_MAC, MacAddress.fromBytes(srcMacBytes))

        // EtherType: 12-13. Should be IPv6 (0x86DD)
        assertEquals(ETHER_TYPE_IPV6.toShort(), buffer.short)

        // Parse and verify IPv6 header (40 bytes)
        val ipStart = ETHER_HEADER_LEN
        // Next Header at offset 6 (14+6 = 20). Should be UDP (17)
        assertEquals(IPPROTO_UDP.toByte(), responseBytes[ipStart + IPV6_PROTOCOL_OFFSET])

        // Src IP at offset 8 (14+8 = 22). Should be dst of request
        val srcIpBytes = responseBytes.copyOfRange(
            ipStart + IPV6_SRC_ADDR_OFFSET,
            ipStart + IPV6_SRC_ADDR_OFFSET + 16
        )
        assertEquals(TEST_DST_IPV6, InetAddress.getByAddress(srcIpBytes))

        // Dst IP at offset 24 (14+24 = 38). Should be src of request
        val dstIpBytes = responseBytes.copyOfRange(
            ipStart + IPV6_DST_ADDR_OFFSET,
            ipStart + IPV6_DST_ADDR_OFFSET + 16
        )
        assertEquals(TEST_SRC_IPV6, InetAddress.getByAddress(dstIpBytes))

        // Parse and verify UDP header (8 bytes)
        val udpStart = ETHER_HEADER_LEN + IPV6_HEADER_LEN
        buffer.position(udpStart)
        // Src Port at offset 0. Should be dst of request: 53
        assertEquals(53.toShort(), buffer.short)
        // Dst Port at offset 2. Should be src of request: 12345
        assertEquals(12345.toShort(), buffer.short)

        // IPv6 header is 40 bytes. Ether 14. UDP 8. Total 62.
        val dnsBytes = responseBytes.copyOfRange(
            ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN,
            responseBytes.size
        )
        val dnsPacket = DnsPacket(dnsBytes)

        assertTrue(dnsPacket.header.isResponse)
        assertEquals(1, dnsPacket.header.getRecordCount(DnsPacket.ANSECTION))
        val answer = dnsPacket.getRecords(DnsPacket.ANSECTION)[0]
        assertEquals(TEST_IPV6_ADDR, InetAddress.getByAddress(answer.rr))
        assertEquals(0x8100, dnsPacket.header.flags)
    }
}
