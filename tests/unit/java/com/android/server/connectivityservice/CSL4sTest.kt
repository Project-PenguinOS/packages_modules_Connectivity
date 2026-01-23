
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

import android.net.ConnectivitySettingsManager
import android.net.ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_AUTOMATIC
import android.net.ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_DISABLED
import android.net.ConnectivitySettingsManager.L4S_DEVELOPER_OPTION_ENABLED
import android.os.Build
import android.os.Build.VERSION_CODES
import androidx.test.filters.SmallTest
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import kotlin.test.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify

@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@SmallTest
@IgnoreUpTo(Build.VERSION_CODES.BAKLAVA)
class CSL4sTest : CSTest() {
    @Test
    fun testCsL4sUpdatedInBpfMap() {
        val inOrder = inOrder(bpfNetMaps)

        // Verify initialization with default value
        inOrder.verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS)).setL4sEnabled(false)

        ConnectivitySettingsManager.setL4sDeveloperOption(context, L4S_DEVELOPER_OPTION_ENABLED)
        assertEquals(
            ConnectivitySettingsManager.getL4sDeveloperOption(context),
            L4S_DEVELOPER_OPTION_ENABLED
        )
        inOrder.verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS)).setL4sEnabled(true)

        ConnectivitySettingsManager.setL4sDeveloperOption(context, L4S_DEVELOPER_OPTION_DISABLED)
        assertEquals(
            ConnectivitySettingsManager.getL4sDeveloperOption(context),
            L4S_DEVELOPER_OPTION_DISABLED
        )
        inOrder.verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS)).setL4sEnabled(false)

        // L4S_DEVELOPER_OPTION_AUTOMATIC means the config is decided by ConnectivityService,
        // the default value is disabled.
        ConnectivitySettingsManager.setL4sDeveloperOption(context, L4S_DEVELOPER_OPTION_AUTOMATIC)
        assertEquals(
            ConnectivitySettingsManager.getL4sDeveloperOption(context),
            L4S_DEVELOPER_OPTION_AUTOMATIC
        )
        inOrder.verify(bpfNetMaps, timeout(HANDLER_TIMEOUT_MS)).setL4sEnabled(false)
    }
}
