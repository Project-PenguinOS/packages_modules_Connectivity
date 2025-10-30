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
package com.android.server.connectivity

import android.Manifest
import android.annotation.SuppressLint
import android.app.role.OnRoleHoldersChangedListener
import android.app.role.RoleManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.UserHandle
import android.os.UserManager
import android.util.ArrayMap
import com.android.server.connectivity.ConnectivityFlags.CONSTRAINED_DATA_SATELLITE_OPTIN
import com.android.server.connectivity.AppOptInDefaultNetworkController.PER_USER_RANGE
import com.android.server.connectivity.AppOptInDefaultNetworkController.PROPERTY_SATELLITE_DATA_OPTIMIZED
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule
import com.android.testutils.com.android.testutils.SetFeatureFlagsRule.FeatureFlag
import com.google.common.truth.Truth.assertThat
import java.util.Collections.emptyList
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executor
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Captor
import org.mockito.junit.MockitoJUnit
import org.mockito.junit.MockitoRule
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import java.util.function.Consumer


private const val PRIMARY_USER = 0
private const val SECONDARY_USER = 10
private val PRIMARY_USER_HANDLE = UserHandle.of(PRIMARY_USER)
private val SECONDARY_USER_HANDLE = UserHandle.of(SECONDARY_USER)

// sms app names
private const val SMS_APP1 = "sms_app_1"
private const val SMS_APP2 = "sms_app_2"

// sms app ids
private const val SMS_APP_ID1 = 100
private const val SMS_APP_ID2 = 101

private fun Int.toUid(userId: Int) = UserHandle.getUid(userId, this)
private fun Int.getUserId() = this / PER_USER_RANGE

private const val TEST_PACKAGE1 = "com.android.package1"
private const val TEST_PACKAGE2 = "com.android.package2"
private const val TEST_UID1 = 2001
private const val TEST_UID2 = 2002 + SECONDARY_USER * PER_USER_RANGE // Under 2nd user.

