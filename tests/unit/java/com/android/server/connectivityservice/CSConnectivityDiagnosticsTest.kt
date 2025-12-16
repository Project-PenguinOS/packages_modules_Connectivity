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

package com.android.server

import android.net.IConnectivityDiagnosticsCallback
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.Mockito.atLeastOnce
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

private const val TIMEOUT_MS = 2000L

@RunWith(DevSdkIgnoreRunner::class)
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
class CSConnectivityDiagnosticsTest : CSTest() {

    private val mConnectivityDiagnosticsCallback =
        mock(IConnectivityDiagnosticsCallback::class.java)
    private val mIBinder = mock(IBinder::class.java)

    @Test
    fun testRegisterUnregisterConnectivityDiagnosticsCallback() {
        val wifiRequest = NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build()
        doReturn(mIBinder).`when`(mConnectivityDiagnosticsCallback).asBinder()

        service.registerConnectivityDiagnosticsCallback(
            mConnectivityDiagnosticsCallback,
            wifiRequest,
            context.packageName
        )

        // Block until all other events are done processing.
        waitForIdle()

        verify(
            mIBinder
        ).linkToDeath(
            any(ConnectivityService.ConnectivityDiagnosticsCallbackInfo::class.java),
            anyInt()
        )
        verify(mConnectivityDiagnosticsCallback).asBinder()
        assertTrue(service.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder))

        service.unregisterConnectivityDiagnosticsCallback(mConnectivityDiagnosticsCallback)
        verify(mIBinder, timeout(TIMEOUT_MS))
            .unlinkToDeath(
                any(ConnectivityService.ConnectivityDiagnosticsCallbackInfo::class.java),
                anyInt()
            )
        assertFalse(service.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder))
        verify(mConnectivityDiagnosticsCallback, atLeastOnce()).asBinder()
    }

    @Test
    fun testRegisterDuplicateConnectivityDiagnosticsCallback() {
        val wifiRequest = NetworkRequest.Builder().addTransportType(TRANSPORT_WIFI).build()
        doReturn(mIBinder).`when`(mConnectivityDiagnosticsCallback).asBinder()

        service.registerConnectivityDiagnosticsCallback(
            mConnectivityDiagnosticsCallback,
            wifiRequest,
            context.packageName
        )

        // Block until all other events are done processing.
        waitForIdle()

        verify(
            mIBinder
        ).linkToDeath(
            any(ConnectivityService.ConnectivityDiagnosticsCallbackInfo::class.java),
            anyInt()
        )
        verify(mConnectivityDiagnosticsCallback).asBinder()
        assertTrue(service.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder))

        // Register the same callback again
        service.registerConnectivityDiagnosticsCallback(
            mConnectivityDiagnosticsCallback,
            wifiRequest,
            context.packageName
        )

        // Block until all other events are done processing.
        waitForIdle()

        assertTrue(service.mConnectivityDiagnosticsCallbacks.containsKey(mIBinder))
    }
}
