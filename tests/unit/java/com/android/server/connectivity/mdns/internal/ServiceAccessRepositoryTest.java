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

package com.android.server.connectivity.mdns.internal;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.net.module.util.SharedLog;
import com.android.testutils.DevSdkIgnoreRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ServiceAccessRepositoryTest {
    @Rule
    public final DevSdkIgnoreRule mIgnoreRule = new DevSdkIgnoreRule();

    private static final int TEST_UID = 12345;
    private static final String SERVICE_NAME = "MyService";
    private static final String SERVICE_TYPE = "_test._tcp";

    private ServiceAccessRepository mRepository;

    @Before
    public void setUp() {
        mRepository = new ServiceAccessRepository(mock(SharedLog.class));
    }

    @Test
    public void testAddAndQueryAllowedService() {
        mRepository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE);

        assertTrue(mRepository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE));
    }

    @Test
    public void testServiceNotAllowed_NoService_NotAllowedByDefault() {
        assertFalse(mRepository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE));
    }

    @Test
    public void testServiceNotAllowed_UidMismatch_NotAllowed() {
        mRepository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE);

        assertFalse(mRepository.isServiceAllowed(TEST_UID + 1, SERVICE_NAME, SERVICE_TYPE));
    }

    @Test
    public void testServiceNotAllowed_ServiceNameMismatch_NotAllowed() {
        mRepository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE);

        assertFalse(mRepository.isServiceAllowed(TEST_UID, "Other service", SERVICE_TYPE));
    }

    @Test
    public void testServiceNotAllowed_ServiceTypeMismatch_NotAllowed() {
        mRepository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE);

        assertFalse(mRepository.isServiceAllowed(TEST_UID, SERVICE_NAME, "_othertype._tcp"));
    }

    @Test
    public void testCaseInsensitivity() {
        mRepository.addAllowedService(TEST_UID, "MyService", "_test._TCP");

        assertTrue(mRepository.isServiceAllowed(TEST_UID, "myservice", "_TEST._tcp"));
    }

    @Test
    public void testUnloadUid() {
        mRepository.addAllowedService(TEST_UID, SERVICE_NAME, SERVICE_TYPE);
        assertTrue(mRepository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE));

        mRepository.unloadUid(TEST_UID);

        assertFalse(mRepository.isServiceAllowed(TEST_UID, SERVICE_NAME, SERVICE_TYPE));
    }
}