@SuppressLint("VisibleForTests", "MissingPermission")
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class AppOptInDefaultNetworkControllerTest {
    @get:Rule
    val mockito: MockitoRule = MockitoJUnit.rule()
    private val context = mock(Context::class.java)
    private val deps = mock(AppOptInDefaultNetworkController.Dependencies::class.java)
    @Suppress("UNCHECKED_CAST")
    private val callback = mock(Consumer::class.java) as Consumer<List<AppOptInDefaultNetworkPolicy>>
    @Captor
    private lateinit var policiesCaptor: ArgumentCaptor<List<AppOptInDefaultNetworkPolicy>>
    private val userManager = mock(UserManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var appOptInDefaultNetworkController: AppOptInDefaultNetworkController
    private lateinit var roleHolderChangedListener: OnRoleHoldersChangedListener
    private val mockedPackageManagerForUser = ArrayMap<Int, PackageManager>()

    private val featureFlags = HashSet<String>()

    // This will set feature flags from @FeatureFlag annotations
    // into the map before setUp() runs.
    @get:Rule
    val setFeatureFlagsRule = SetFeatureFlagsRule(
            { name, enabled ->
                if (enabled == true) featureFlags.add(name) else featureFlags.remove(name) },
            { name -> featureFlags.contains(name) }
    )

    private fun <T> mockService(name: String, clazz: Class<T>, service: T) {
        doReturn(name).`when`(context).getSystemServiceName(clazz)
        doReturn(service).`when`(context).getSystemService(name)
        if (context.getSystemService(clazz) == null) {
            // Test is using mockito-extended
            doReturn(service).`when`(context).getSystemService(clazz)
        }
    }

    private fun mockPackageManagerForUser(userId: Int): PackageManager =
            mockedPackageManagerForUser.getOrPut(userId) {
                val userHandle = UserHandle.of(userId)
                val contextAsUser = mock(Context::class.java)
                val packageManager = mock(PackageManager::class.java)
                doReturn(contextAsUser).`when`(context).createContextAsUser(userHandle, 0)
                doReturn(packageManager).`when`(contextAsUser).packageManager
                packageManager
            }

    private fun getMockedPackageManagerForUser(userId: Int) = mockedPackageManagerForUser[userId]!!

    @Before
    fun setup() {
        doReturn(emptyList<UserHandle>()).`when`(userManager).getUserHandles(true)
        mockService(Context.USER_SERVICE, UserManager::class.java, userManager)
        doReturn(featureFlags.contains(CONSTRAINED_DATA_SATELLITE_OPTIN))
                .`when`(deps).supportConstrainedDataSatelliteOptIn(any())
        appOptInDefaultNetworkController = AppOptInDefaultNetworkController(
                context, deps, callback, handler)

        mockPackageManagerForUser(PRIMARY_USER)
        mockPackageManagerForUser(SECONDARY_USER)

        for (app in listOf(SMS_APP1, SMS_APP2)) {
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(getMockedPackageManagerForUser(PRIMARY_USER))
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
            doReturn(PackageManager.PERMISSION_GRANTED)
                .`when`(getMockedPackageManagerForUser(SECONDARY_USER))
                .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, app)
        }

        for ((appName, appId) in listOf(
            SMS_APP1 to SMS_APP_ID1,
            SMS_APP2 to SMS_APP_ID2
        )) {
            val primaryUid = appId.toUid(PRIMARY_USER)
            val primaryAppInfo = ApplicationInfo().apply { uid = primaryUid }
            doReturn(primaryAppInfo)
                    .`when`(getMockedPackageManagerForUser(PRIMARY_USER))
                    .getApplicationInfo(eq(appName), anyInt())
            val secondaryUid = appId.toUid(SECONDARY_USER)
            val secondaryAppInfo = ApplicationInfo().apply { uid = secondaryUid }
            doReturn(secondaryAppInfo)
                    .`when`(getMockedPackageManagerForUser(SECONDARY_USER))
                    .getApplicationInfo(eq(appName), anyInt())
        }
    }

    @Test
    fun testRoleHoldersChanged_satelliteRoleSmsUidChanged_singleUser() {
        startAppOptInDefaultNetworkController()
        doReturn(listOf<String>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())

        // check DEFAULT_MESSAGING_APP1 is available as satellite network fallback uid
        doReturn(listOf(SMS_APP1))
            .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with the correct policy info object.
        verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check SMS_APP2 is available as satellite network Fallback uid
        doReturn(listOf(SMS_APP2)).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with the updated policy info.
        verify(callback, times(2)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check no uid is available as satellite network fallback uid
        doReturn(listOf<String>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(callback).accept(emptyList())
    }

    @Test
    fun testRoleHoldersChanged_noSatelliteCommunicationPermission() {
        startAppOptInDefaultNetworkController()
        doReturn(listOf<Any>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())

        // Check DEFAULT_MESSAGING_APP1 is not available as satellite network fallback uid
        // since satellite communication permission not available.
        doReturn(PackageManager.PERMISSION_DENIED)
            .`when`(getMockedPackageManagerForUser(PRIMARY_USER))
            .checkPermission(Manifest.permission.SATELLITE_COMMUNICATION, SMS_APP1)
        doReturn(listOf(SMS_APP1))
            .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())
    }

    @Test
    fun testRoleHoldersChanged_roleSms_notAvailable() {
        startAppOptInDefaultNetworkController()
        doReturn(listOf(SMS_APP1))
            .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_BROWSER, PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())
    }

    @Test
    fun testRoleHoldersChanged_satelliteRoleSmsUidChanged_multiUser() {
        startAppOptInDefaultNetworkController()
        doReturn(listOf<String>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())

        // check SMS_APP1 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP1))
            .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with the correct policy for the primary user.
        verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with the updated policy for the primary user.
        verify(callback, times(2)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        // Verify the callback contains both UIDs, grouped under the same policy.
        verify(callback, times(3)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER), SMS_APP_ID1.toUid(SECONDARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check no uid is available as satellite network fallback uid at primary user
        doReturn(listOf<String>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the primary user's UID has been removed.
        verify(callback, times(4)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(SECONDARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check SMS_APP2 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP2))
            .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        // Verify the secondary user's UID has been updated.
        verify(callback, times(5)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(SECONDARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check no uid is available as satellite network fallback uid at secondary user
        doReturn(listOf<String>()).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        // The final call should be with an empty list, as there are no more UIDs with policies.
        verify(callback).accept(emptyList())
    }

    @Test
    fun testSatelliteFallbackUidCallback_onUserRemoval() {
        startAppOptInDefaultNetworkController()
        // check SMS_APP2 is available as satellite network fallback uid at primary user
        doReturn(listOf(SMS_APP2)).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            PRIMARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with the correct policy for the primary user.
        verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // check SMS_APP1 is available as satellite network fallback uid at secondary user
        doReturn(listOf(SMS_APP1)).`when`(deps).getRoleHoldersAsUser(
            RoleManager.ROLE_SMS,
            SECONDARY_USER_HANDLE
        )
        onRoleHoldersChanged(RoleManager.ROLE_SMS, SECONDARY_USER_HANDLE)
        // Verify the callback now contains both UIDs, grouped under the same policy.
        verify(callback, times(2)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER), SMS_APP_ID1.toUid(SECONDARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onUserRemoved(SECONDARY_USER_HANDLE)
        // Verify the secondary user's UID has been removed from the policy.
        verify(callback, times(3)).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID2.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
    }

    private fun <T : Any> processOnHandlerThread(function: () -> T): T {
        val future = CompletableFuture<T>()
        handler.post { future.complete(function()) }
        return future.get()
    }

    private fun onRoleHoldersChanged(roleName: String, userHandle: UserHandle) =
        processOnHandlerThread {
            roleHolderChangedListener.onRoleHoldersChanged(roleName, userHandle)
        }

    private fun onUserAddedWithInstalledPackageList(
            userHandle: UserHandle,
            apps: List<PackageInfo>
    ) = processOnHandlerThread {
        appOptInDefaultNetworkController.onUserAddedWithInstalledPackageList(userHandle, apps)
    }

    private fun onUserRemoved(userHandle: UserHandle) = processOnHandlerThread {
        appOptInDefaultNetworkController.onUserRemoved(userHandle)
    }

    private fun onPackageAdded(packageName: String, uid: Int) =
            processOnHandlerThread {
                appOptInDefaultNetworkController.onPackageAdded(packageName, uid) }

    private fun onPackageRemoved(packageName: String, uid: Int) =
            processOnHandlerThread {
                appOptInDefaultNetworkController.onPackageRemoved(packageName, uid) }

    private fun onExternalApplicationsAvailable(pkgList: Array<String>) =
            processOnHandlerThread {
                appOptInDefaultNetworkController.onExternalApplicationsAvailable(pkgList)
            }

    private fun startAppOptInDefaultNetworkController() {
        appOptInDefaultNetworkController.start()
        // Get registered listener using captor
        val listenerCaptor = ArgumentCaptor.forClass(OnRoleHoldersChangedListener::class.java)
        verify(deps).addOnRoleHoldersChangedListenerAsUser(
            any(Executor::class.java),
            listenerCaptor.capture(),
            any(UserHandle::class.java)
        )
        roleHolderChangedListener = listenerCaptor.value
    }

    private fun makePackageInfo(packageName: String, uid: Int) = PackageInfo().apply {
        this.packageName = packageName
        applicationInfo = ApplicationInfo().apply { this.uid = uid }
    }

    private fun mockGetPackagesForUid(uid: Int, pkgs: Array<String>?) {
        val pm = mockPackageManagerForUser(uid.getUserId())
        `when`(pm.getPackagesForUid(uid)).thenReturn(pkgs)
    }

    private fun mockIsSatelliteDataOptimizedAppForUser(
            userId: Int,
            packageName: String,
            isOptimized: Boolean
    ) {
        val appInfo = ApplicationInfo()
        if (isOptimized) {
            appInfo.metaData = Bundle()
            appInfo.metaData.putString(PROPERTY_SATELLITE_DATA_OPTIMIZED, packageName)
        }
        val pm = mockPackageManagerForUser(userId)
        doReturn(appInfo).`when`(pm).getApplicationInfo(packageName, PackageManager.GET_META_DATA)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onPackageAdded() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was called with the correct policy info object.
        verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_invalidMetaData() {
        val appInfoWithBoolean = ApplicationInfo()
        appInfoWithBoolean.metaData = Bundle()
        appInfoWithBoolean.metaData.putBoolean(PROPERTY_SATELLITE_DATA_OPTIMIZED, true)
        val pm = mockPackageManagerForUser(TEST_UID1.getUserId())
        doReturn(appInfoWithBoolean).`when`(pm)
                .getApplicationInfo(TEST_PACKAGE1, PackageManager.GET_META_DATA)
        doReturn(PackageInfo()).`when`(pm).getPackageInfo(
                eq(TEST_PACKAGE1),
                eq(
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or
                        PackageManager.MATCH_DISABLED_COMPONENTS
                )
        )
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        verify(callback, never()).accept(any())

        val appInfoWithWrongPackage = ApplicationInfo()
        appInfoWithWrongPackage.metaData = Bundle()
        appInfoWithWrongPackage.metaData
                .putString(PROPERTY_SATELLITE_DATA_OPTIMIZED, "wrong package")
        doReturn(appInfoWithWrongPackage).`when`(pm)
                .getApplicationInfo(TEST_PACKAGE1, PackageManager.GET_META_DATA)

        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        verify(callback, never()).accept(any())
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_withServiceMetaData() {
        val pm = mockPackageManagerForUser(TEST_UID1.getUserId())
        // Return an ApplicationInfo without meta-data to ensure it falls back to service check.
        val appInfo = ApplicationInfo()
        doReturn(appInfo).`when`(pm)
                .getApplicationInfo(TEST_PACKAGE1, PackageManager.GET_META_DATA)

        val packageInfo = PackageInfo()
        val serviceInfo = android.content.pm.ServiceInfo()
        serviceInfo.metaData = Bundle()
        serviceInfo.metaData.putString(PROPERTY_SATELLITE_DATA_OPTIMIZED, TEST_PACKAGE1)
        packageInfo.services = arrayOf(serviceInfo)
        doReturn(packageInfo).`when`(pm).getPackageInfo(
                TEST_PACKAGE1,
                PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or
                        PackageManager.MATCH_DISABLED_COMPONENTS
        )

        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was called with the correct policy info object.
        verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onPackageAdded_ignoresIfNotSatelliteOptimized() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, false)
        val pm = mockPackageManagerForUser(TEST_UID1.getUserId())
        val packageInfo = PackageInfo()
        val serviceInfo = android.content.pm.ServiceInfo()
        packageInfo.services = arrayOf(serviceInfo)
        doReturn(packageInfo).`when`(pm).getPackageInfo(
                eq(TEST_PACKAGE1),
                eq(
                    PackageManager.GET_SERVICES or PackageManager.GET_META_DATA or
                        PackageManager.MATCH_DISABLED_COMPONENTS
                )
        )
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was never called
        verify(callback, never()).accept(any())
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onPackageRemoved_noOtherShareUid() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        mockGetPackagesForUid(TEST_UID1, arrayOf(TEST_PACKAGE1))

        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was called with the correct policy info object after the
        // first package add.
        verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        verify(callback).accept(emptyList())
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onPackageRemoved_otherShareUid() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE2, true)
        mockGetPackagesForUid(TEST_UID1, arrayOf(TEST_PACKAGE1, TEST_PACKAGE2))

        // Verify uid is not removed if there is still another package shares the same uid.
        val inOrder = inOrder(callback)
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was called with the correct policy info object after the first
        // package add.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        inOrder.verifyNoMoreInteractions()

        // Verify uid is removed if there is no other package with shared uid.
        mockGetPackagesForUid(TEST_UID1, null)
        onPackageRemoved(TEST_PACKAGE2, TEST_UID1)
        inOrder.verify(callback).accept(emptyList())
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onUserAddedRemoved() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID2.getUserId(), TEST_PACKAGE2, true)
        val packageInfo1 = makePackageInfo(TEST_PACKAGE1, TEST_UID1)
        val packageInfo2 = makePackageInfo(TEST_PACKAGE2, TEST_UID2)

        val inOrder = inOrder(callback)
        onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, listOf(packageInfo1))
        // Verify the callback for the first user addition.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onUserAddedWithInstalledPackageList(SECONDARY_USER_HANDLE, listOf(packageInfo2))
        // Verify the callback for the second user addition. The UIDs should be merged.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1, TEST_UID2)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onUserRemoved(SECONDARY_USER_HANDLE)
        // Verify that the app associated with the non-removed user is not removed.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onUserRemoved(PRIMARY_USER_HANDLE)
        // Verify everything is removed.
        inOrder.verify(callback).accept(emptyList())
        inOrder.verifyNoMoreInteractions()
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN, enabled = false)
    @Test
    fun testSatelliteOptInUids_featureDisabled() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        val packageInfo1 = makePackageInfo(TEST_PACKAGE1, TEST_UID1)

        // Verify nothing changes and nothing crashes.
        onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, listOf(packageInfo1))
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        onPackageRemoved(TEST_PACKAGE1, TEST_UID1)
        onExternalApplicationsAvailable(arrayOf(SMS_APP1, SMS_APP2))
        onUserRemoved(PRIMARY_USER_HANDLE)
        verify(callback, never()).accept(any())
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_withRoleSmsUids() {
        startAppOptInDefaultNetworkController()
        val inOrder = inOrder(callback)

        // Set SMS_APP1 under primary user as a role-sms Uid.
        doReturn(listOf(SMS_APP1))
                .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        // Verify the callback was called with a list containing one policy for the SMS UID.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // Mock another opt-in uid, verify they both reported via the callback.
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the callback was called with a list containing two distinct policy objects.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )

        val expectedPolicy1 = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy, expectedPolicy1)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onUserAddedWithRoleSmsUids() {
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        val packageInfo1 = makePackageInfo(TEST_PACKAGE1, TEST_UID1)
        doReturn(listOf(SMS_APP1))
                .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)

        val inOrder = inOrder(callback)
        onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, listOf(packageInfo1))
        // Verify the callback was called list containing two distinct policy objects.
        inOrder.verify(callback).accept(policiesCaptor.capture())

        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )

        val expectedPolicy1 = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )

        assertThat(policiesCaptor.value).containsExactly(expectedPolicy, expectedPolicy1)
        inOrder.verifyNoMoreInteractions()
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_withRoleSmsUids_overlappedUid() {
        startAppOptInDefaultNetworkController()
        val smsUid = SMS_APP_ID1.toUid(PRIMARY_USER)

        val inOrder = inOrder(callback)
        // Mock opt-in uids, verify they both reported via the callback.
        // However, one opt-in uid is a messaging app and will surprise us later.
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), SMS_APP1, true)
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the first opt-in UID is added.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        onPackageAdded(SMS_APP1, smsUid)
        // Verify the second opt-in UID is added to the same policy group.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1, smsUid)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // Set SMS_APP1 as a role-sms Uid.
        doReturn(listOf(SMS_APP1))
                .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        inOrder.verify(callback).accept(policiesCaptor.capture())

        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(smsUid)
        )

        val expectedPolicy1 = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )

        assertThat(policiesCaptor.value).containsExactly(expectedPolicy, expectedPolicy1)

        // Unset SMS_APP1 as the role-sms Uid.
        // Verify the role-sms Uid is included to the opt-in Uid list again.
        doReturn(emptyList<String>())
                .`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1, smsUid)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testSatelliteOptInUids_onExternalApplicationsAvailable() {
        // Mock the sms apps as general opt-in apps without setting role-sms.
        mockIsSatelliteDataOptimizedAppForUser(PRIMARY_USER, SMS_APP1, true)
        mockIsSatelliteDataOptimizedAppForUser(SECONDARY_USER, SMS_APP1, true)
        mockIsSatelliteDataOptimizedAppForUser(PRIMARY_USER, SMS_APP2, true)
        mockIsSatelliteDataOptimizedAppForUser(SECONDARY_USER, SMS_APP2, true)
        doReturn(listOf(PRIMARY_USER_HANDLE, SECONDARY_USER_HANDLE))
                .`when`(userManager).getUserHandles(true)

        val inOrder = inOrder(callback)
        onUserAddedWithInstalledPackageList(PRIMARY_USER_HANDLE, emptyList())
        onUserAddedWithInstalledPackageList(SECONDARY_USER_HANDLE, emptyList())
        onExternalApplicationsAvailable(arrayOf(SMS_APP1, SMS_APP2))
        // Verify the callback was called once with a list containing a single policy object
        // that groups all four UIDs under the "satellite opt-in" policy.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(
                        SMS_APP_ID1.toUid(PRIMARY_USER),
                        SMS_APP_ID1.toUid(SECONDARY_USER),
                        SMS_APP_ID2.toUid(PRIMARY_USER),
                        SMS_APP_ID2.toUid(SECONDARY_USER)
                )
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun testSmsRoleAddedAndRemoved() {
        startAppOptInDefaultNetworkController()
        val uid1 = SMS_APP_ID1.toUid(PRIMARY_USER)
        val inOrder = inOrder(callback)

        // Add SMS role
        doReturn(listOf(SMS_APP1)).`when`(deps).getRoleHoldersAsUser(
                RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)

        inOrder.verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(SMS_APP_ID1.toUid(PRIMARY_USER))
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // Remove SMS role
        doReturn(emptyList<String>()).`when`(deps).getRoleHoldersAsUser(
                RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        inOrder.verify(callback).accept(emptyList())
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun testOttCallAddedAndRemoved_StandardUid() {
        startAppOptInDefaultNetworkController()
        val inOrder = inOrder(callback)

        // Add OTT Call for the uid
        processOnHandlerThread {
            appOptInDefaultNetworkController.onOttCallStateChanged(TEST_UID1, true /*isAdded*/)
        }

        // Verify callback with the new OTT policy
        inOrder.verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // 2. Remove the OTT call
        processOnHandlerThread {
            appOptInDefaultNetworkController.onOttCallStateChanged(TEST_UID1, false /*isAdded*/)

        }

        // Verify the callback is invoked with an empty list again
        inOrder.verify(callback).accept(emptyList())
        inOrder.verifyNoMoreInteractions()
    }

    @Test
    fun testOttCall_withSmsRole_hasCorrectPolicy() {
        startAppOptInDefaultNetworkController()
        val uid1 = SMS_APP_ID1.toUid(PRIMARY_USER)
        val inOrder = inOrder(callback)

        // Add SMS role
        doReturn(listOf(SMS_APP1)).`when`(deps).getRoleHoldersAsUser(
                RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, PRIMARY_USER_HANDLE)

        inOrder.verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(uid1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // 2. Start an OTT call for the same UID
        processOnHandlerThread {
            appOptInDefaultNetworkController.onOttCallStateChanged(uid1, true /*isAdded*/)
        }

        // Verify the new policy has both OTT and SMS flags set
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(uid1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
        inOrder.verifyNoMoreInteractions()
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testOttCall_withOptIn_hasCorrectPolicy() {
        startAppOptInDefaultNetworkController()
        val inOrder = inOrder(callback)

        // Mock opt-in uids
        mockIsSatelliteDataOptimizedAppForUser(TEST_UID1.getUserId(), TEST_PACKAGE1, true)
        onPackageAdded(TEST_PACKAGE1, TEST_UID1)
        // Verify the first opt-in UID is added.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        var expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)

        // 2. Start an OTT call for the same UID
        processOnHandlerThread {
            appOptInDefaultNetworkController.onOttCallStateChanged(TEST_UID1, true /*isAdded*/)
        }

        // Verify the new policy has both OTT and OptIn flags set
        inOrder.verify(callback).accept(policiesCaptor.capture())
        expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(TEST_UID1)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
        inOrder.verifyNoMoreInteractions()
    }

    @FeatureFlag(name = CONSTRAINED_DATA_SATELLITE_OPTIN)
    @Test
    fun testOttCall_withSmsAndOptIn_hasCorrectPolicy() {
        startAppOptInDefaultNetworkController()
        val uidAll = SMS_APP_ID1.toUid(PRIMARY_USER)
        val pkgAll = SMS_APP1
        val userHandle = UserHandle.getUserHandleForUid(uidAll)
        val inOrder = inOrder(callback)

        // Step 1: Set up mocks for the package to be an Opt-in app and have the SMS role.
        mockIsSatelliteDataOptimizedAppForUser(uidAll.getUserId(), pkgAll, true)
        mockGetPackagesForUid(uidAll, arrayOf(pkgAll))
        doReturn(listOf(pkgAll)).`when`(deps).getRoleHoldersAsUser(RoleManager.ROLE_SMS, userHandle)

        // Step 2: Trigger initial state changes by adding the package and setting it as SMS role
        // holder.
        onPackageAdded(pkgAll, uidAll)
        onRoleHoldersChanged(RoleManager.ROLE_SMS, userHandle)
        // Verify callbacks were made for the initial state changes.
        inOrder.verify(callback, times(2)).accept(any())

        // Step 3: Start an OTT call for the same UID.
        processOnHandlerThread {
            appOptInDefaultNetworkController.onOttCallStateChanged(uidAll, true /*isAdded*/)
        }

        // Step 4: Verify the final policy has all three flags (OptIn, SMS, OTT) set for the UID.
        inOrder.verify(callback).accept(policiesCaptor.capture())
        val expectedPolicy = AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(uidAll)
        )
        assertThat(policiesCaptor.value).containsExactly(expectedPolicy)
        inOrder.verifyNoMoreInteractions()
    }
}
