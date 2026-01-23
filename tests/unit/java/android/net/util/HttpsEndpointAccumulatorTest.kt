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
package android.net.util

import android.net.dns.HttpsEndpoint
import android.net.dns.HttpsRecord
import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.DnsResolver.DnsException
import android.net.InetAddresses
import android.net.Network
import android.net.ParseException
import android.platform.test.annotations.RequiresFlagsEnabled
import android.platform.test.flag.junit.CheckFlagsRule
import android.platform.test.flag.junit.DeviceFlagsValueProvider
import android.util.Log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest

import com.android.net.module.util.DnsPacket
import com.android.net.module.util.HexDump
import com.android.net.module.util.SdkUtil
import com.android.testutils.AutoReleaseNetworkCallbackRule
import com.android.testutils.ConnectivityModuleTest
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event

import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests for [HttpsEndpointAccumulator], including [HttpsRecord] construction.
 *
 * Build, install and run with:
 * atest ConnectivityCoverageTests:android.net.connectivity.android.net.util.HttpsEndpointAccumulatorTest
 */
@RunWith(AndroidJUnit4::class)
@ConnectivityModuleTest
@SmallTest
class HttpsEndpointAccumulatorTest {
  @get:Rule val checkFlagsRule: CheckFlagsRule =
      DeviceFlagsValueProvider.createCheckFlagsRule()

  @get:Rule val callbackRule: AutoReleaseNetworkCallbackRule = AutoReleaseNetworkCallbackRule()

