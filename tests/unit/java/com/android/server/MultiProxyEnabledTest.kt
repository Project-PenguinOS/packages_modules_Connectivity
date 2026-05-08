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

import com.android.connectivity.resources.R
import com.android.testutils.DevSdkIgnoreRunner
import com.android.tethering.flags.Flags.FLAG_ENABLE_MULTI_PROXY_SYSTEM
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Tests ConnectivityService.mMultiProxyEnabled initialization.
 */
@RunWith(DevSdkIgnoreRunner::class)
class MultiProxyEnabledTest : CSTest() {

    @Test
    @FeatureFlags([Flag(FLAG_ENABLE_MULTI_PROXY_SYSTEM, true)])
    @ConfigProperty(bools = [BoolConfig(R.bool.config_enable_multi_proxy_system, true)])
    fun testMultiProxyEnabled_BothTrue_Enabled() {
        assertTrue(deps.capturedMultiProxyEnabled!!)
    }

    @Test
    @FeatureFlags([Flag(FLAG_ENABLE_MULTI_PROXY_SYSTEM, false)])
    @ConfigProperty(bools = [BoolConfig(R.bool.config_enable_multi_proxy_system, true)])
    fun testMultiProxyEnabled_AconfigFalse_Disabled() {
        assertFalse(deps.capturedMultiProxyEnabled!!)
    }

    @Test
    @FeatureFlags([Flag(FLAG_ENABLE_MULTI_PROXY_SYSTEM, true)])
    @ConfigProperty(bools = [BoolConfig(R.bool.config_enable_multi_proxy_system, false)])
    fun testMultiProxyEnabled_RroFalse_Disabled() {
        assertFalse(deps.capturedMultiProxyEnabled!!)
    }

    @Test
    @FeatureFlags([Flag(FLAG_ENABLE_MULTI_PROXY_SYSTEM, false)])
    @ConfigProperty(bools = [BoolConfig(R.bool.config_enable_multi_proxy_system, false)])
    fun testMultiProxyEnabled_BothFalse_Disabled() {
        assertFalse(deps.capturedMultiProxyEnabled!!)
    }
}
