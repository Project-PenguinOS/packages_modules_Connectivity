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

package com.android.server

import android.content.Context
import android.net.ConnectivityManager
import android.net.IConnectivityManager
import android.net.ProxyInfo
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify

@RunWith(JUnit4::class)
class ConnectivityManagerOnEarlyInitTest {

    private val mockContext = mock(Context::class.java)
    private val mockService = mock(IConnectivityManager::class.java)
    private lateinit var cm: ConnectivityManager

    @Before
    fun setup() {
        ConnectivityManager.resetEarlyInitCalledForTest()
        // Clear system properties before each test
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")

        cm = ConnectivityManager(mockContext, mockService)
    }

    @After
    fun tearDown() {
        System.clearProperty("http.proxyHost")
        System.clearProperty("http.proxyPort")
    }

    @Test
    fun onEarlyInit_noProxy() {
         doReturn(null).`when`(mockService).getProxyForNetwork(any())

        cm.onEarlyInit()

        verify(mockService, atLeastOnce()).getProxyForNetwork(any())
        assertNull(System.getProperty("http.proxyHost"))
    }

    @Test
    fun onEarlyInit_setsProxy() {
        val testProxy = ProxyInfo.buildDirectProxy("legacy.example.com", 8080)
        doReturn(testProxy).`when`(mockService).getProxyForNetwork(any())

        cm.onEarlyInit()

        verify(mockService, atLeastOnce()).getProxyForNetwork(any())
        assertEquals("legacy.example.com", System.getProperty("http.proxyHost"))
        assertEquals("8080", System.getProperty("http.proxyPort"))
    }

    @Test
    fun onEarlyInit_calledTwice_throwsException() {
         doReturn(ProxyInfo.buildDirectProxy("test.example.com", 8080))
            .`when`(mockService)
            .getProxyForNetwork(any())

        cm.onEarlyInit() // First call

        assertFailsWith<IllegalStateException> {
            cm.onEarlyInit() // Second call
        }
    }
}