  @Test
  fun testOnAnswer_whenSvcbQueryType_returnsEmptyResponse() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val errorCallback = createExpectErrorCallback { error: DnsException ->
        assertEquals(error.code, DnsResolver.ERROR_PARSE)
        with(error.cause) {
          assertIs<ParseException>(this)
          assertEquals("Unsupported query type: 64", this?.message)
        }
    }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createErrorAccumulator(network, errorCallback)
    accumulator.onAnswer(SVCB_QUERY_TYPE_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenSvcbResponseType_returnsEmptyResponse() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val errorCallback = createExpectErrorCallback { error: DnsException ->
        assertEquals(error.code, DnsResolver.ERROR_PARSE)
        with(error.cause) {
          assertIs<ParseException>(this)
          assertEquals("Unexpected answer type: 64", this?.message)
        }
    }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createErrorAccumulator(network, errorCallback)
    accumulator.onAnswer(INVALID_RESPONSE_TYPE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenSingleHttpsRecord_success() {
    // Instead of reading the Conscrypt platform flag, check for the SDK version because of trunk
    // stable flag weirdness.
    assumeTrue(SdkUtil.isAtLeast26Q2())

    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertEquals(1, priority)
          assertEquals(DEFAULT_TARGET_NAME, targetName)
          assertContentEquals(TEST_IP_HINTS, ipAddressHints)
          assertContentEquals(TEST_ECH_CONFIG_LIST, echConfigList?.toBytes())
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenMultipleHttpsRecords_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 3)
        // Just check the priorities, since we've duplicated the records aside from the priority.
        assertEquals(1, response.httpsRecords[0].priority)
        assertEquals(2, response.httpsRecords[1].priority)
        assertEquals(3, response.httpsRecords[2].priority)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_MULTIPLE_HTTPS_RECORDS_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetPriority_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertEquals(1, priority)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetTargetName_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertEquals(DEFAULT_TARGET_NAME, targetName)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetAlpnIds_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertContentEquals(listOf("h3", "h2", "http/1.1"), alpnIds)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetPort_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertEquals(DEFAULT_PORT, port)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetIpAddressHints_whenNetworkIpv4Only_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        // No filtering expected for IP hints in the HTTPS record
        assertContentEquals(TEST_IP_HINTS, response.httpsRecords.first().ipAddressHints)
        assertContentEquals(TEST_IP_HINTS_IPV4_ONLY, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 1,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetIpAddressHints_whenNetworkIpv6Only_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        // No filtering expected for IP hints in the HTTPS record
        assertContentEquals(TEST_IP_HINTS, response.httpsRecords.first().ipAddressHints)
        assertContentEquals(TEST_IP_HINTS_IPV6_ONLY, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 1,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetIpAddressHints_whenNetworkIpv4AndIpv6_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        assertContentEquals(TEST_IP_HINTS, response.httpsRecords.first().ipAddressHints)
        assertContentEquals(TEST_IP_HINTS, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetEchConfigList_returnsCorrectValue() {
    // Instead of reading the Conscrypt platform flag, check for the SDK version because of trunk
    // stable flag weirdness.
    assumeTrue(SdkUtil.isAtLeast26Q2())

    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertContentEquals(TEST_ECH_CONFIG_LIST, echConfigList?.toBytes())
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenARecordOnly_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertTrue(response.httpsRecords.isEmpty())
        assertContentEquals(TEST_IP_HINTS_IPV4_ONLY, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 1,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenAAAARecordOnly_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertTrue(response.httpsRecords.isEmpty())
        assertContentEquals(TEST_IP_HINTS_IPV6_ONLY, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 1,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenAAndAAAARecordsOnly_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertTrue(response.httpsRecords.isEmpty())
        assertContentEquals(TEST_IP_HINTS, response.ipAddresses)
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 2,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenHttpsAndARecordOnly_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertContentEquals(TEST_IP_HINTS_IPV4_ONLY, response.ipAddresses)
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertContentEquals(listOf("h3", "h2", "http/1.1"), alpnIds)
          assertEquals(DEFAULT_PORT, port)
          // No filtering expected for IP hints
          assertContentEquals(TEST_IP_HINTS, ipAddressHints)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 3,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenHttpsAndAAAARecordOnly_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertContentEquals(TEST_IP_HINTS_IPV6_ONLY, response.ipAddresses)
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertContentEquals(listOf("h3", "h2", "http/1.1"), alpnIds)
          assertEquals(DEFAULT_PORT, port)
          assertContentEquals(TEST_IP_HINTS, ipAddressHints)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 3,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenAllRecords_success() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertContentEquals(TEST_IP_HINTS, response.ipAddresses)
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertContentEquals(listOf("h3", "h2", "http/1.1"), alpnIds)
          assertEquals(DEFAULT_PORT, port)
          assertContentEquals(TEST_IP_HINTS, ipAddressHints)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = HttpsEndpointAccumulator(network, answerCallback, /* queryCount= */ 3,
        QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
  }

  // TODO(b/448882639): add test to check for HTTPS record timeout

  companion object {
    private const val NETWORK_TIMEOUT_MS = 10_000L
    private const val QUERY_TIMEOUT_MS = 1000

    private const val DEFAULT_TARGET_NAME = "."
    private const val DEFAULT_PORT = 443

    val TEST_IP_HINTS_IPV4_ONLY = listOf(
        InetAddresses.parseNumericAddress("104.18.10.118"),
        InetAddresses.parseNumericAddress("104.18.11.118"))

    val TEST_IP_HINTS_IPV6_ONLY = listOf(
        InetAddresses.parseNumericAddress("2606:4700::6812:a76"),
        InetAddresses.parseNumericAddress("2606:4700::6812:b76"))

    val TEST_IP_HINTS = TEST_IP_HINTS_IPV4_ONLY + TEST_IP_HINTS_IPV6_ONLY

    // This is the exact A rawQuery response for cloudflare-ech.com.
    val VALID_A_RECORD_RESPONSE = HexDump.hexStringToByteArray(
      """
      |e5ed818000010002000000000e636c6f7564666c6172652d65636803636f6d000001
      |0001c00c000100010000012c000468120a76c00c000100010000012c000468120b76
      """.trimMargin().replace("\n", ""))

    // This is the exact AAAA rawQuery response for cloudflare-ech.com.
    val VALID_AAAA_RECORD_RESPONSE = HexDump.hexStringToByteArray(
      """
      |9ec3818000010002000000000e636c6f7564666c6172652d65636803636f6d00001c
      |0001c00c001c00010000012c001026064700000000000000000068120a76c00c001c
      |00010000012c001026064700000000000000000068120b76
      """.trimMargin().replace("\n", ""))

    // This is the exact HTTPS rawQuery response for cloudflare-ech.com.
    val VALID_SINGLE_HTTPS_RECORD_RESPONSE = HexDump.hexStringToByteArray(
    """
    |da68818000010001000000000e636c6f7564666c6172652d65636803636f6d00004100
    |01c00c004100010000012c0088000100000100060268330268320004000868120a7668
    |120b76000500470045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
    |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
    |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
    |000000000068120b76
    """.trimMargin().replace("\n", ""))

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

    // This is the exact SVCB rawQuery response for cloudflare-ech.com.
    val SVCB_QUERY_TYPE_RESPONSE = HexDump.hexStringToByteArray(
    """
    |8b8c818000010000000100000e636c6f7564666c6172652d65636803636f6d00004000
    |01c00c0006000100000708002f0466726564026e730a636c6f7564666c617265c01b03
    |646e73c0388e3df333000027100000096000093a8000000708
    """.trimMargin().replace("\n", ""))

    // This is a modified rawQuery cloudflare-ech.com response to have an invalid response type
    // of SVCB (64) instead of HTTPS (65).
    val INVALID_RESPONSE_TYPE = HexDump.hexStringToByteArray(
    """
    |da68818000010001000000000e636c6f7564666c6172652d65636803636f6d00004100
    |01c00c004000010000012c0088000100000100060268330268320004000868120a7668
    |120b76000500470045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
    |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
    |63682e636f6d00000006002026064700000000000000000068120a7626064700000000
    |000000000068120b76
    """.trimMargin().replace("\n", ""))

    val TEST_ECH_CONFIG_LIST = HexDump.hexStringToByteArray(
    """
    |0045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
    |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
    |63682e636f6d0000
    """.trimMargin().replace("\n", ""))

    private fun createErrorAccumulator(network: Network, callback: Callback<HttpsEndpoint>) =
        HttpsEndpointAccumulator(network, callback, /* queryCount= */ 1, QUERY_TIMEOUT_MS,
            /* hasIpv4= */ false, /* hasIpv6= */ false)

    private fun createOnAnswerAccumulator(network: Network, callback: Callback<HttpsEndpoint>) =
        HttpsEndpointAccumulator(network, callback, /* queryCount= */ 1, QUERY_TIMEOUT_MS,
            /* hasIpv4= */ true, /* hasIpv6= */ true)

    private fun createExpectErrorCallback(assertError: (input: DnsException) -> Unit)
      : Callback<HttpsEndpoint> {
      return object: Callback<HttpsEndpoint> {
        override fun onAnswer(response: HttpsEndpoint, rcode: Int) {
          fail("onAnswer should not be called, as we expect an exception to be thrown")
        }

        override fun onError(error: DnsException) {
          assertError(error)
        }
      }
    }

    private fun createExpectAnswerCallback(assertOnAnswer: (input: HttpsEndpoint) -> Unit)
        : Callback<HttpsEndpoint> {
      return object: Callback<HttpsEndpoint> {
        override fun onAnswer(response: HttpsEndpoint, rcode: Int) {
          assertOnAnswer(response)
        }

        override fun onError(error: DnsException) {
          fail("onError should not be called, as a valid response should be returned")
        }
      }
    }
  }
}
