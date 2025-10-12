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

package com.android.testutils

import android.Manifest.permission.MANAGE_TEST_NETWORKS
import android.content.Context
import android.net.TestNetworkInterface
import android.net.TestNetworkManager
import android.net.TestNetworkManager.TestInterfaceRequest
import android.util.Log
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Wrapper around TestNetworkInterface that implements AutoCloseable for use with {@link
 * AutoCloseTestResourcesRule}.
 */
class AutoCloseableTestNetworkInterface(
    private val iface: TestNetworkInterface,
) : AutoCloseable {
    companion object {
        fun createTap(context: Context): AutoCloseableTestNetworkInterface {
            // TODO: fix this by calling create() below, once the modules are merged.
            // Some automation tests are run with an outdated Tethering module which does not
            // include the createTestInterface() API.
            val iface = runAsShell(MANAGE_TEST_NETWORKS) {
                val tnm = context.getSystemService(TestNetworkManager::class.java)!!
                tnm.createTapInterface()
            }
            return AutoCloseableTestNetworkInterface(iface)
        }

        fun create(context: Context, req: TestInterfaceRequest): AutoCloseableTestNetworkInterface {
            val iface = runAsShell(MANAGE_TEST_NETWORKS) {
                val tnm = context.getSystemService(TestNetworkManager::class.java)!!
                tnm.createTestInterface(req)
            }
            return AutoCloseableTestNetworkInterface(iface)
        }
    }

    val fileDescriptor get() = iface.fileDescriptor
    val interfaceName get() = iface.interfaceName
    val macAddress get() = iface.macAddress
    val mtu get() = iface.mtu

    override fun close() {
        // ParcelFileDescriptor prevents the fd from being double closed.
        iface.getFileDescriptor().close()
    }
}

/**
 * Closes test resources after test execution.
 *
 * Note that for robustness, the implementation for AutoCloseable#close() *should* be idempotent to
 * avoid double-close issues.
 */
class AutoCloseTestResourcesRule : TestRule {
    private val TAG = "AutoCloseTestResourcesRule"
    private val resources = ArrayList<AutoCloseable>()

    private fun closeAllResources() {
        for (res in resources) {
            try {
                // If close() throws an exception, subsequent resources should still be cleaned up.
                res.close()
            } catch (e: Exception) {
                Log.wtf(TAG, "Failed to close test resource", e)
            }
        }
    }

    // TODO: support tracking arbitrary objects by passing member function pointers.
    /** Ensure this resource is closed at the end of the test */
    public fun add(resource: AutoCloseable) {
        resources.add(resource)
    }

    private inner class AutoCloseTestResourcesRule(
        private val base: Statement,
        private val description: Description
    ) : Statement() {
        override fun evaluate() {
            tryTest {
                base.evaluate()
            } cleanup {
                closeAllResources()
            }
        }
    }

    override fun apply(base: Statement, description: Description): Statement {
        return AutoCloseTestResourcesRule(base, description)
    }
}
