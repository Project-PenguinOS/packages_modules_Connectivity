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

package android.net

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.UserHandle
import android.telecom.Call
import android.telecom.PhoneAccount
import android.telecom.PhoneAccountHandle
import android.telecom.TelecomManager
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mock
import org.mockito.ArgumentMatchers.anyBoolean
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Spy
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule

private const val TEST_PACKAGE_NAME = "com.example.ottapp"
private const val TEST_UID = 12345
private const val TEST_ACCOUNT_ID = "test_id"
private val TEST_USER_HANDLE: UserHandle = UserHandle.of(0)
private val TEST_SECONDARY_USER_ID = 10
private val TEST_SECONDARY_UID = 1012345
private val TEST_SECONDARY_USER_HANDLE: UserHandle = UserHandle.of(TEST_SECONDARY_USER_ID)


class ConnectivityCallListenerServiceTest {
    @get:Rule val mockito: MockitoRule = MockitoJUnit.rule()

    @Mock private lateinit var mockConnectivityManager: ConnectivityManager
    @Mock private lateinit var mockPackageManager: PackageManager
    @Mock private lateinit var mockTelecomManager: TelecomManager
    @Mock private lateinit var mockCall: Call
    @Mock private lateinit var mockCallDetails: Call.Details
    @Mock private lateinit var mockPhoneAccount: PhoneAccount
    @Mock private lateinit var mockContext: Context

    @Spy
    private lateinit var service: ConnectivityCallListenerService
    private lateinit var realPhoneAccountHandle: PhoneAccountHandle
    private lateinit var realComponentName: ComponentName

    @Before
    fun setUp() {
        // Stub the service's getSystemService to return our mocks
        doReturn(mockConnectivityManager).`when`(service).getSystemService(
                ConnectivityManager::class.java)
        doReturn(mockPackageManager).`when`(service).packageManager
        doReturn(mockTelecomManager).`when`(service).getSystemService(TelecomManager::class.java)
        // Stub the feature flag check to enable the OTT slicing path
        doReturn(true).`when`(mockConnectivityManager).isFeatureEnabled(
                ConnectivityManager.FEATURE_OTT_NETWORK_SLICING)

        // Mock any base context calls the spy might still make to the real object
        doReturn(mockContext).`when`(service).applicationContext

        service.onCreate()

        realComponentName = ComponentName(TEST_PACKAGE_NAME, "SomeClass")
        realPhoneAccountHandle = PhoneAccountHandle(realComponentName, TEST_ACCOUNT_ID,
                TEST_USER_HANDLE)

        doReturn(mockCallDetails).`when`(mockCall).details
        doReturn(realPhoneAccountHandle).`when`(mockCallDetails).accountHandle

        val appInfo = ApplicationInfo().apply { uid = TEST_UID }
        doReturn(appInfo).`when`(mockPackageManager)
                .getApplicationInfoAsUser(eq(TEST_PACKAGE_NAME), anyInt(), eq(TEST_USER_HANDLE))
        doReturn(mockPhoneAccount).`when`(mockTelecomManager)
                .getPhoneAccount(realPhoneAccountHandle)
    }

    private fun mockCallProperties(
            isTransactional: Boolean,
            hasSelfManagedProp: Boolean,
            hasSelfManagedCap: Boolean
    ) {
        doReturn(isTransactional).`when`(mockCallDetails)
                .hasProperty(Call.Details.PROPERTY_IS_TRANSACTIONAL)
        doReturn(hasSelfManagedProp).`when`(mockCallDetails)
                .hasProperty(Call.Details.PROPERTY_SELF_MANAGED)
        doReturn(hasSelfManagedCap).`when`(mockPhoneAccount)
                .hasCapabilities(PhoneAccount.CAPABILITY_SELF_MANAGED)
    }

    @Test
    fun onCallAdded_nullCall_isIgnored() {
        service.onCallAdded(null)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallAdded_notTransactional_isIgnored() {
        mockCallProperties(isTransactional = false, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallAdded_notSelfManaged_isIgnored() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = false,
                hasSelfManagedCap = false)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallAdded_selfManagedByProperty_notifiesCM() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = false)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager).onOttCallStateChanged(TEST_UID, true /* isAdd */)
    }

    @Test
    fun onCallAdded_selfManagedByCapability_notifiesCM() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = false,
                hasSelfManagedCap = true)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager).onOttCallStateChanged(TEST_UID, true /* isAdd */)
    }

    @Test
    fun onCallAdded_selfManagedByBoth_notifiesCM() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager).onOttCallStateChanged(TEST_UID, true /* isAdd */)
    }

    @Test
    fun onCallRemoved_nullCall_isIgnored() {
        service.onCallRemoved(null)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallRemoved_notTransactional_isIgnored() {
        mockCallProperties(isTransactional = false, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        service.onCallRemoved(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallRemoved_notSelfManaged_isIgnored() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = false,
                hasSelfManagedCap = false)
        service.onCallRemoved(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallRemoved_validOttCall_notifiesCM() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        service.onCallRemoved(mockCall)
        verify(mockConnectivityManager).onOttCallStateChanged(TEST_UID, false /* isAdd */)
    }

    @Test
    fun getUidFromCall_packageNotFound_isIgnored() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        doThrow(PackageManager.NameNotFoundException()).`when`(mockPackageManager)
                .getApplicationInfoAsUser(eq(TEST_PACKAGE_NAME), anyInt(), eq(TEST_USER_HANDLE))

        service.onCallAdded(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun getUidFromCall_nullAccountHandle_isIgnored() {
        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        doReturn(null).`when`(mockCallDetails).accountHandle

        service.onCallAdded(mockCall)
        verify(mockConnectivityManager, never()).onOttCallStateChanged(anyInt(), anyBoolean())
    }

    @Test
    fun onCallAdded_callFromSecondaryUser_notifiesCM() {
        val secondaryPhoneAccountHandle = PhoneAccountHandle(realComponentName, TEST_ACCOUNT_ID,
                TEST_SECONDARY_USER_HANDLE)
        doReturn(secondaryPhoneAccountHandle).`when`(mockCallDetails).accountHandle

        val appInfo = ApplicationInfo().apply { uid = TEST_SECONDARY_UID }
        doReturn(appInfo).`when`(mockPackageManager).getApplicationInfoAsUser(
                eq(TEST_PACKAGE_NAME), anyInt(), eq(TEST_SECONDARY_USER_HANDLE))

        mockCallProperties(isTransactional = true, hasSelfManagedProp = true,
                hasSelfManagedCap = true)
        service.onCallAdded(mockCall)
        verify(mockConnectivityManager)
                .onOttCallStateChanged(TEST_SECONDARY_UID, true /* isAdd */)
    }
}
