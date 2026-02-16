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
import android.net.ConnectivityManager
import android.net.DnsResolver
import android.net.DnsResolver.Callback
import android.net.DnsResolver.DnsException
import android.net.InetAddresses
import android.net.LinkProperties
import android.net.Network
import android.net.ParseException
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock;
import android.os.TestLooperManager
import android.util.Log

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry

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
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

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
  @get:Rule val callbackRule: AutoReleaseNetworkCallbackRule = AutoReleaseNetworkCallbackRule()

  // Requires casting because Callback is a generic type and cannot be mocked with type directly.
  @SuppressWarnings("unchecked")
  private val mockUserCallback = mock(Callback::class.java) as Callback<HttpsEndpoint>
  private val endpointCaptor = ArgumentCaptor.forClass(HttpsEndpoint::class.java)
  private val exceptionCaptor = ArgumentCaptor.forClass(DnsException::class.java)
  private val context = InstrumentationRegistry.getInstrumentation().context
  private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

  private val handlerThread = HandlerThread("HttpsEndpointAccumulatorTest")
  private lateinit var handler: Handler
  private lateinit var testLooperManager: TestLooperManager

  @Before
  fun setUp() {
    handlerThread.start()
    val looper = handlerThread.looper
    handler = Handler(looper)
    testLooperManager = InstrumentationRegistry.getInstrumentation().acquireLooperManager(looper)
  }

  @After
  fun tearDown() {
    testLooperManager.release()
    handlerThread.quitSafely()
    handlerThread.join()
  }

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
  fun testOnAnswer_whenNoDataReturned_returnsEmptyResponse() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertTrue(response.httpsRecords.isEmpty())
    }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(NODATA_HTTPS_RESPONSE, /* rcode= */ 0)
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
          assertEquals("", targetName)
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
          assertEquals("", targetName)
        }
      }

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network

    val accumulator = createOnAnswerAccumulator(network, answerCallback)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testGetOwnerName_returnsCorrectValue() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()
    val answerCallback = createExpectAnswerCallback { response: HttpsEndpoint ->
        assertEquals(response.httpsRecords.size, 1)
        with(response.httpsRecords.first()) {
          assertEquals("cloudflare-ech.com", ownerName)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 2, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ false, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ true, handler)
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
    val linkProperties = connectivityManager.getLinkProperties(network)

    val accumulator = HttpsEndpointAccumulator(network, linkProperties, answerCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
  }

  @Test
  fun testOnAnswer_whenIpResponsesFirst_expectedResponseCount_onAnswerInvokedWithoutHttpsRecord() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 2, DnsResolver.HTTPS_QUERY_WAIT_NONE, /* hasIpv4= */ true,
        /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      // Verify that we haven't recorded the HTTPS record since we reached the expected number of
      // responses before the HTTPS response.
      assertTrue(httpsRecords.isEmpty())
    }
  }

  @Test
  fun testOnAnswer_whenHttpsResponseFirst_onAnswerInvokedEarlyWithHttpsRecordOnly() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE_DIFF_ADDRESS, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      // Verify that we haven't recorded the extra IP addresses from the AAAA record since we
      // received the HTTPS response first and returned early.
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      assertEquals(httpsRecords.size, 1)
      with(httpsRecords.first()) {
        assertEquals(1, priority)
        assertContentEquals(TEST_IP_HINTS, ipAddressHints)
      }
    }
  }

  @Test
  fun testOnAnswer_whenSameNumberResponsesAsExpected_onAnswerInvokedOnceWithAllRecords() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE_DIFF_ADDRESS, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      // Verify that we recorded the extra IP addresses from the AAAA record since we didn't
      // receive the HTTPS response first and return early.
      assertContentEquals(TEST_IP_HINTS_WITH_EXTRA_IPV6, ipAddresses)
      assertEquals(httpsRecords.size, 1)
      with(httpsRecords.first()) {
        assertEquals(1, priority)
        assertContentEquals(TEST_IP_HINTS, ipAddressHints)
      }
    }
  }

  @Test
  fun testOnAnswer_whenMoreResponsesThanExpected_onAnswerInvokedOnceWithFirstExpectedRecords() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    // These responses should be ignored, and the callback should only be invoked once without them.
    accumulator.onAnswer(VALID_MULTIPLE_HTTPS_RECORDS_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE_DIFF_ADDRESS, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      // Verify that we haven't recorded the extra IP addresses from the second AAAA record response
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      // Verify that we haven't recorded the extra HTTPS records that were returned in the last
      // response.
      assertEquals(httpsRecords.size, 1)
      with(httpsRecords.first()) {
        assertEquals(1, priority)
        assertContentEquals(TEST_IP_HINTS, ipAddressHints)
      }
    }
  }

  @Test
  fun testOnAnswer_whenNormalHttpsResponseAndParseError_onAnswerInvokedOnceWithHttpsRecord() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(INVALID_QUESTION_COUNT_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    // Because we received a successful HTTPS response, we expect onAnswer to be invoked due to the
    // early return, despite the expected query count not being reached and receiving a parse error.
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      assertEquals(httpsRecords.size, 1)
      with(httpsRecords.first()) {
        assertEquals(1, priority)
        assertContentEquals(TEST_IP_HINTS, ipAddressHints)
      }
    }
  }

  @Test
  fun testOnAnswer_whenNormalIpResponseAndParseError_onErrorInvokedOnce() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator= HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onAnswer(INVALID_QUESTION_COUNT_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)

    verify(mockUserCallback, never()).onAnswer(any(), anyInt())
    verify(mockUserCallback, times(1)).onError(exceptionCaptor.capture())
    // Check that the error is returned, and not a successful answer since we didn't reach the
    // expected number of responses before the parse error.
    with(exceptionCaptor.value) {
      assertEquals(code, DnsResolver.ERROR_PARSE)
      assertEquals(cause?.message, "Unexpected question count: 2")
    }
  }

  @Test
  fun testOnAnswer_whenNoHttpsResponseInWaitPeriod_onAnswerInvokedAfterTimeoutWithoutHttps() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, DnsResolver.HTTPS_QUERY_WAIT_AUTO, /* hasIpv4= */ true,
        /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    val startTime = SystemClock.uptimeMillis()
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    val nextMessage = testLooperManager.next()
    assertTrue(nextMessage.getWhen() - startTime >= DEFAULT_HTTPS_TIMEOUT_MS)
    // Execute the message to simulate we've exceeded the expected timeout.
    testLooperManager.execute(nextMessage)
    testLooperManager.recycle(nextMessage)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      // Verify that we haven't recorded the HTTPS record since we didn't receive a response before
      // the auto timeout.
      assertTrue(httpsRecords.isEmpty())
    }
  }

  @Test
  fun testOnAnswer_whenHttpsResponseInWaitPeriod_onAnswerInvokedWithHttps() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, DnsResolver.HTTPS_QUERY_WAIT_AUTO, /* hasIpv4= */ true,
        /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)
    val nextMessage = testLooperManager.next()
    // Verify executing the message does nothing since the accumulator should have already returned
    // the results.
    testLooperManager.execute(nextMessage)
    testLooperManager.recycle(nextMessage)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      assertEquals(httpsRecords.size, 1)
      with(httpsRecords.first()) {
        assertEquals(1, priority)
        assertContentEquals(TEST_IP_HINTS, ipAddressHints)
      }
    }
  }

  @Test
  fun testOnAnswer_whenHttpsResponseLast_noAdditionalWait_onAnswerInvokedWithoutHttps() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, DnsResolver.HTTPS_QUERY_WAIT_NONE, /* hasIpv4= */ true,
        /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_SINGLE_HTTPS_RECORD_RESPONSE, /* rcode= */ 0)

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, times(1)).onAnswer(endpointCaptor.capture(), anyInt())
    with(endpointCaptor.value) {
      assertContentEquals(TEST_IP_HINTS, ipAddresses)
      // Verify that we haven't recorded the HTTPS record since we received the response after the
      // address records and there is no wait period.
      assertTrue(httpsRecords.isEmpty())
    }
  }

  @Test
  fun testOnAnswer_whenNoHttpsResponse_waitUntilTimeoutMode_onAnswerNeverInvoked() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator = HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 3, DnsResolver.HTTPS_QUERY_WAIT_UNTIL_TIMEOUT, /* hasIpv4= */ true,
        /* hasIpv6= */ true, handler)
    accumulator.onAnswer(VALID_A_RECORD_RESPONSE, /* rcode= */ 0)
    accumulator.onAnswer(VALID_AAAA_RECORD_RESPONSE, /* rcode= */ 0)
    // We don't expect any messages since we haven't set a timeout
    assertFalse(testLooperManager.hasMessages(handler, /* object= */ null, /* what= */ 0))

    verify(mockUserCallback, never()).onError(any())
    verify(mockUserCallback, never()).onAnswer(any(), anyInt())
  }

  @Test
  fun testOnError_whenMultipleOnErrors_onErrorInvokedOnceWithFirstError() {
    val networkCallback = callbackRule.registerDefaultNetworkCallback()

    val network = networkCallback.eventuallyExpect<Event.Available>(NETWORK_TIMEOUT_MS).network
    val linkProperties = connectivityManager.getLinkProperties(network)
    val accumulator= HttpsEndpointAccumulator(network, linkProperties, mockUserCallback,
        /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)
    accumulator.onError(DnsException(DnsResolver.ERROR_SYSTEM, Exception("System exception")))
    accumulator.onError(DnsException(DnsResolver.ERROR_PARSE, Exception("Parse exception")))

    verify(mockUserCallback, never()).onAnswer(any(), anyInt())
    verify(mockUserCallback, times(1)).onError(exceptionCaptor.capture())
    // Check that the first error is returned, and not the latest one.
    with(exceptionCaptor.value) {
      assertEquals(code, DnsResolver.ERROR_SYSTEM)
      assertEquals(cause?.message, "System exception")
    }
  }

  private fun createErrorAccumulator(network: Network, callback: Callback<HttpsEndpoint>) =
      HttpsEndpointAccumulator(network, connectivityManager.getLinkProperties(network), callback,
          /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ false, /* hasIpv6= */ false,
          handler)

  private fun createOnAnswerAccumulator(network: Network, callback: Callback<HttpsEndpoint>) =
      HttpsEndpointAccumulator(network, connectivityManager.getLinkProperties(network), callback,
          /* queryCount= */ 1, QUERY_TIMEOUT_MS, /* hasIpv4= */ true, /* hasIpv6= */ true, handler)

  companion object {
    private const val NETWORK_TIMEOUT_MS = 10_000L
    private const val QUERY_TIMEOUT_MS = 1000

    private const val DEFAULT_PORT = 443
    // Copied here to avoid a dependency on HttpsEndpointAccumulator.
    private const val DEFAULT_HTTPS_TIMEOUT_MS = 50L

    val TEST_IP_HINTS_IPV4_ONLY = listOf(
        InetAddresses.parseNumericAddress("104.18.10.118"),
        InetAddresses.parseNumericAddress("104.18.11.118"))

    val TEST_IP_HINTS_IPV6_ONLY = listOf(
        InetAddresses.parseNumericAddress("2606:4700::6812:a76"),
        InetAddresses.parseNumericAddress("2606:4700::6812:b76"))

    val TEST_IP_HINTS = TEST_IP_HINTS_IPV4_ONLY + TEST_IP_HINTS_IPV6_ONLY
    val TEST_IP_HINTS_WITH_EXTRA_IPV6 = TEST_IP_HINTS_IPV4_ONLY + listOf(
        InetAddresses.parseNumericAddress("2606:4700::6812:c76"),
        InetAddresses.parseNumericAddress("2606:4700::6812:d76")) + TEST_IP_HINTS_IPV6_ONLY

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

    // This is a modified AAAA rawQuery response for cloudflare-ech.com to have different addresses
    // than what's in the HTTPS record.
    val VALID_AAAA_RECORD_RESPONSE_DIFF_ADDRESS = HexDump.hexStringToByteArray(
      """
      |9ec3818000010002000000000e636c6f7564666c6172652d65636803636f6d00001c
      |0001c00c001c00010000012c001026064700000000000000000068120c76c00c001c
      |00010000012c001026064700000000000000000068120d76
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

    // When the response contains no answer RRs (NODATA).
    val NODATA_HTTPS_RESPONSE = HexDump.hexStringToByteArray(
    """
    |da68818000010000000000000e636c6f7564666c6172652d65636803636f6d0000410001
    """.trimMargin().replace("\n", ""))

    val TEST_ECH_CONFIG_LIST = HexDump.hexStringToByteArray(
    """
    |0045fe0d0041860020002058a2172489f01dcd0ff39adf7a40f2e791
    |c72ba65d889ca06e8a4282a286710a0004000100010012636c6f7564666c6172652d65
    |63682e636f6d0000
    """.trimMargin().replace("\n", ""))

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
