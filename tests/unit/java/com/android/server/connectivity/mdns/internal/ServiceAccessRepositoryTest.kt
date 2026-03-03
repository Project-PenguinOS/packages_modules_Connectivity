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
package com.android.server.connectivity.mdns.internal

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import com.android.net.module.util.SharedLog
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository.LEAST_REFRESHED_PACKAGES_CHECK_COUNT
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository.PackageEntry
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository.Service
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.waitForIdle
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.any
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.eq
import org.mockito.Mock
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.MockitoAnnotations

private const val TEST_UID = 12345
private const val TEST_PACKAGE = "com.android.test.package"
private const val SERVICE_NAME = "MyService"
private const val SERVICE_TYPE = "_test._tcp"
private const val TIMEOUT_MS = 10_000L

@RunWith(AndroidJUnit4::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
class ServiceAccessRepositoryTest {
    @Mock
    private lateinit var context: Context
    @Mock
    private lateinit var packageManager: PackageManager
    @Mock
    private lateinit var clock: ServiceAccessDb.Clock
    private lateinit var handlerThread: HandlerThread
    private lateinit var testHandler: Handler
    private lateinit var repository: ServiceAccessRepository
    private lateinit var db: ServiceAccessDb

    @Before
    fun setUp() {
        MockitoAnnotations.openMocks(this)
        doReturn(context).`when`(context).createContextAsUser(any(), anyInt())
        doReturn(packageManager).`when`(context).packageManager
        handlerThread = HandlerThread(ServiceAccessRepositoryTest::class.java.simpleName)
        handlerThread.start()
        testHandler = Handler(handlerThread.looper)
        db = ServiceAccessDb(
            InstrumentationRegistry.getInstrumentation().context,
            clock,
            Files.createTempDirectory("ServiceAccessRepositoryTest").toFile()
        )
        repository = ServiceAccessRepository(
            context,
            handlerThread.looper,
            mock(SharedLog::class.java),
            db
        )
        repository.loadPackage(TEST_UID, TEST_PACKAGE)
    }

    @After
    fun tearDown() {
        handlerThread.quitSafely()
        handlerThread.join()
    }

    private fun waitForIdle() {
        testHandler.waitForIdle(TIMEOUT_MS)
    }

    @Test
    fun testAddAndQueryAllowedService() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_NoService_NotAllowedByDefault() {
        assertFalse(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_UidMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(
            repository.isServiceAllowed(TEST_UID + 1, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        )
    }

    @Test
    fun testServiceNotAllowed_PackageMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(
            repository.isServiceAllowed(TEST_UID, "other.package", SERVICE_NAME, SERVICE_TYPE)
        )
    }

    @Test
    fun testServiceNotAllowed_ServiceNameMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(
            repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, "Other service", SERVICE_TYPE)
        )
    }

    @Test
    fun testServiceNotAllowed_ServiceTypeMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(
            repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, "_othertype._tcp")
        )
    }

    @Test
    fun testCaseInsensitivity() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "MyService", "_test._TCP")

        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, "myservice", "_TEST._tcp"))
    }

    @Test
    fun testUnloadPackage() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE))
        waitForIdle()

        repository.unloadPackage(TEST_UID, TEST_PACKAGE)

        assertFalse(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testPersistence() {
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(1, allowed.size)
        val service = allowed.valueAt(0)
        assertEquals(Service(SERVICE_NAME, SERVICE_TYPE, /* needsSeenTimeRefresh= */false), service)
    }

    @Test
    fun testLoadPackage() {
        repository.unloadPackage(TEST_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        repository.loadPackage(TEST_UID, TEST_PACKAGE)

        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE))
    }

    private fun captureReceiver(): BroadcastReceiver {
        repository.start()
        val captor = ArgumentCaptor.forClass(BroadcastReceiver::class.java)
        verify(context).registerReceiver(captor.capture(), any())
        return captor.value
    }

    @Test
    fun testPackageRemoved() {
        val receiver = captureReceiver()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            putExtra(Intent.EXTRA_UID, TEST_UID)
            putExtra(Intent.EXTRA_REPLACING, false)
            putExtra(Intent.EXTRA_DATA_REMOVED, true)
            data = Uri.parse("package:$TEST_PACKAGE")
        }
        receiver.onReceive(context, intent)
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testPackageRemoved_KeepData() {
        val receiver = captureReceiver()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        waitForIdle()

        val intent = Intent(Intent.ACTION_PACKAGE_REMOVED).apply {
            putExtra(Intent.EXTRA_UID, TEST_UID)
            data = Uri.parse("package:$TEST_PACKAGE")
            putExtra(Intent.EXTRA_DATA_REMOVED, false)
        }
        receiver.onReceive(context, intent)
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(1, allowed.size)
    }

    @Test
    fun testPackageDataCleared() {
        val receiver = captureReceiver()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        val intent = Intent(Intent.ACTION_PACKAGE_DATA_CLEARED).apply {
            putExtra(Intent.EXTRA_UID, TEST_UID)
            data = Uri.parse("package:$TEST_PACKAGE")
        }
        receiver.onReceive(context, intent)
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testPackageDataCleared_DataClearedBroadcastBeforeAppDies() {
        val receiver = captureReceiver()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE)
        waitForIdle()

        val intent = Intent(Intent.ACTION_PACKAGE_DATA_CLEARED).apply {
            putExtra(Intent.EXTRA_UID, TEST_UID)
            data = Uri.parse("package:$TEST_PACKAGE")
        }
        receiver.onReceive(context, intent)

        // After the app data clear broadcast is received, simulate service allow and package unload
        // events being processed
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service2", SERVICE_TYPE)
        waitForIdle()
        repository.unloadPackage(TEST_UID, TEST_PACKAGE)
        waitForIdle()

        // The database should be empty as it did not have a package entry when addAllowedService
        // was called
        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testPackageDataCleared_DataClearedBroadcastAfterAppRestart() {
        // Simulate an app restart after adding a service
        val receiver = captureReceiver()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE)
        waitForIdle()
        repository.unloadPackage(TEST_UID, TEST_PACKAGE)
        repository.loadPackage(TEST_UID, TEST_PACKAGE)

        // Simulate the clear data broadcast being received after the app restarted, and a service
        // being added
        val intent = Intent(Intent.ACTION_PACKAGE_DATA_CLEARED).apply {
            putExtra(Intent.EXTRA_UID, TEST_UID)
            data = Uri.parse("package:$TEST_PACKAGE")
        }
        receiver.onReceive(context, intent)
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service2", SERVICE_TYPE)

        waitForIdle()

        // Verify in-memory state: both services should be there because "service1" was still
        // allowlisted when the app started (data clear broadcast was not processed yet), and
        // removing it from the allowlist while the app is running would be disruptive.
        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE))
        assertTrue(repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, "service2", SERVICE_TYPE))

        // Verify state on disk: services are not persisted until app restart, as the package entry
        // was deleted with the data clear broadcast.
        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testDatabaseMaintenance_RefreshesValidPackage() {
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        doReturn(TEST_UID).`when`(packageManager).getPackageUid(eq(TEST_PACKAGE), anyInt())

        repository.maybeScheduleDatabaseMaintenance()
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(1, allowed.size)
    }

    @Test
    fun testDatabaseMaintenance_DeletesRemovedPackage() {
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        doThrow(NameNotFoundException::class.java).`when`(packageManager).getPackageUid(
            eq(TEST_PACKAGE), anyInt())

        repository.maybeScheduleDatabaseMaintenance()
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testDatabaseMaintenance_DeletesReinstalledPackageWithNewUid() {
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        doReturn(TEST_UID + 1).`when`(packageManager).getPackageUid(eq(TEST_PACKAGE), anyInt())

        repository.maybeScheduleDatabaseMaintenance()
        waitForIdle()

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowed.isEmpty())
    }

    @Test
    fun testDatabaseMaintenance_ChecksAtMostConfiguredNumberOfPackages() {
        val basePkg = PackageEntry(TEST_UID, TEST_PACKAGE)
        // Add more packages so there's LEAST_REFRESHED_PACKAGES_CHECK_COUNT + 1 packages in total
        val additionalPackages = (1..LEAST_REFRESHED_PACKAGES_CHECK_COUNT).map {
            PackageEntry(TEST_UID + it, "pkg$it")
        }
        // Set different check times for each package to ensure order
        doReturn(1000L).`when`(clock).currentTimeMillis()
        db.refreshPackage(basePkg.uid, basePkg.packageName)
        additionalPackages.forEachIndexed { index, pkg ->
            doReturn(1000L + index + 1).`when`(clock).currentTimeMillis()
            db.refreshPackage(pkg.uid, pkg.packageName)
        }
        // Refresh the first additionalPackage one more time with the most recent timestamp
        doReturn(2000L).`when`(clock).currentTimeMillis()
        db.refreshPackage(additionalPackages[0].uid, additionalPackages[0].packageName)

        repository.maybeScheduleDatabaseMaintenance()
        waitForIdle()

        // Verify that only the first additionalPackage is not checked
        verify(packageManager).getPackageUid(eq(basePkg.packageName), anyInt())
        additionalPackages.forEachIndexed { index, pkg ->
            if (index == 0) {
                verify(packageManager, never()).getPackageUid(eq(pkg.packageName), anyInt())
            } else {
                verify(packageManager).getPackageUid(eq(pkg.packageName), anyInt())
            }
        }
    }

    @Test
    fun testRecordSeenTimeOnUnload() {
        doReturn(100L).`when`(clock).currentTimeMillis()
        repository.loadPackage(TEST_UID, TEST_PACKAGE)
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE)
        waitForIdle()
        doReturn(200L).`when`(clock).currentTimeMillis()
        repository.addAllowedService(TEST_UID, TEST_PACKAGE, "service2", SERVICE_TYPE)
        waitForIdle()

        repository.unloadPackage(TEST_UID, TEST_PACKAGE)
        repository.loadPackage(TEST_UID, TEST_PACKAGE)

        // Only see the first service (call isServiceAllowed) after reloading from disk
        doReturn(300L).`when`(clock).currentTimeMillis()
        repository.isServiceAllowed(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE)
        repository.unloadPackage(TEST_UID, TEST_PACKAGE)

        db.deleteOlderEntries(TEST_UID, TEST_PACKAGE, /* numEntriesToKeep= */1)
        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(1, allowed.size.toLong())
        // service2 was added later, but service1 was the last seen, so it should be the one kept
        assertEquals(Service("service1", SERVICE_TYPE, /* needsSeenTimeRefresh= */false),
            allowed.valueAt(0))
    }
}
