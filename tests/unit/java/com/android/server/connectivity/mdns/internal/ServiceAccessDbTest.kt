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

import android.content.Context
import android.util.ArraySet
import androidx.test.core.app.ApplicationProvider
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository.Service
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRunner
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.mock

private const val TEST_UID = 12345
private const val TEST_OTHER_UID = 12346
private const val TEST_PACKAGE = "com.example.app"
private const val SERVICE_NAME = "MyService"
private const val OTHER_SERVICE_NAME = "MyOtherService"
private const val SERVICE_TYPE = "_test._tcp"
private val TEST_SERVICE = Service(SERVICE_NAME, SERVICE_TYPE, /* needsSeenTimeRefresh= */false)
private const val TEST_TIMESTAMP = 1000L

@RunWith(DevSdkIgnoreRunner::class)
class ServiceAccessDbTest {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    private lateinit var context: Context
    private lateinit var clock: ServiceAccessDb.Clock
    private lateinit var dataDir: File
    private lateinit var db: ServiceAccessDb

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clock = mock(ServiceAccessDb.Clock::class.java)
        dataDir = Files.createTempDirectory("ServiceAccessDbTest").toFile()
        db = ServiceAccessDb(context, clock, dataDir)
        doReturn(TEST_TIMESTAMP).`when`(clock).currentTimeMillis()
    }

    @After
    fun tearDown() {
        db.close()
        dataDir.deleteRecursively()
    }

    @Test
    fun testAddAndGetAllowedServices() {
        db.refreshPackage(TEST_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)
        db.refreshPackage(TEST_OTHER_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_OTHER_UID, TEST_PACKAGE, OTHER_SERVICE_NAME, SERVICE_TYPE)

        val services = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(1, services.size)
        assertTrue(services.contains(TEST_SERVICE))
    }

    @Test
    fun testRecordServicesSeen() {
        db.refreshPackage(TEST_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        doReturn(TEST_TIMESTAMP + 1000L).`when`(clock).currentTimeMillis()
        val servicesToRecord = ArraySet<Service>()
        servicesToRecord.add(TEST_SERVICE)
        db.recordServicesSeen(TEST_UID, TEST_PACKAGE, servicesToRecord)

        // Verify that the service is still allowed
        val allowedServices = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertTrue(allowedServices.contains(TEST_SERVICE))
    }

    @Test
    fun testDeleteOlderEntries() {
        db.refreshPackage(TEST_UID, TEST_PACKAGE)

        db.addAllowedService(TEST_UID, TEST_PACKAGE, "service1", SERVICE_TYPE)
        doReturn(TEST_TIMESTAMP + 100L).`when`(clock).currentTimeMillis()
        db.addAllowedService(TEST_UID, TEST_PACKAGE, "service2", SERVICE_TYPE)
        doReturn(TEST_TIMESTAMP + 200L).`when`(clock).currentTimeMillis()
        db.addAllowedService(TEST_UID, TEST_PACKAGE, "service3", SERVICE_TYPE)

        // Keep only 2 entries. "service1" should be deleted.
        db.deleteOlderEntries(TEST_UID, TEST_PACKAGE, 2)

        val services = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(2, services.size)
        assertTrue(services.contains(Service("service2", SERVICE_TYPE,
            /* needsSeenTimeRefresh= */false)))
        assertTrue(services.contains(Service("service3", SERVICE_TYPE,
            /* needsSeenTimeRefresh= */false)))
    }

    @Test
    fun testDeleteAllEntriesForPackage() {
        db.refreshPackage(TEST_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        db.deleteAllEntriesForPackage(TEST_UID, TEST_PACKAGE)

        assertTrue(db.getAllowedServices(TEST_UID, TEST_PACKAGE).isEmpty())
    }

    @Test
    fun testGetLeastRefreshedPackages() {
        db.refreshPackage(1, "pkg1")
        doReturn(TEST_TIMESTAMP + 100L).`when`(clock).currentTimeMillis()
        db.refreshPackage(3, "pkg3")
        doReturn(TEST_TIMESTAMP + 200L).`when`(clock).currentTimeMillis()
        db.refreshPackage(2, "pkg2")
        doReturn(TEST_TIMESTAMP + 300L).`when`(clock).currentTimeMillis()
        // Same package name as pkg1, but different UID
        db.refreshPackage(4, "pkg1")
        // Refresh (1, pkg1)
        doReturn(TEST_TIMESTAMP + 400L).`when`(clock).currentTimeMillis()
        db.refreshPackage(1, "pkg1")

        val leastRefreshed = db.getLeastRefreshedPackages(3)
        assertEquals(3, leastRefreshed.size)
        assertEquals(3, leastRefreshed[0].uid)
        assertEquals("pkg3", leastRefreshed[0].packageName)
        assertEquals(2, leastRefreshed[1].uid)
        assertEquals("pkg2", leastRefreshed[1].packageName)
        assertEquals(4, leastRefreshed[2].uid)
        assertEquals("pkg1", leastRefreshed[2].packageName)
    }

    @Test
    fun testRefreshExistingPackage_keepsAllowedServices() {
        db.refreshPackage(TEST_UID, TEST_PACKAGE)
        db.addAllowedService(TEST_UID, TEST_PACKAGE, SERVICE_NAME, SERVICE_TYPE)

        db.refreshPackage(TEST_UID, TEST_PACKAGE)

        val allowed = db.getAllowedServices(TEST_UID, TEST_PACKAGE)
        assertEquals(setOf(Service(SERVICE_NAME, SERVICE_TYPE, /* needsSeenTimeRefresh= */false)),
            allowed)
    }
}
