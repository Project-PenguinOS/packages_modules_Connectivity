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

package android.net.cts

import android.Manifest
import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.pm.PackageManager
import android.net.InetAddresses
import android.net.LinkAddress
import android.net.Network
import android.net.TestNetworkManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.NetworkStackConstants
import com.android.net.module.util.PacketBuilder
import com.android.net.module.util.Struct
import com.android.net.module.util.structs.Ipv4Header
import com.android.net.module.util.structs.Ipv6Header
import com.android.net.module.util.structs.TcpHeader
import com.android.net.module.util.structs.UdpHeader
import com.android.testutils.AutoCloseTestResourcesRule
import com.android.testutils.AutoCloseableTestNetworkInterface
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.PollPacketReader
import com.android.testutils.TestableNetworkAgent
import com.android.testutils.TestableNetworkCallback.Event
import com.android.testutils.filters.CtsNetTestCasesLocalNetNoPermissions
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.nio.ByteBuffer
import java.nio.channels.DatagramChannel
import java.nio.channels.SocketChannel
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.fail
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LocalNetworkTest {
    private val context by lazy { InstrumentationRegistry.getInstrumentation().context }
    private val binder = Binder()
    private val handlerThread = HandlerThread(LocalNetworkTest::class.java.simpleName)

    private lateinit var packetReader: PollPacketReader
    private lateinit var network: Network
    private lateinit var handler: Handler
    private lateinit var linkLocalIpv6Address: Inet6Address
    private lateinit var tnm: TestNetworkManager

    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private val iface =
        AutoCloseableTestNetworkInterface.createTun(context, LINK_ADDRESSES)

    @get:Rule
    val testResourcesRule = AutoCloseTestResourcesRule().apply {
        add(iface)
    }

    @get:Rule
    val cbRule = AutoReleaseNetworkCallbackRule()

    companion object {
        private const val TEST_TIMEOUT_MS = 10000L
        private const val MAX_PACKET_LENGTH = 1500
        private const val PORT = 8000
        private const val TCP_INITIAL_SEQ = 123456789.toShort()
        private const val TCP_WINDOW_SIZE = 65535
        private val ON_LINK_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.168.0.10")
        private val ON_LINK_IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:abcd::2")
        private val OFF_LINK_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.1")
        private val OFF_LINK_IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1::1")
        private val DEVICE_IPV4_ADDRESS: InetAddress =
            InetAddresses.parseNumericAddress("192.168.0.1")
        private val DEVICE_IPV6_ADDRESS: InetAddress =
            InetAddresses.parseNumericAddress("2001:db8:abcd::1")
        private val ALWAYS_ALLOWED_IPV4_ADDRESS = InetAddresses.parseNumericAddress("192.0.2.2")
        private val ALWAYS_ALLOWED_IPV6_ADDRESS = InetAddresses.parseNumericAddress("2001:db8:1::2")
        private val LINK_ADDRESSES =
            listOf(LinkAddress("192.168.0.1/16"), LinkAddress("2001:db8:abcd::1/64"))
        private val PACKET_PAYLOAD = "abcdefghijklmnop".toByteArray(Charsets.UTF_8)

        private fun makeLinkLocalAddress(iface: String): Inet6Address {
            return Inet6Address.getByAddress(
                null, /* host */
                InetAddresses.parseNumericAddress("fe80::1").address,
                NetworkInterface.getByName(iface).index
            )
        }
    }

    @Before
    fun setUp() {
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        packetReader =
            PollPacketReader(handler, iface.fileDescriptor.fileDescriptor, MAX_PACKET_LENGTH)
        handler.post { packetReader.start() }
        handler.waitForIdle(TEST_TIMEOUT_MS)

        val cb = cbRule.requestNetwork(
            TestableNetworkAgent.makeNetworkRequestForInterface(
                iface.interfaceName
            )
        )
        // Set up the test network after network request is filed to prevent Network from being
        // reaped due to no requests matching it.
        runAsShell(MANAGE_TEST_NETWORKS) {
            tnm = context.getSystemService(TestNetworkManager::class.java)!!
            tnm.setupTestNetwork(iface.interfaceName, binder)
        }

        network = cb.expect<Event.Available>(timeoutMs = TEST_TIMEOUT_MS).network
        linkLocalIpv6Address = makeLinkLocalAddress(iface.interfaceName)
    }

    @After
    fun tearDown() {
        handler.post { packetReader.stop() }
        handlerThread.quitSafely()
        handlerThread.join()

        if (this::network.isInitialized) {
            runAsShell(MANAGE_TEST_NETWORKS) {
                tnm.teardownTestNetwork(network)
            }
        }
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_dropsLocalEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV4_ADDRESS, false)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV6_ADDRESS, false)
        sendUdpPacketAndCheckSuccess(linkLocalIpv6Address, false)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_allowsOffLinkEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendUdpPacketAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_sendsLocalEgressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV4_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(ON_LINK_IPV6_ADDRESS, true)
        sendUdpPacketAndCheckSuccess(linkLocalIpv6Address, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_dropsLocalEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendTcpPacketAndAssertPermissionDenied(ON_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertPermissionDenied(ON_LINK_IPV6_ADDRESS)
        sendTcpPacketAndAssertPermissionDenied(linkLocalIpv6Address)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_allowsOffLinkEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        sendTcpPacketAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_sendsLocalEgressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        sendTcpPacketAndAssertSuccess(ON_LINK_IPV4_ADDRESS)
        sendTcpPacketAndAssertSuccess(ON_LINK_IPV6_ADDRESS)
        sendTcpPacketAndAssertSuccess(linkLocalIpv6Address)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_receivesLocalIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV6_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(linkLocalIpv6Address, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_dropsLocalIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV4_ADDRESS, false)
        writeIngressUdpAndCheckSuccess(ON_LINK_IPV6_ADDRESS, false)
        writeIngressUdpAndCheckSuccess(linkLocalIpv6Address, false)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_receivesOffLinkIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_receivesOffLinkIngressUdpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV4_ADDRESS, true)
        writeIngressUdpAndCheckSuccess(OFF_LINK_IPV6_ADDRESS, true)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_receivesLocalIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressTcpAndAssertSuccess(ON_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(ON_LINK_IPV6_ADDRESS)
        writeIngressTcpAndAssertSuccess(linkLocalIpv6Address)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_dropsLocalIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressTcpAndAssertPermissionDenied(ON_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertPermissionDenied(ON_LINK_IPV6_ADDRESS)
        writeIngressTcpAndAssertPermissionDenied(linkLocalIpv6Address)
    }

    @Test
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testPermissionGranted_receivesOffLinkIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_GRANTED)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    @Test
    @CtsNetTestCasesLocalNetNoPermissions
    @DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    fun testMissingPermission_receivesOffLinkIngressTcpPacket() {
        assertLocalNetworkPermissions(PackageManager.PERMISSION_DENIED)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV4_ADDRESS)
        writeIngressTcpAndAssertSuccess(OFF_LINK_IPV6_ADDRESS)
    }

    private fun assertLocalNetworkPermissions(expected: Int) {
        assertEquals(
            expected,
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK)
        )
    }

    private fun checkIpHeader(buf: ByteBuffer, dstAddress: InetAddress, protocol: Byte): Boolean {
        if (dstAddress is Inet6Address) {
            val ipHeader = Struct.parse(Ipv6Header::class.java, buf)
            if (ipHeader.nextHeader != protocol || ipHeader.dstIp != dstAddress) {
                return false
            }
        } else {
            val ipHeader = Struct.parse(Ipv4Header::class.java, buf)
            if (ipHeader.protocol != protocol || ipHeader.dstIp != dstAddress) {
                return false
            }
        }
        return true
    }

    // ------------ TCP Helpers ------------

    private fun writeIngressTcpAndAssertSuccess(srcAddress: InetAddress) {
        ServerSocket().use { serverSocket ->
            val dstAddress =
                if (srcAddress is Inet6Address) DEVICE_IPV6_ADDRESS else DEVICE_IPV4_ADDRESS
            // Start a socket at the intended destination address, otherwise the kernel sends a
            // reset before packet ingress hooks are run
            serverSocket.bind(InetSocketAddress(dstAddress, 0))
            val dstPort = serverSocket.localPort

            // Simulate ingress packets by writing TCP SYN packets directly to the test network
            // interface's file descriptor.
            writeSynPacket(srcAddress, PORT, dstAddress, dstPort)

            assertNotNull(packetReader.poll(TEST_TIMEOUT_MS) { packet ->
                // Verify that the network stack didn't drop the SYN packet by looking for the
                // outgoing SYN-ACK
                matchTcpPacket(
                    packet,
                    dstAddress = srcAddress,
                    NetworkStackConstants.TCPHDR_ACK.toInt()
                )
            })
            // TODO: verify that android_getnetworkblockedreason does not report any errors when
            // ingress packets are dropped
        }
    }

    private fun writeIngressTcpAndAssertPermissionDenied(srcAddress: InetAddress) {
        ServerSocket().use { serverSocket ->
            val dstAddress =
                if (srcAddress is Inet6Address) DEVICE_IPV6_ADDRESS else DEVICE_IPV4_ADDRESS
            // Start a socket at the intended destination address, otherwise the kernel sends a
            // reset before packet ingress hooks are run
            serverSocket.bind(InetSocketAddress(dstAddress, 0))
            val dstPort = serverSocket.localPort

            // Simulate ingress packets by writing TCP SYN packets directly to the test network
            // interface's file descriptor.
            writeSynPacket(srcAddress, PORT, dstAddress, dstPort)

            // Write a second SYN packet that should be allowed. If we see a response to this SYN
            // connection first, we know the previous packet was dropped.
            val allowedAddr: InetAddress = if (dstAddress is Inet6Address) {
                ALWAYS_ALLOWED_IPV6_ADDRESS
            } else {
                ALWAYS_ALLOWED_IPV4_ADDRESS
            }
            writeSynPacket(allowedAddr, PORT, dstAddress, dstPort)

            assertNotNull(packetReader.poll(TEST_TIMEOUT_MS) { packet ->
                if (matchTcpPacket(packet, srcAddress, NetworkStackConstants.TCPHDR_ACK.toInt())) {
                    fail("Unexpectedly received packet that should have been dropped")
                }
                if (matchTcpPacket(packet, allowedAddr, NetworkStackConstants.TCPHDR_ACK.toInt())) {
                    return@poll true
                }
                false
            })
        }
    }

    private fun writeSynPacket(
        srcAddress: InetAddress,
        srcPort: Int,
        dstAddress: InetAddress,
        dstPort: Int
    ) {
        val buf: ByteBuffer = if (dstAddress is Inet6Address) {
            buildIpv6TcpSynPacket(
                srcAddress as Inet6Address,
                srcPort,
                dstAddress,
                dstPort,
            )
        } else {
            buildIpv4TcpSynPacket(
                srcAddress as Inet4Address,
                srcPort,
                dstAddress as Inet4Address,
                dstPort,
            )
        }
        Os.write(iface.fileDescriptor.fileDescriptor, buf)
    }

    private fun buildIpv4TcpSynPacket(
        srcAddr: Inet4Address,
        srcPort: Int,
        dstAddr: Inet4Address,
        dstPort: Int,
    ): ByteBuffer {
        val buffer = PacketBuilder.allocate(
            false, // hasEther
            OsConstants.IPPROTO_IP,
            OsConstants.IPPROTO_TCP,
            0
        )
        val packetBuilder = PacketBuilder(buffer)

        packetBuilder.writeIpv4Header(
            0.toByte(),
            0.toShort(),
            0x4000.toShort(),
            64.toByte(),
            OsConstants.IPPROTO_TCP.toByte(),
            srcAddr,
            dstAddr
        )

        packetBuilder.writeTcpHeader(
            srcPort.toShort(),
            dstPort.toShort(),
            TCP_INITIAL_SEQ,
            0, // ACK number
            NetworkStackConstants.TCPHDR_SYN,
            TCP_WINDOW_SIZE.toShort(),
            0 // urgentPointer
        )
        return packetBuilder.finalizePacket()
    }

    private fun buildIpv6TcpSynPacket(
        srcAddr: Inet6Address,
        srcPort: Int,
        dstAddr: Inet6Address,
        dstPort: Int,
    ): ByteBuffer {
        val buffer = PacketBuilder.allocate(
            false, // hasEther
            OsConstants.IPPROTO_IPV6,
            OsConstants.IPPROTO_TCP,
            0 // TCP payload size 0 for SYN
        )
        val packetBuilder = PacketBuilder(buffer)

        // Must be called before writeTcpHeader to set up pseudo header for checksum
        packetBuilder.writeIpv6Header(
            0x60000000,
            OsConstants.IPPROTO_TCP.toByte(),
            64.toShort(),
            srcAddr,
            dstAddr
        )

        packetBuilder.writeTcpHeader(
            srcPort.toShort(),
            dstPort.toShort(),
            TCP_INITIAL_SEQ,
            0, // ACK number
            NetworkStackConstants.TCPHDR_SYN,
            TCP_WINDOW_SIZE.toShort(),
            0 // urgentPointer
        )
        return packetBuilder.finalizePacket()
    }

    private fun sendTcpPacketAndAssertSuccess(dstAddress: InetAddress) {
        attemptTcpConnection(network, dstAddress)
        assertNotNull(packetReader.poll(TEST_TIMEOUT_MS) { packet ->
            val tcpFlags = NetworkStackConstants.TCPHDR_SYN.toInt()
            matchTcpPacket(packet, dstAddress, tcpFlags)
        })
    }

    private fun sendTcpPacketAndAssertPermissionDenied(dstAddress: InetAddress) {
        attemptTcpConnection(network, dstAddress)
        // Attempt a second connection that should be allowed. If we see the SYN packet for this
        // connection first, we know the packets for the previous connection are being dropped
        attemptTcpConnection(network, ALWAYS_ALLOWED_IPV6_ADDRESS)
        assertNotNull(packetReader.poll(TEST_TIMEOUT_MS) { packet ->
            val tcpFlags = NetworkStackConstants.TCPHDR_SYN.toInt()
            if (matchTcpPacket(packet, ALWAYS_ALLOWED_IPV6_ADDRESS, tcpFlags)) {
                return@poll true
            }
            if (matchTcpPacket(packet, dstAddress, tcpFlags)) {
                fail("Unexpectedly received packet that should have been dropped")
            }
            false
        })
    }

    private fun matchTcpPacket(packet: ByteArray, dstAddress: InetAddress, tcpFlags: Int): Boolean {
        val buf = ByteBuffer.wrap(packet)
        try {
            if (!checkIpHeader(buf, dstAddress, OsConstants.IPPROTO_TCP.toByte())) {
                return false
            }
            val tcpHeader = Struct.parse(TcpHeader::class.java, buf)
            return (tcpHeader.dataOffsetAndControlBits.toInt() and tcpFlags) != 0
        } catch (ignored: IllegalArgumentException) {
            return false
        }
    }

    private fun attemptTcpConnection(network: Network, dstAddress: InetAddress) {
        // Create a non-blocking socket so we don't have to wait for a timeout
        val channel = SocketChannel.open()
        channel.configureBlocking(false)
        val sock = channel.socket()
        network.bindSocket(sock)
        val socketAddress = InetSocketAddress(dstAddress, PORT)
        channel.connect(socketAddress)
        // TODO: Before closing the socket, get the blocked reason and return the result
        // so the caller can check it
        sock.close()
    }

    // ------------ UDP Helpers ------------
    private fun sendUdpPacketAndCheckSuccess(dstAddress: InetAddress, expectSuccess: Boolean) {
        val domain = if (dstAddress is Inet6Address) OsConstants.AF_INET6 else OsConstants.AF_INET
        val sock = Os.socket(domain, OsConstants.SOCK_DGRAM, OsConstants.IPPROTO_UDP)
        network.bindSocket(sock)
        try {
            Os.sendto(sock, ByteBuffer.wrap(PACKET_PAYLOAD), 0, dstAddress, PORT)
            if (!expectSuccess) {
                fail("Unexpectedly sent packet that should have been blocked")
            }
            assertNotNull(packetReader.poll(TEST_TIMEOUT_MS) { packet ->
                matchUdpPayload(packet, dstAddress, PACKET_PAYLOAD)
            })
        } catch (e: ErrnoException) {
            if (expectSuccess) {
                fail(
                    "Unexpectedly failed to send packet to ${dstAddress.hostAddress}: " +
                            "${e.message} (errno: ${e.errno})"
                )
            }
            assertEquals(OsConstants.EPERM, e.errno)
        } finally {
            Os.close(sock)
        }
    }

    private fun matchUdpPayload(
        packet: ByteArray,
        dstAddress: InetAddress,
        payload: ByteArray
    ): Boolean {
        val buf = ByteBuffer.wrap(packet)
        try {
            if (!checkIpHeader(buf, dstAddress, OsConstants.IPPROTO_UDP.toByte())) {
                return false
            }
            Struct.parse(UdpHeader::class.java, buf)
            val remaining = ByteArray(buf.remaining())
            buf.get(remaining)
            return remaining.contentEquals(payload)
        } catch (ignored: IllegalArgumentException) {
            return false
        }
    }

    private fun writeIngressUdpAndCheckSuccess(srcAddress: InetAddress, expectSuccess: Boolean) {
        DatagramChannel.open().use { channel ->
            channel.configureBlocking(false)
            val dstSock = channel.socket()
            val dstAddress =
                if (srcAddress is Inet6Address) DEVICE_IPV6_ADDRESS else DEVICE_IPV4_ADDRESS
            val bindAddr = InetSocketAddress(dstAddress, 0)
            dstSock.bind(bindAddr)
            val dstPort = dstSock.localPort

            val buf: ByteBuffer = buildUdpPacket(
                srcAddress,
                PORT,
                dstPort,
                PACKET_PAYLOAD
            )
            Os.write(iface.fileDescriptor.fileDescriptor, buf)

            val receiveBuf = ByteBuffer.allocate(PACKET_PAYLOAD.size + 100)
            val senderAddress = channel.receive(receiveBuf)
            if (expectSuccess) {
                assertNotNull(senderAddress, "Packet from $srcAddress was not received.")
                receiveBuf.flip()
                val packetSize = receiveBuf.remaining()
                assertEquals(PACKET_PAYLOAD.size, packetSize)

                val packetData = ByteArray(packetSize)
                receiveBuf.get(packetData)
                assertContentEquals(PACKET_PAYLOAD, packetData)
            } else {
                assertNull(senderAddress, "Unexpectedly received packet from $srcAddress")
            }
        }
    }

    private fun buildUdpPacket(
        srcAddr: InetAddress,
        srcPort: Int,
        dstPort: Int,
        payload: ByteArray
    ): ByteBuffer {
        val l3Proto =
            if (srcAddr is Inet6Address) OsConstants.IPPROTO_IPV6 else OsConstants.IPPROTO_IP
        val buffer = PacketBuilder.allocate(
            false, // hasEther
            l3Proto,
            OsConstants.IPPROTO_UDP,
            payload.size
        )
        val packetBuilder = PacketBuilder(buffer)

        if (srcAddr is Inet6Address) {
            packetBuilder.writeIpv6Header(
                0x60000000,
                OsConstants.IPPROTO_UDP.toByte(),
                64.toShort(),
                srcAddr,
                DEVICE_IPV6_ADDRESS as Inet6Address
            )
        } else {
            packetBuilder.writeIpv4Header(
                0.toByte(),
                27149.toShort(),
                0x4000.toShort(),
                64.toByte(),
                OsConstants.IPPROTO_UDP.toByte(),
                srcAddr as Inet4Address,
                DEVICE_IPV4_ADDRESS as Inet4Address
            )
        }
        packetBuilder.writeUdpHeader(srcPort.toShort(), dstPort.toShort())
        buffer.put(payload)
        return packetBuilder.finalizePacket()
    }
}
