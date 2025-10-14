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

import android.content.pm.PackageManager
import android.content.res.Resources
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.connectivity.ConnectivityCompatChanges.ENABLE_SELF_CERTIFIED_CAPABILITIES_DECLARATION
import com.android.frameworks.tests.net.R
import com.android.networkstack.apishim.ConstantsShim
import com.android.server.connectivity.ApplicationSelfCertifiedNetworkCapabilities
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.assertThrows
import org.hamcrest.MatcherAssert.assertThat
import org.hamcrest.Matchers.containsString
import org.junit.Test
import org.mockito.Mockito.doReturn
import org.mockito.Mockito.doThrow

class CSSelfCertifiedNetworkCapabilitiesTest : CSTest() {

    private fun setupMockForNetworkCapabilitiesResources(networkSliceResourceId: Int) {
        val res: Resources = context.resources ?: throw IllegalStateException(
                "context.resources is null")

        if (networkSliceResourceId == 0) {
            doThrow(PackageManager.NameNotFoundException())
                    .`when`(packageManager)
                    .getProperty(ConstantsShim.PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES,
                            context.packageName)
        } else {
            val property =
                    PackageManager.Property(
                            ConstantsShim.PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES,
                            networkSliceResourceId,
                            true /* isResource */,
                            context.packageName,
                            "dummyClass"
                    )
            doReturn(property).`when`(packageManager).getProperty(
                    ConstantsShim.PROPERTY_SELF_CERTIFIED_NETWORK_CAPABILITIES,
                    context.packageName
            )
        }
        doReturn(res).`when`(packageManager).getResourcesForApplication(context.packageName)
    }

    // Helper method to validate exceptions.
    private fun validateThrowException(capability: Int): SecurityException {
        val req = NetworkRequest.Builder().addCapability(capability).build()
        val cb = TestableNetworkCallback()
        return assertThrows(SecurityException::class.java) {
            cm.requestNetwork(req, cb)
        }
    }

    @Test
    fun requestNetwork_latency_withOnlyPrioritizeUfcDeclaration_shouldThrowException() {
        // Enable the self-certification feature gate for this test.
        deps.setChangeIdEnabled(true, ENABLE_SELF_CERTIFIED_CAPABILITIES_DECLARATION)

        // Setup the mock to return an XML that ONLY declares UNIFIED_COMMUNICATIONS.
        setupMockForNetworkCapabilitiesResources(
                R.xml.self_certified_capabilities_unified_communications
        )

        // Verify that requesting LATENCY throws a SecurityException because it was not declared.
        val e = validateThrowException(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_LATENCY)
        assertThat(e.message, containsString(ApplicationSelfCertifiedNetworkCapabilities
                .PRIORITIZE_LATENCY))
    }

    @Test
    fun requestNetwork_Bandwidth_withOnlyPrioritizeUfcDeclaration_shouldThrowException() {
        // Enable the self-certification feature gate for this test.
        deps.setChangeIdEnabled(true, ENABLE_SELF_CERTIFIED_CAPABILITIES_DECLARATION)

        // Setup the mock to return an XML that ONLY declares UNIFIED_COMMUNICATIONS.
        setupMockForNetworkCapabilitiesResources(
                R.xml.self_certified_capabilities_unified_communications
        )

        // Verify that requesting BANDWIDTH also throws a SecurityException.
        val e = validateThrowException(NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_BANDWIDTH)
        assertThat(e.message, containsString(ApplicationSelfCertifiedNetworkCapabilities
                .PRIORITIZE_BANDWIDTH))
    }

    private fun assertUfcException() {
        val e = validateThrowException(NetworkCapabilities
                .NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS)
        assertThat(e.message, containsString(ApplicationSelfCertifiedNetworkCapabilities
                .PRIORITIZE_UNIFIED_COMMUNICATIONS))
    }

    @Test
    fun requestNetwork_UnifiedCommunications_WithOnlyBandwidthDeclaration_shouldThrowException() {
        // Enable the self-certification feature gate for this test.
        deps.setChangeIdEnabled(true, ENABLE_SELF_CERTIFIED_CAPABILITIES_DECLARATION)

        // Setup the mock to return an XML that ONLY declares prioritize_bandwidth.
        setupMockForNetworkCapabilitiesResources(R.xml.self_certified_capabilities_bandwidth)

        // Verify that requesting ufc throws a SecurityException because it was not declared.
        assertUfcException()
    }

    @Test
    fun requestNetwork_UnifiedCommunications_WithOnlyLatencyDeclaration_shouldThrowException() {
        // Enable the self-certification feature gate for this test.
        deps.setChangeIdEnabled(true, ENABLE_SELF_CERTIFIED_CAPABILITIES_DECLARATION)

        // Setup the mock to return an XML that ONLY declares prioritize_latency.
        setupMockForNetworkCapabilitiesResources(R.xml.self_certified_capabilities_latency)

        // Verify that requesting ufc throws a SecurityException because it was not declared.
        assertUfcException()
    }
}