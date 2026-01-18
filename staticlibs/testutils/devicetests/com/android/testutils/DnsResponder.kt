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

import android.net.MacAddress
import android.util.Log
import com.android.net.module.util.DnsPacket
import com.android.net.module.util.DnsPacket.DnsHeader
import com.android.net.module.util.DnsPacket.QDSECTION
import com.android.net.module.util.HexDump
import com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV4
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_IPV6
import com.android.net.module.util.NetworkStackConstants.ETHER_TYPE_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_ADDR_LEN
import com.android.net.module.util.NetworkStackConstants.IPV4_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV4_HEADER_MIN_LEN
import com.android.net.module.util.NetworkStackConstants.IPV4_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.IPV6_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.UDP_DSTPORT_OFFSET
import com.android.net.module.util.NetworkStackConstants.UDP_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.UDP_SRCPORT_OFFSET
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.nio.ByteBuffer

/**
 * A class that can be used to reply to IPv4 and IPv6 DNS packets on a [PollPacketReader].
 */
class DnsResponder(
    reader: PollPacketReader,
    answers: Map<String, List<InetAddress>>,
    name: String = DnsResponder::class.java.simpleName
) : PacketResponder(reader, DnsPacketFilter(), name) {

    private val TAG = DnsResponder::class.java.simpleName
    private val ansProvider = DnsAnswerProvider()

    private val DNS_HEADER_FLAGS_RESPONSE = 0x8000
    private val DNS_HEADER_FLAGS_RECURSION_DESIRED = 0x0100

    // Offsets for IPv4 UDP packets
    private val IPV4_SRC_OFFSET = ETHER_HEADER_LEN + IPV4_SRC_ADDR_OFFSET
    private val IPV4_DST_OFFSET = ETHER_HEADER_LEN + IPV4_DST_ADDR_OFFSET
    private val UDP_SRC_PORT_OFFSET_IPV4 =
        ETHER_HEADER_LEN + IPV4_HEADER_MIN_LEN + UDP_SRCPORT_OFFSET
    private val UDP_DST_PORT_OFFSET_IPV4 =
        ETHER_HEADER_LEN + IPV4_HEADER_MIN_LEN + UDP_DSTPORT_OFFSET
    private val DNS_PAYLOAD_OFFSET_IPV4 = ETHER_HEADER_LEN + IPV4_HEADER_MIN_LEN + UDP_HEADER_LEN

    // Offsets for IPv6 UDP packets
    private val IPV6_SRC_OFFSET = ETHER_HEADER_LEN + IPV6_SRC_ADDR_OFFSET
    private val IPV6_DST_OFFSET = ETHER_HEADER_LEN + IPV6_DST_ADDR_OFFSET
    private val UDP_SRC_PORT_OFFSET_IPV6 = ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_SRCPORT_OFFSET
    private val UDP_DST_PORT_OFFSET_IPV6 = ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_DSTPORT_OFFSET
    private val DNS_PAYLOAD_OFFSET_IPV6 = ETHER_HEADER_LEN + IPV6_HEADER_LEN + UDP_HEADER_LEN

    init {
        answers.forEach { (host, addrs) ->
            ansProvider.setAnswer(host, addrs)
        }
    }

    override fun replyToPacket(packet: ByteArray, reader: PollPacketReader) {
        val buffer = ByteBuffer.wrap(packet)
        val etherType = buffer.getShort(ETHER_TYPE_OFFSET).toInt() and 0xFFFF

        val srcIpRange: IntRange
        val dstIpRange: IntRange
        val srcPortOffset: Int
        val dstPortOffset: Int
        val dnsPayloadOffset: Int

        when (etherType) {
            ETHER_TYPE_IPV4 -> {
                srcIpRange = IPV4_SRC_OFFSET until IPV4_SRC_OFFSET + IPV4_ADDR_LEN
                dstIpRange = IPV4_DST_OFFSET until IPV4_DST_OFFSET + IPV4_ADDR_LEN
                srcPortOffset = UDP_SRC_PORT_OFFSET_IPV4
                dstPortOffset = UDP_DST_PORT_OFFSET_IPV4
                dnsPayloadOffset = DNS_PAYLOAD_OFFSET_IPV4
            }
            ETHER_TYPE_IPV6 -> {
                srcIpRange = IPV6_SRC_OFFSET until IPV6_SRC_OFFSET + IPV6_ADDR_LEN
                dstIpRange = IPV6_DST_OFFSET until IPV6_DST_OFFSET + IPV6_ADDR_LEN
                srcPortOffset = UDP_SRC_PORT_OFFSET_IPV6
                dstPortOffset = UDP_DST_PORT_OFFSET_IPV6
                dnsPayloadOffset = DNS_PAYLOAD_OFFSET_IPV6
            }
            else -> return
        }

        if (packet.size <= dnsPayloadOffset) return
        val srcIp = InetAddress.getByAddress(packet.sliceArray(srcIpRange))
        val dstIp = InetAddress.getByAddress(packet.sliceArray(dstIpRange))
        val srcPort = buffer.getShort(srcPortOffset).toInt() and 0xFFFF
        val dstPort = buffer.getShort(dstPortOffset).toInt() and 0xFFFF

        val dnsBytes = packet.copyOfRange(dnsPayloadOffset, packet.size)
        val dnsPacket = try {
            DnsPacket(dnsBytes)
        } catch (e: DnsPacket.ParseException) {
            Log.e(TAG, "Bad DNS packet: " + HexDump.toHexString(dnsBytes), e)
            return
        }
        val questions = dnsPacket.getRecords(QDSECTION)

        if (questions.isEmpty()) {
            Log.i(TAG, "No questions in DNS query")
            return
        }

        if (questions.size > 1) {
            Log.i(TAG, "More than one question in DNS query; not supported.")
            return
        }
        val answerRecords = questions[0].let { ansProvider.getAnswer(it.dName, it.nsType) }
        if (answerRecords.isEmpty()) {
            Log.i(TAG, "No answer for DNS query ${questions[0].dName}")
            return
        }

        val flags = DNS_HEADER_FLAGS_RESPONSE or
                (dnsPacket.header.flags and DNS_HEADER_FLAGS_RECURSION_DESIRED)

        val responseHeader = DnsHeader(
            dnsPacket.header.id,
            flags,
            questions.size,
            answerRecords.size
        )

        val responseDnsPacket = DnsPacket(responseHeader, questions, answerRecords)

        val srcMac =
            MacAddress.fromBytes(
                packet.copyOfRange(
                    ETHER_SRC_ADDR_OFFSET,
                    ETHER_SRC_ADDR_OFFSET + ETHER_ADDR_LEN
                )
            )
        val dstMac =
            MacAddress.fromBytes(
                packet.copyOfRange(
                    ETHER_DST_ADDR_OFFSET,
                    ETHER_DST_ADDR_OFFSET + ETHER_ADDR_LEN
                )
            )

        val ether = EtherPkt(srcMac, dstMac)
        val ipPkt = if (etherType == ETHER_TYPE_IPV4) {
            Ip4Pkt(dstIp as Inet4Address, srcIp as Inet4Address)
        } else {
            Ip6Pkt(dstIp as Inet6Address, srcIp as Inet6Address)
        }
        val udp = UdpPkt(dstPort, srcPort)
        val dns = DataPkt(responseDnsPacket.bytes)

        val replyPacket = ether / ipPkt / udp / dns
        reader.sendResponse(ByteBuffer.wrap(replyPacket.build()))
    }
}
