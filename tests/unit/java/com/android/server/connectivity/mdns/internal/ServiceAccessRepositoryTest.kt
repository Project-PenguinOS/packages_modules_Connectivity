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

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.net.module.util.SharedLog
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.mock

private const val TEST_UID = 12345
private const val SERVICE_NAME = "MyService"
private const val SERVICE_TYPE = "_test._tcp"

@RunWith(AndroidJUnit4::class)
@SmallTest
class ServiceAccessRepositoryTest {
    private lateinit var repository: ServiceAccessRepository

    @Before
    fun setUp() {
        repository = ServiceAccessRepository(mock(SharedLog::class.java))
    }

    @Test
    fun testAddAndQueryAllowedService() {
        repository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE)

        assertTrue(repository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_NoService_NotAllowedByDefault() {
        assertFalse(repository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_UidMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(repository.isServiceAllowed(TEST_UID + 1, SERVICE_NAME, SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_ServiceNameMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(repository.isServiceAllowed(TEST_UID, "Other service", SERVICE_TYPE))
    }

    @Test
    fun testServiceNotAllowed_ServiceTypeMismatch_NotAllowed() {
        repository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE)

        assertFalse(repository.isServiceAllowed(TEST_UID, SERVICE_NAME, "_othertype._tcp"))
    }

    @Test
    fun testCaseInsensitivity() {
        repository.addAllowedService(TEST_UID, "MyService", "_test._TCP")

        assertTrue(repository.isServiceAllowed(TEST_UID, "myservice", "_TEST._tcp"))
    }

    @Test
    fun testUnloadUid() {
        repository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE)
        assertTrue(repository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE))

        repository.unloadUid(TEST_UID)

        assertFalse(repository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE))
    }
}
