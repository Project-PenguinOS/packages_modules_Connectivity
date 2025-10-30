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

package android.net.cts

import android.content.ComponentName
import android.os.Bundle
import android.os.Process
import android.telecom.Connection
import android.telecom.ConnectionRequest
import android.telecom.ConnectionService
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val TEST_TIMEOUT_MS = 5000L

@RunWith(AndroidJUnit4::class)
@SmallTest
class ConnectivityCallListenerServiceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val telecomManager = context.getSystemService(TelecomManager::class.java)!!
    private lateinit var phoneAccountHandle: PhoneAccountHandle
    private val myUid = Process.myUid()

    @Before
    fun setUp() {
        // Set up a self-managed PhoneAccount to simulate an OTT calling app.
        phoneAccountHandle = PhoneAccountHandle(
            ComponentName(context, TestConnectionService::class.java),
            "CtsTestAccount_$myUid"
        )
        val phoneAccount = PhoneAccount.builder(phoneAccountHandle, "CTS Test")
            .setCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
            .addSupportedUriScheme("tel")
            .build()

        telecomManager.registerPhoneAccount(phoneAccount)
    }

    @After
    fun tearDown() {
        if (this::phoneAccountHandle.isInitialized) {
            telecomManager.unregisterPhoneAccount(phoneAccountHandle)
        }
        TestConnectionService.destroyCurrentConnection()
    }

    class TestConnectionService : ConnectionService() {
        companion object {
            private var connectionFuture = CompletableFuture<Connection>()
            fun getNewConnectionCompletableFuture(): CompletableFuture<Connection> {
                connectionFuture = CompletableFuture<Connection>()
                return connectionFuture
            }
            fun destroyCurrentConnection() {
                connectionFuture.getNow(null)?.destroy()
            }
        }

        override fun onCreateOutgoingConnection(
            connectionManagerAccount: PhoneAccountHandle,
            request: ConnectionRequest
        ): Connection {
            val conn = object : Connection() {}
            conn.setAddress(request.address, TelecomManager.PRESENTATION_ALLOWED)
            conn.setActive()
            connectionFuture.complete(conn)
            return conn
        }
    }

    private fun placeCall(): Connection {
        val future = TestConnectionService.getNewConnectionCompletableFuture()
        instrumentation.runOnMainSync {
            val extras = Bundle()
            extras.putParcelable(TelecomManager.EXTRA_PHONE_ACCOUNT_HANDLE, phoneAccountHandle)
            telecomManager.placeCall(android.net.Uri.parse("tel:12345"), extras)
        }
        return future.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    @Test
    fun testCallLifecycleCallbacks() {
        // This function calls future.get(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS).
        // If a problem in the onCallAdded callback prevents the call from connecting
        // within 5 seconds, it will throw a TimeoutException and fail the test.
        // This acts as an implicit assertion that the call was successfully established.
        val connection = placeCall()

        // This triggers the onCallRemoved callback.
        // If the service crashes at any point during this process, the test will fail.
        connection.destroy()
    }

// TODO(b/430509489): Add tests for network prioritization effects once
// ConnectivityCallListenerService is fully integrated with ConnectivityService in subsequent CLs
}
