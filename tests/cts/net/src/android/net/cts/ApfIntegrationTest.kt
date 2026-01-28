/*
 * Copyright (C) 2024 The Android Open Source Project
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

import android.net.Network
import android.net.TrafficStats
import android.net.apf.ApfConstants.ETH_ETHERTYPE_OFFSET
import android.net.apf.ApfConstants.ETH_HEADER_LEN
import android.net.apf.ApfConstants.ICMP6_CHECKSUM_OFFSET
import android.net.apf.ApfConstants.ICMP6_TYPE_OFFSET
import android.net.apf.ApfConstants.IPV6_DEST_ADDR_OFFSET
import android.net.apf.ApfConstants.IPV6_HEADER_LEN
import android.net.apf.ApfConstants.IPV6_NEXT_HEADER_OFFSET
import android.net.apf.ApfConstants.IPV6_SRC_ADDR_OFFSET
import android.net.apf.ApfCounterTracker
import android.net.apf.ApfCounterTracker.Counter.DROPPED_IPV6_NS_INVALID
import android.net.apf.ApfCounterTracker.Counter.DROPPED_IPV6_NS_REPLIED_NON_DAD
import android.net.apf.ApfCounterTracker.Counter.FILTER_AGE_16384THS
import android.net.apf.ApfCounterTracker.Counter.PASSED_IPV6_ICMP
import android.net.apf.ApfV4Generator
import android.net.apf.ApfV4GeneratorBase
import android.net.apf.ApfV6Generator
import android.net.apf.BaseApfGenerator
import android.net.apf.BaseApfGenerator.MemorySlot
import android.net.apf.BaseApfGenerator.Register.R0
import android.net.apf.BaseApfGenerator.Register.R1
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.platform.test.annotations.AppModeFull
import android.system.Os
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.ETH_P_IPV6
import android.system.OsConstants.ICMP6_ECHO_REPLY
import android.system.OsConstants.ICMP6_ECHO_REQUEST
import android.system.OsConstants.IPPROTO_ICMPV6
import android.system.OsConstants.SOCK_DGRAM
import android.system.OsConstants.SOCK_NONBLOCK
import android.util.Log
import androidx.test.filters.RequiresDevice
import com.android.compatibility.common.util.PropertyUtil.getFirstApiLevel
import com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel
import com.android.compatibility.common.util.VsrTest
import com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ICMPV6_HEADER_MIN_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_LEN
import com.android.net.module.util.PacketReader
import com.android.testutils.ConnectivityDiagnosticsCollector
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.waitForIdle
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.TruthJUnit.assume
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.fail
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TIMEOUT_MS = 2000L
private const val RCV_BUFFER_SIZE = 1480
private const val PING_HEADER_LENGTH = 8

// Sets threshold to 5 Kbps to provide a conservative buffer against the 10 Mbps VSR requirement.
private const val TRAFFIC_THRESHOLD_KBPS = 5.0
private const val POLLING_INTERVAL_MS = 2000L
private const val MAX_POLLING_ATTEMPTS = 15

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@RequiresDevice
@NetworkStackModuleTest
// ByteArray.toHexString is experimental API
@kotlin.ExperimentalStdlibApi
class ApfIntegrationTest : ApfTestBase() {
    companion object {
        private val TAG = "ApfIntegrationTest"
        private val PING_DESTINATION = InetSocketAddress("2001:4860:4860::8888", 0)
    }

    class Icmp6PacketReader(
            handler: Handler,
            private val network: Network
    ) : PacketReader(handler, RCV_BUFFER_SIZE) {
        private data class PingContext(
            val futureReply: CompletableFuture<List<ByteArray>>,
            val expectReplyCount: Int,
            val replyPayloads: MutableList<ByteArray> = mutableListOf()
        )
        private var sockFd: FileDescriptor? = null
        private var pingContext: PingContext? = null

        override fun createFd(): FileDescriptor {
            // sockFd is closed by calling super.stop()
            val sock = Os.socket(AF_INET6, SOCK_DGRAM or SOCK_NONBLOCK, IPPROTO_ICMPV6)
            // APF runs only on WiFi, so make sure the socket is bound to the right network.
            network.bindSocket(sock)
            sockFd = sock
            return sock
        }

        override fun handlePacket(recvbuf: ByteArray, length: Int) {
            val context = pingContext ?: return

            // If zero-length or Type is not echo reply: ignore.
            if (length == 0 || recvbuf[0] != 0x81.toByte()) {
                return
            }
            // Only copy the ping data and complete the future.
            val result = recvbuf.sliceArray(8..<length)
            Log.i(TAG, "Received ping reply: ${result.toHexString()}")
            context.replyPayloads.add(recvbuf.sliceArray(8..<length))
            if (context.replyPayloads.size == context.expectReplyCount) {
                context.futureReply.complete(context.replyPayloads)
                pingContext = null
            }
        }

        fun sendPing(data: ByteArray, payloadSize: Int, expectReplyCount: Int = 1) {
            require(data.size == payloadSize)

            // rfc4443#section-4.1: Echo Request Message
            //   0                   1                   2                   3
            //   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |     Type      |     Code      |          Checksum             |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |           Identifier          |        Sequence Number        |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |     Data ...
            //  +-+-+-+-+-
            val icmp6Header = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val packet = icmp6Header + data
            Log.i(TAG, "Sent ping: ${packet.toHexString()}")
            pingContext = PingContext(
                futureReply = CompletableFuture<List<ByteArray>>(),
                expectReplyCount = expectReplyCount
            )
            Os.sendto(sockFd!!, packet, 0, packet.size, 0, PING_DESTINATION)
        }

        fun expectPingReply(timeoutMs: Long = TIMEOUT_MS): List<ByteArray> {
            return pingContext!!.futureReply.get(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun expectPingDropped() {
            assertFailsWith(TimeoutException::class) {
                pingContext!!.futureReply.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }

        override fun start(): Boolean {
            // Ignore the fact start() could return false or throw an exception.
            handler.post({ super.start() })
            handler.waitForIdle(TIMEOUT_MS)
            return true
        }

        override fun stop() {
            handler.post({ super.stop() })
            handler.waitForIdle(TIMEOUT_MS)
        }
    }

    private lateinit var packetReader: Icmp6PacketReader

    @Before
    override fun setUp() {
        super.setUp()
        packetReader = Icmp6PacketReader(handler, network)
        packetReader.start()
    }

    @After
    override fun tearDown() {
        if (::packetReader.isInitialized) {
            packetReader.stop()
        }
        super.tearDown()
    }

    private data class TrafficSnapshot(
        val rxBytes: Long,
        val txBytes: Long,
        val timeMs: Long
    ) {
        fun getThroughputKbps(prev: TrafficSnapshot): Double {
            val totalBytesDiff = (rxBytes - prev.rxBytes) + (txBytes - prev.txBytes)
            val timeDiffMs = timeMs - prev.timeMs
            if (timeDiffMs <= 1000) {
                fail(
                    "Need to wait least 1 second to avoid hit cache: timeDiffMs=$timeDiffMs."
                )
            }
            return (totalBytesDiff * 8.0) / (timeDiffMs / 1000.0) / 1000.0
        }

        fun isUnsupported(): Boolean {
            return rxBytes == TrafficStats.UNSUPPORTED.toLong() ||
                    txBytes == TrafficStats.UNSUPPORTED.toLong()
        }

        override fun toString(): String = "rx=$rxBytes tx=$txBytes"

        companion object {
            fun capture(ifname: String) = TrafficSnapshot(
                TrafficStats.getRxBytes(ifname),
                TrafficStats.getTxBytes(ifname),
                SystemClock.elapsedRealtime()
            )
        }
    }

    /**
     * Wait for network traffic on the test interface to be low enough for APF to be active.
     * Polls traffic every 2 seconds. Fails if traffic exceeds threshold after all retries.
     *
     * Note: This method will wait at least 2 seconds to measure traffic.
     */
    fun waitForLowTraffic() {
        var prev = TrafficSnapshot.capture(ifname)
        Log.i(TAG, "$ifname initial TrafficStats: $prev")

        if (prev.isUnsupported()) {
            fail("TrafficStats unsupported for $ifname")
        }

        for (i in 0 until MAX_POLLING_ATTEMPTS) {
            Thread.sleep(POLLING_INTERVAL_MS)

            val current = TrafficSnapshot.capture(ifname)
            Log.i(
                TAG,
                "$ifname TrafficStats (attempt ${i + 1}/$MAX_POLLING_ATTEMPTS): $current"
            )

            val throughputKbps = current.getThroughputKbps(prev)
            val rxDiff = current.rxBytes - prev.rxBytes
            val txDiff = current.txBytes - prev.txBytes

            Log.i(
                TAG,
                "$ifname throughput (${i + 1}/$MAX_POLLING_ATTEMPTS) " +
                        "after ${POLLING_INTERVAL_MS}ms: " +
                        "${"%.2f".format(throughputKbps)} Kbps " +
                        "(rxDiff=$rxDiff txDiff=$txDiff)."
            )

            if (throughputKbps < TRAFFIC_THRESHOLD_KBPS) {
                Log.i(
                    TAG,
                    "$ifname traffic below threshold ${TRAFFIC_THRESHOLD_KBPS}kbps after " +
                            "${POLLING_INTERVAL_MS}ms; continuing"
                )
                return
            }

            prev = current
        }

        fail(
            "Background traffic on $ifname exceeded $TRAFFIC_THRESHOLD_KBPS Kbps after retries. " +
            "APF requires low traffic. Ensure no background traffic during test."
        )
    }

    private fun installAndVerifyProgram(program: ByteArray) {
        installProgram(program)
        val readResult = readProgram().take(program.size).toByteArray()
        assertThat(readResult).isEqualTo(program)
    }

    fun ApfV4GeneratorBase<*>.addPassIfNotIcmpv6EchoReply(skipPacketLabel: Short) {
        // If not IPv6 -> PASS
        addLoad16intoR0(ETH_ETHERTYPE_OFFSET)
        addJumpIfR0NotEquals(ETH_P_IPV6.toLong(), skipPacketLabel)

        // If not ICMPv6 -> PASS
        addLoad8intoR0(IPV6_NEXT_HEADER_OFFSET)
        addJumpIfR0NotEquals(IPPROTO_ICMPV6.toLong(), skipPacketLabel)

        // If not echo reply -> PASS
        addLoad8intoR0(ICMP6_TYPE_OFFSET)
        addJumpIfR0NotEquals(0x81, skipPacketLabel)
    }

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    fun testDropPingReply() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        // If the firmware declares a version greater than or equal to 6000, it must properly
        // support APFv6+.
        if (caps.apfVersionSupported < 6000) {
            assume().that(getVsrApiLevel()).isAtLeast(34)
        }
        assumeApfVersionSupportAtLeast(4)
        assumeNotCuttlefish()

        waitForLowTraffic()

        // clear any active APF filter
        clearApfMemory()
        readProgram() // wait for install completion

        // Assert that initial ping does not get filtered.
        val payloadSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            68
        } else {
            4
        }
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        assertThat(packetReader.expectPingReply()[0]).isEqualTo(data)

        // Generate an APF program that drops the next ping
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        val skipPacketLabel = gen.uniqueLabel
        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply(skipPacketLabel)

        // if not data matches -> PASS
        gen.addLoadImmediate(R0, ICMP6_TYPE_OFFSET + PING_HEADER_LENGTH)
        gen.addJumpIfBytesAtR0NotEqual(data, skipPacketLabel)

        // else DROP
        // Warning: the program abuse DROPPED_IPV6_NS_INVALID/PASSED_IPV6_ICMP for debugging purpose
        gen.addCountAndDrop(DROPPED_IPV6_NS_INVALID)
            .defineLabel(skipPacketLabel)
            .addCountAndPass(PASSED_IPV6_ICMP)
            .addCountTrampoline()

        val program = gen.generate()
        installAndVerifyProgram(program)

        val counterBefore = ApfCounterTracker.getCounterValue(
            readProgram(),
            DROPPED_IPV6_NS_INVALID
        )
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingDropped()
        val counterAfter = ApfCounterTracker.getCounterValue(
            readProgram(),
            DROPPED_IPV6_NS_INVALID
        )
        assertEquals(counterBefore + 1, counterAfter)
    }

    fun clearApfMemory() = installProgram(ByteArray(caps.maximumApfProgramSize))

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    fun testPrefilledMemorySlotsV4() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        // If the firmware declares a version greater than or equal to 6000, it must properly
        // support APFv6+.
        if (caps.apfVersionSupported < 6000) {
            assume().that(getVsrApiLevel()).isAtLeast(34)
        }
        // Test v4 memory slots on both v4 and v6 interpreters.
        assumeApfVersionSupportAtLeast(4)
        assumeNotCuttlefish()

        waitForLowTraffic()

        clearApfMemory()
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply(BaseApfGenerator.PASS_LABEL)

        // Store all prefilled memory slots in counter region [500, 520)
        val counterRegion = 500
        gen.addLoadImmediate(R1, counterRegion)
        gen.addLoadFromMemory(R0, MemorySlot.PROGRAM_SIZE)
        gen.addStoreData(R0, 0)
        gen.addLoadFromMemory(R0, MemorySlot.RAM_LEN)
        gen.addStoreData(R0, 4)
        gen.addLoadFromMemory(R0, MemorySlot.IPV4_HEADER_SIZE)
        gen.addStoreData(R0, 8)
        gen.addLoadFromMemory(R0, MemorySlot.PACKET_SIZE)
        gen.addStoreData(R0, 12)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_SECONDS)
        gen.addStoreData(R0, 16)

        val program = gen.generate()
        assertThat(program.size).isLessThan(counterRegion)
        val randomProgram = ByteArray(1) { 0 } +
                ByteArray(counterRegion - 1).also { Random.nextBytes(it) }
        // There are known firmware bugs where they calculate the number of non-zero bytes within
        // the program to determine the program length. Modify the test to first install a longer
        // program before installing a program that do the program length check. This should help us
        // catch these types of firmware bugs in CTS. (b/395545572)
        installAndVerifyProgram(randomProgram)
        installAndVerifyProgram(program)

        // Trigger the program by sending a ping and waiting on the reply.
        val payloadSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            68
        } else {
            4
        }
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        val readResult = readProgram()
        val buffer = ByteBuffer.wrap(readResult, counterRegion, 20 /* length */)
        expect.withMessage("PROGRAM_SIZE").that(buffer.getInt()).isEqualTo(program.size)
        expect.withMessage("RAM_LEN").that(buffer.getInt()).isEqualTo(caps.maximumApfProgramSize)
        expect.withMessage("IPV4_HEADER_SIZE").that(buffer.getInt()).isEqualTo(0)
        // Ping packet payload + ICMPv6 header (8)  + IPv6 header (40) + ethernet header (14)
        expect.withMessage("PACKET_SIZE").that(buffer.getInt()).isEqualTo(payloadSize + 8 + 40 + 14)
        expect.withMessage("FILTER_AGE_SECONDS").that(buffer.getInt()).isLessThan(5)
    }

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    fun testFilterAgeIncreasesBetweenPackets() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        // If the firmware declares a version greater than or equal to 6000, it must properly
        // support APFv6+.
        if (caps.apfVersionSupported < 6000) {
            assume().that(getVsrApiLevel()).isAtLeast(34)
        }
        assumeApfVersionSupportAtLeast(4)
        assumeNotCuttlefish()

        waitForLowTraffic()

        clearApfMemory()
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply(BaseApfGenerator.PASS_LABEL)

        // Store all prefilled memory slots in counter region [500, 520)
        val counterRegion = 500
        gen.addLoadImmediate(R1, counterRegion)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_SECONDS)
        gen.addStoreData(R0, 0)

        installAndVerifyProgram(gen.generate())

        val payloadSize = 56
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        var buffer = ByteBuffer.wrap(readProgram(), counterRegion, 4 /* length */)
        val filterAgeSecondsOrig = buffer.getInt()

        Thread.sleep(5100)

        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        buffer = ByteBuffer.wrap(readProgram(), counterRegion, 4 /* length */)
        val filterAgeSeconds = buffer.getInt()
        // Assert that filter age has increased, but not too much.
        val timeDiff = filterAgeSeconds - filterAgeSecondsOrig
        assertThat(timeDiff).isAnyOf(5, 6)
    }

    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    fun testFilterAge16384thsIncreasesBetweenPackets() {
        assumeApfVersionSupportAtLeast(6000)
        assumeNotCuttlefish()

        waitForLowTraffic()

        clearApfMemory()
        val gen = ApfV6Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply(BaseApfGenerator.PASS_LABEL)

        // Store all prefilled memory slots in counter region [500, 520)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_16384THS)
        gen.addStoreCounter(FILTER_AGE_16384THS, R0)

        installAndVerifyProgram(gen.generate())

        val payloadSize = 56
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        var apfRam = readProgram()
        val filterAge16384thSecondsOrig =
                ApfCounterTracker.getCounterValue(apfRam, FILTER_AGE_16384THS)

        Thread.sleep(5000)

        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        apfRam = readProgram()
        val filterAge16384thSeconds = ApfCounterTracker.getCounterValue(apfRam, FILTER_AGE_16384THS)
        val timeDiff = (filterAge16384thSeconds - filterAge16384thSecondsOrig)
        // Expect the HAL plus ping latency to be less than 800ms.
        val timeDiffLowerBound = (4.99 * 16384).toInt()
        val timeDiffUpperBound = (5.81 * 16384).toInt()
        // Assert that filter age has increased, but not too much.
        assertThat(timeDiff).isGreaterThan(timeDiffLowerBound)
        assertThat(timeDiff).isLessThan(timeDiffUpperBound)
    }

    @VsrTest(
            requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005", "VSR-5.3.12-012", "VSR-5.3.12-013",
                "VSR-5.3.12-014", "VSR-5.3.12-015", "VSR-5.3.12-016", "VSR-5.3.12-017"]
    )
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @ConnectivityDiagnosticsCollector.CollectTcpdumpOnFailure
    @Test
    fun testReplyPing() {
        assumeApfVersionSupportAtLeast(6000)
        assumeNotCuttlefish()

        waitForLowTraffic()

        installProgram(ByteArray(caps.maximumApfProgramSize) { 0 }) // Clear previous program
        readProgram() // Ensure installation is complete

        val payloadSize = 56
        val payload = ByteArray(payloadSize).also { Random.nextBytes(it) }
        val firstByte = payload.take(1).toByteArray()

        val pingRequestIpv6PayloadLen = PING_HEADER_LENGTH + 1
        val pingRequestPktLen = ETH_HEADER_LEN + IPV6_HEADER_LEN + pingRequestIpv6PayloadLen

        val gen = ApfV6Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )
        val skipPacketLabel = gen.uniqueLabel

        // Summary of the program:
        //   if the packet is not ICMPv6 echo reply
        //     pass
        //   else if the echo reply payload size is 1
        //     increase PASSED_IPV6_ICMP counter
        //     pass
        //   else
        //     transmit 3 ICMPv6 echo requests with random first byte
        //     increase DROPPED_IPV6_NS_REPLIED_NON_DAD counter
        //     drop
        gen.addLoad16intoR0(ETH_ETHERTYPE_OFFSET)
                .addJumpIfR0NotEquals(ETH_P_IPV6.toLong(), skipPacketLabel)
                .addLoad8intoR0(IPV6_NEXT_HEADER_OFFSET)
                .addJumpIfR0NotEquals(IPPROTO_ICMPV6.toLong(), skipPacketLabel)
                .addLoad8intoR0(ICMP6_TYPE_OFFSET)
                .addJumpIfR0NotEquals(ICMP6_ECHO_REPLY.toLong(), skipPacketLabel)
                .addLoadFromMemory(R0, MemorySlot.PACKET_SIZE)
                .addCountAndPassIfR0Equals(
                    (ETHER_HEADER_LEN + IPV6_HEADER_LEN + PING_HEADER_LENGTH + firstByte.size)
                        .toLong(),
                    PASSED_IPV6_ICMP
                )

        val numOfPacketToTransmit = 3
        val expectReplyPayloads = (0 until numOfPacketToTransmit).map { Random.nextBytes(1) }
        expectReplyPayloads.forEach { replyPingPayload ->
            // Ping Packet Generation
            gen.addAllocate(pingRequestPktLen)
                    // Eth header
                    .addPacketCopy(ETHER_SRC_ADDR_OFFSET, ETHER_ADDR_LEN) // dst MAC address
                    .addPacketCopy(ETHER_DST_ADDR_OFFSET, ETHER_ADDR_LEN) // src MAC address
                    .addWriteU16(ETH_P_IPV6) // IPv6 type
                    // IPv6 Header
                    .addWrite32(0x60000000) // IPv6 Header: version, traffic class, flowlabel
                    // payload length (2 bytes) | next header: ICMPv6 (1 byte) | hop limit (1 byte)
                    .addWrite32(pingRequestIpv6PayloadLen shl 16 or (IPPROTO_ICMPV6 shl 8 or 64))
                    .addPacketCopy(IPV6_DEST_ADDR_OFFSET, IPV6_ADDR_LEN) // src ip
                    .addPacketCopy(IPV6_SRC_ADDR_OFFSET, IPV6_ADDR_LEN) // dst ip
                    // ICMPv6
                    .addWriteU8(ICMP6_ECHO_REQUEST)
                    .addWriteU8(0) // code
                    .addWriteU16(pingRequestIpv6PayloadLen) // checksum
                    // identifier
                    .addPacketCopy(ETHER_HEADER_LEN + IPV6_HEADER_LEN + ICMPV6_HEADER_MIN_LEN, 2)
                    .addWriteU16(0) // sequence number
                    .addDataCopy(replyPingPayload) // data
                    .addTransmitL4(
                        ETHER_HEADER_LEN, // ip_ofs
                        ICMP6_CHECKSUM_OFFSET, // csum_ofs
                        IPV6_SRC_ADDR_OFFSET, // csum_start
                        IPPROTO_ICMPV6, // partial_sum
                        false // udp
                    )
        }

        // Warning: the program abuse DROPPED_IPV6_NS_REPLIED_NON_DAD for debugging purpose
        gen.addCountAndDrop(DROPPED_IPV6_NS_REPLIED_NON_DAD)
            .defineLabel(skipPacketLabel)
            .addPass()

        val program = gen.generate()
        installAndVerifyProgram(program)

        val counterBefore = ApfCounterTracker.getCounterValue(
            readProgram(),
            DROPPED_IPV6_NS_REPLIED_NON_DAD
        )
        packetReader.sendPing(payload, payloadSize, expectReplyCount = numOfPacketToTransmit)
        val replyPayloads = try {
            packetReader.expectPingReply(TIMEOUT_MS * 2)
        } catch (e: TimeoutException) {
            emptyList()
        }

        val apfCounterTracker = ApfCounterTracker()
        val apfRam = readProgram()
        apfCounterTracker.updateCountersFromData(apfRam)
        Log.i(TAG, "counter map: ${apfCounterTracker.counters}")

        val counterAfter = ApfCounterTracker.getCounterValue(
            apfRam,
            DROPPED_IPV6_NS_REPLIED_NON_DAD
        )
        assertEquals(counterBefore + 1, counterAfter)

        assertThat(replyPayloads.size).isEqualTo(expectReplyPayloads.size)

        // Sort the payload list before comparison to ensure consistency.
        val sortedReplyPayloads = replyPayloads.sortedBy { it[0] }
        val sortedExpectReplyPayloads = expectReplyPayloads.sortedBy { it[0] }
        for (i in sortedReplyPayloads.indices) {
            assertThat(sortedReplyPayloads[i]).isEqualTo(sortedExpectReplyPayloads[i])
        }
    }
}
