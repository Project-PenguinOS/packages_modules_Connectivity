/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.Manifest.permission.NETWORK_SETTINGS
import android.annotation.SuppressLint
import android.net.INetd
import android.net.NativeNetworkConfig
import android.net.NativeNetworkType
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_ROAMING
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_SUSPENDED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VCN_MANAGED
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN
import android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED
import android.net.NetworkCapabilities.NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS
import android.net.NetworkCapabilities.TRANSPORT_CELLULAR
import android.net.NetworkCapabilities.TRANSPORT_SATELLITE
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.UidRange
import android.net.UidRangeParcel
import android.net.VpnManager
import android.net.netd.aidl.NativeUidRangeConfig
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.util.ArraySet
import com.android.net.module.util.CollectionUtils
import com.android.server.ConnectivityService.PREFERENCE_ORDER_APP_OPT_IN
import com.android.server.connectivity.AppOptInDefaultNetworkPolicy
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.TestableNetworkCallback.Event.CapabilitiesChanged
import com.android.testutils.TestableNetworkCallback.Event.Losing
import com.android.testutils.TestableNetworkCallback.Event.Lost
import com.android.testutils.TestableNetworkCallback.Event.Resumed
import com.android.testutils.TestableNetworkCallback.Event.Suspended
import com.android.testutils.runAsShell
import com.android.testutils.visibleOnHandlerThread
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assert
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import java.util.Collections.emptyList

private const val SECONDARY_USER = 10
private val SECONDARY_USER_HANDLE = UserHandle(SECONDARY_USER)
private const val TEST_PACKAGE_UID = 123
private const val TEST_PACKAGE_UID2 = 321

@SuppressLint("VisibleForTests", "MissingPermission")
@DevSdkIgnoreRunner.MonitorThreadLeak
@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.TIRAMISU)
class CSSatelliteNetworkTest : CSTest() {
    @get:Rule
    val ignoreRule = DevSdkIgnoreRule()

    /**
     * Test createNrisFromAppOptInPolicies returns correct NetworkRequestInfo.
     */
    @Test
    fun testCreateMultiLayerNrisFromAppOptInSmsRoleSatelliteUids() {
        // Verify that empty uid set should not create any NRI for it.
        val nrisNoUid = service.createNrisFromAppOptInPolicies(emptyList())
        Assert.assertEquals(0, nrisNoUid.size.toLong())
        val uid1 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val uid2 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID2)
        val uid3 = SECONDARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        assertNrisForAppOptInSmsRoleSatelliteUids(mutableSetOf(uid1))
        assertNrisForAppOptInSmsRoleSatelliteUids(mutableSetOf(uid1, uid3))
        assertNrisForAppOptInSmsRoleSatelliteUids(mutableSetOf(uid1, uid2))
    }

    /**
     * Test App Opt-In default network request satisfies satellite network and send correct net id
     * and uid ranges to netd.
     */
    private fun doTestAppOptInSatelliteNetworkUids(restricted: Boolean) {
        val netdInOrder = inOrder(netd)

        val satelliteAgent = createSatelliteAgent("satellite0", restricted)
        satelliteAgent.connect()

        val satelliteNetId = satelliteAgent.network.netId
        val permission = if (restricted) INetd.PERMISSION_SYSTEM else INetd.PERMISSION_NONE
        netdInOrder.verify(netd).networkCreate(
            nativeNetworkConfigPhysical(satelliteNetId, permission)
        )

        val uid1 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val uid2 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID2)
        val uid3 = SECONDARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)

        // Initial satellite network fallback uids status.
        updateAppOptInDefaultNetworkPolicies(emptyList())
        netdInOrder.verify(netd, never()).networkAddUidRangesParcel(any())
        netdInOrder.verify(netd, never()).networkRemoveUidRangesParcel(any())

        // Update satellite network fallback uids and verify that net id and uid ranges send to netd
        var uids = mutableSetOf(uid1, uid2, uid3)
        val uidRanges1 = toUidRangeStableParcels(uidRangesForUids(uids))
        val config1 = NativeUidRangeConfig(
            satelliteNetId,
            uidRanges1,
            PREFERENCE_ORDER_APP_OPT_IN
        )
        // Construct the policy object for UIDs with the SMS role.
        var policy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                uids)
        updateAppOptInDefaultNetworkPolicies(listOf(policy))
        netdInOrder.verify(netd).networkAddUidRangesParcel(config1)
        netdInOrder.verify(netd, never()).networkRemoveUidRangesParcel(any())

        // Update satellite network fallback uids and verify that net id and uid ranges send to netd
        uids = mutableSetOf(uid1)
        val uidRanges2: Array<UidRangeParcel?> = toUidRangeStableParcels(uidRangesForUids(uids))
        val config2 = NativeUidRangeConfig(
            satelliteNetId,
            uidRanges2,
            PREFERENCE_ORDER_APP_OPT_IN
        )
        // Construct the updated policy object with the smaller UID set.
        policy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                uids)
        updateAppOptInDefaultNetworkPolicies(listOf(policy))
        netdInOrder.verify(netd).networkRemoveUidRangesParcel(config1)
        netdInOrder.verify(netd).networkAddUidRangesParcel(config2)
    }

    @Test
    fun testUpdateAppOptInDefaultNetworkPolicies_SmsPolicyWithRestrictedSatellite() {
        doTestAppOptInSatelliteNetworkUids(restricted = true)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testUpdateAppOptInDefaultNetworkPolicies_SmsPolicyWithNonRestricted() {
        doTestAppOptInSatelliteNetworkUids(restricted = false)
    }

    private fun doTestSatelliteNeverBecomeDefaultNetwork(restricted: Boolean) {
        val agent = createSatelliteAgent("satellite0", restricted)
        agent.connect()
        val defaultCb = TestableNetworkCallback()
        cm.registerDefaultNetworkCallback(defaultCb)
        // Satellite network must not become the default network
        defaultCb.assertNoCallback()
    }

    @Test
    fun testSatelliteNeverBecomeDefaultNetwork_restricted() {
        doTestSatelliteNeverBecomeDefaultNetwork(restricted = true)
    }

    @Test @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testSatelliteNeverBecomeDefaultNetwork_notRestricted() {
        doTestSatelliteNeverBecomeDefaultNetwork(restricted = false)
    }

    private fun doTestUnregisterAfterReplacementSatisfier(
        destroyBeforeRequest: Boolean = false,
        destroyAfterRequest: Boolean = false
    ) {
        val satelliteAgent = createSatelliteAgent("satellite0")
        satelliteAgent.connect()

        if (destroyBeforeRequest) {
            satelliteAgent.unregisterAfterReplacement(timeoutMs = 5000)
        }

        val uids = setOf(TEST_PACKAGE_UID)
        // Create the policy info object for UIDs with the SMS role.
        val policy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                uids)
        // Call the updated callback method with the list of policy info objects.
        updateAppOptInDefaultNetworkPolicies(listOf(policy))

        if (destroyBeforeRequest) {
            verify(netd, never()).networkAddUidRangesParcel(any())
        } else {
            verify(netd).networkAddUidRangesParcel(
                NativeUidRangeConfig(
                    satelliteAgent.network.netId,
                    toUidRangeStableParcels(uidRangesForUids(uids)),
                    PREFERENCE_ORDER_APP_OPT_IN
                )
            )
        }

        if (destroyAfterRequest) {
            satelliteAgent.unregisterAfterReplacement(timeoutMs = 5000)
        }

        updateAppOptInDefaultNetworkPolicies(emptyList())
        if (destroyBeforeRequest || destroyAfterRequest) {
            // If the network is already destroyed, networkRemoveUidRangesParcel should not be
            // called.
            verify(netd, never()).networkRemoveUidRangesParcel(any())
        } else {
            verify(netd).networkRemoveUidRangesParcel(
                    NativeUidRangeConfig(
                            satelliteAgent.network.netId,
                            toUidRangeStableParcels(uidRangesForUids(uids)),
                            PREFERENCE_ORDER_APP_OPT_IN
                    )
            )
        }
    }

    @Test
    fun testUnregisterAfterReplacementSatisfier_destroyBeforeRequest() {
        doTestUnregisterAfterReplacementSatisfier(destroyBeforeRequest = true)
    }

    @Test
    fun testUnregisterAfterReplacementSatisfier_destroyAfterRequest() {
        doTestUnregisterAfterReplacementSatisfier(destroyAfterRequest = true)
    }

    @Test
    fun testUnregisterAfterReplacementSatisfier_notDestroyed() {
        doTestUnregisterAfterReplacementSatisfier()
    }

    @SuppressLint("MissingPermission")
    @Test @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    fun testAppOptInDefaultNetworkPoliciesCallbacks() {
        val handler = Handler(Looper.getMainLooper())
        val myUid = Process.myUid()
        val otherUid = Process.myUid() + 1
        val defaultCb = TestableNetworkCallback().also { cm.registerDefaultNetworkCallback(it) }
        val otherUidCb = TestableNetworkCallback().also {
            runAsShell(NETWORK_SETTINGS) {
                cm.registerDefaultNetworkCallbackForUid(otherUid, it, handler)
            }
        }
        val allNetworksCb = TestableNetworkCallback().also {
            cm.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities().build(), it)
        }

        // Create the policy info object for myUid with the SMS role.
        val policy = AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(myUid))
        // Call the updated callback method with the list of policy info objects.
        updateAppOptInDefaultNetworkPolicies(listOf(policy))
        defaultCb.assertNoCallback()

        val satelliteAgent = createSatelliteAgent(
            "satellite0",
            restricted = false,
            keepConnected = false
        ).apply { connect() }
        val satelliteNetwork = satelliteAgent.network

        allNetworksCb.expectAvailableCallbacks(satelliteNetwork, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteNetwork, validated = false)
        otherUidCb.assertNoCallback()

        val wifiAgent = Agent(
            lp = defaultLp().apply { interfaceName = "wlan0" },
                nc = ncForTransport(TRANSPORT_WIFI)
        ).apply { connect() }
        val wifiNetwork = wifiAgent.network

        allNetworksCb.expectAvailableCallbacks(wifiNetwork, validated = false)
        defaultCb.expectAvailableCallbacks(wifiNetwork, validated = false)
        otherUidCb.expectAvailableCallbacks(wifiNetwork, validated = false)
        allNetworksCb.expect<Losing>(satelliteNetwork)
        allNetworksCb.expect<Lost>(satelliteNetwork)

        wifiAgent.disconnect()
        allNetworksCb.expect<Lost>(wifiNetwork)
        defaultCb.expect<Lost>(wifiNetwork)
        otherUidCb.expect<Lost>(wifiNetwork)

        val satelliteAgent2 = createSatelliteAgent(
            "satellite0",
            restricted = false,
            keepConnected = false
        )
        satelliteAgent2.connect()
        val satelliteNetwork2 = satelliteAgent2.network

        allNetworksCb.expectAvailableCallbacks(satelliteNetwork2, validated = false)
        defaultCb.expectAvailableCallbacks(satelliteNetwork2, validated = false)

        updateAppOptInDefaultNetworkPolicies(emptyList())

        allNetworksCb.expect<Lost>(satelliteNetwork2)
        defaultCb.expect<Lost>(satelliteNetwork2)
        otherUidCb.assertNoCallback()
    }

    @Test
    fun testSuspendAndRoam() {
        val agent = createSatelliteAgent(
                name = "satellite0",
                restricted = false,
                keepConnected = true
        )
        agent.connect()
        val nr = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(TRANSPORT_SATELLITE)
                .build()
        val cb = TestableNetworkCallback()
        cm.registerNetworkCallback(nr, cb)
        cb.eventuallyExpect<CapabilitiesChanged> {it.network == agent.network &&
                    it.caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED) &&
                    it.caps.hasCapability(NET_CAPABILITY_NOT_ROAMING)
        }

        // Suspend satellite network
        val nc1 = satelliteNc(restricted = false)
                .removeCapability(NET_CAPABILITY_NOT_SUSPENDED)
                .removeCapability(NET_CAPABILITY_NOT_ROAMING)
        agent.sendNetworkCapabilities(nc1)
        cb.eventuallyExpect<CapabilitiesChanged> {it.network == agent.network &&
                    !it.caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED) &&
                    !it.caps.hasCapability(NET_CAPABILITY_NOT_ROAMING)
        }
        cb.expect<Suspended>(agent)

        // Resume satellite network
        val nc2 = satelliteNc(restricted = false)
        agent.sendNetworkCapabilities(nc2)
        cb.expect<CapabilitiesChanged> {it.network == agent.network &&
                it.caps.hasCapability(NET_CAPABILITY_NOT_SUSPENDED) &&
                it.caps.hasCapability(NET_CAPABILITY_NOT_ROAMING)
        }
        cb.expect<Resumed>(agent)
    }

    private fun assertNrisForAppOptInSmsRoleSatelliteUids(uids: Set<Int>) {
        val policies = listOf(
                AppOptInDefaultNetworkPolicy(
                        false /* isSatelliteOptIn */,
                        true  /* isSatelliteRoleSms */,
                        false /* isOtt */,
                        uids
                )
        )
        val nris = service.createNrisFromAppOptInPolicies(policies)
        val nri = nris.iterator().next()
        // Verify that one NRI is created with multilayer requests. Because one NRI can contain
        // multiple uid ranges, so it only need create one NRI here.
        assertEquals(1, nris.size.toLong())
        assertTrue(nri.isMultilayerRequest)
        assertEquals(nri.uids, uidRangesForUids(uids))
        assertEquals(PREFERENCE_ORDER_APP_OPT_IN, nri.mPreferenceOrder)
    }

    private fun updateAppOptInDefaultNetworkPolicies(
            policies: List<AppOptInDefaultNetworkPolicy>) {
        visibleOnHandlerThread(csHandler) {
            deps.appOptInDefaultNetworkPoliciesUpdate!!.accept(policies)
        }
    }

    private fun nativeNetworkConfigPhysical(netId: Int, permission: Int) =
        NativeNetworkConfig(
            netId,
            NativeNetworkType.PHYSICAL,
            permission,
            false /* secure */,
            VpnManager.TYPE_VPN_NONE,
            false /* excludeLocalRoutes */
        )

    private fun createSatelliteAgent(
        name: String,
        restricted: Boolean = true,
        keepConnected: Boolean = true
    ): CSAgentWrapper {
        return Agent(
            score = if (keepConnected) keepScore() else defaultScore(),
            lp = defaultLp().apply { interfaceName = name },
            nc = satelliteNc(restricted)
        )
    }

    private fun toUidRangeStableParcels(ranges: Set<UidRange>): Array<UidRangeParcel?> {
        val stableRanges = arrayOfNulls<UidRangeParcel>(ranges.size)
        for ((index, range) in ranges.withIndex()) {
            stableRanges[index] = UidRangeParcel(range.start, range.stop)
        }
        return stableRanges
    }

    private fun uidRangesForUids(vararg uids: Int): Set<UidRange> {
        val ranges = ArraySet<UidRange>()
        for (uid in uids) {
            ranges.add(UidRange(uid, uid))
        }
        return ranges
    }

    private fun uidRangesForUids(uids: Collection<Int>): Set<UidRange> {
        return uidRangesForUids(*CollectionUtils.toIntArray(uids))
    }

    private fun ncForTransport(transport: Int) =
        NetworkCapabilities.Builder().apply {
            addTransportType(transport)
            addCapability(NET_CAPABILITY_INTERNET)
            addCapability(NET_CAPABILITY_NOT_SUSPENDED)
            addCapability(NET_CAPABILITY_NOT_ROAMING)
            addCapability(NET_CAPABILITY_NOT_VCN_MANAGED)
            addCapability(NET_CAPABILITY_NOT_VPN)
        }.build()

    private fun satelliteNc(restricted: Boolean): NetworkCapabilities {
        val nc = ncForTransport(TRANSPORT_SATELLITE)
        if (restricted) {
            nc.removeCapability(NET_CAPABILITY_NOT_RESTRICTED)
        } else {
            nc.removeCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED)
        }
        return nc
    }

    // Helpers to assert the content of NetworkRequest layers
    private fun assertTrackDefaultRequest(request: NetworkRequest) {
        assertEquals(NetworkRequest.Type.TRACK_DEFAULT, request.type)
    }

    private fun assertSmsRequest(request: NetworkRequest) {
        val caps = request.networkCapabilities
        assertTrue(caps.hasTransport(TRANSPORT_SATELLITE))
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED))
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED))
        assertTrue(caps.hasCapability(NET_CAPABILITY_INTERNET))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_VCN_MANAGED))
    }

    private fun assertOptInRequest(request: NetworkRequest) {
        val caps = request.networkCapabilities
        assertTrue(caps.hasTransport(TRANSPORT_SATELLITE))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_RESTRICTED))
        assertFalse(caps.hasCapability(NET_CAPABILITY_NOT_BANDWIDTH_CONSTRAINED))
        assertTrue(caps.hasCapability(NET_CAPABILITY_INTERNET))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_VCN_MANAGED))
    }

    @Test
    fun testCreateNrisFromAppOptInPolicies_emptyList_producesNoNris() {
        val nris = service.createNrisFromAppOptInPolicies(emptyList())
        assertEquals(0, nris.size.toLong())
    }

    @Test
    fun testCreateNrisFromAppOptInPolicies_smsOnly_createsSmsRequest() {
        val uid1 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val uid2 = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID2)
        val smsPolicies = listOf(AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true  /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(uid1, uid2)))

        val nris = service.createNrisFromAppOptInPolicies(smsPolicies)

        assertEquals(1, nris.size)
        val requests = nris.valueAt(0).mRequests
        assertEquals(2, requests.size)
        assertTrackDefaultRequest(requests[0])
        assertSmsRequest(requests[1])
    }

    @Test
    fun testCreateNrisFromAppOptInPolicies_optInOnly_createsOptInRequest() {
        val uid = SECONDARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val optInPolicies = listOf(AppOptInDefaultNetworkPolicy(
                true  /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                false /* isOtt */,
                setOf(uid)))

        val nris = service.createNrisFromAppOptInPolicies(optInPolicies)

        assertEquals(1, nris.size)
        val requests = nris.valueAt(0).mRequests
        assertEquals(2, requests.size)
        assertTrackDefaultRequest(requests[0])
        assertOptInRequest(requests[1])
    }

    /**
     * Test that ConnectivityService correctly handles a mixed list of policies, including
     * overlapping policies for a single UID.
     */
    @Test
    fun testCreateAppOptInNris_MixedAndOverlappingPolicies() {
        val uidSms = 1001
        val uidOptIn = 1002
        val uidBoth = 1003

        val mixedPolicies = listOf(
                AppOptInDefaultNetworkPolicy(false  /* isSatelliteOptIn */,
                        true  /* isSatelliteRoleSms */, false /* isOtt */, setOf(uidSms)),
                AppOptInDefaultNetworkPolicy(true  /* isSatelliteOptIn */,
                        false  /* isSatelliteRoleSms */, false /* isOtt */, setOf(uidOptIn)),
                AppOptInDefaultNetworkPolicy(true /* isSatelliteOptIn */,
                        true /* isSatelliteRoleSms */, false /* isOtt */, setOf(uidBoth))
        )

        val nris = service.createNrisFromAppOptInPolicies(mixedPolicies)
        assertEquals(3, nris.size)

        // Find and verify the NRI for the SMS-only UID
        val smsNri = nris.first { it.uids == uidRangesForUids(setOf(uidSms)) }
        assertEquals(2, smsNri.mRequests.size)
        assertTrackDefaultRequest(smsNri.mRequests[0])
        assertSmsRequest(smsNri.mRequests[1])

        // Find and verify the NRI for the Opt-in only UID
        val optInNri = nris.first { it.uids == uidRangesForUids(setOf(uidOptIn)) }
        assertEquals(2, optInNri.mRequests.size)
        assertTrackDefaultRequest(optInNri.mRequests[0])
        assertOptInRequest(optInNri.mRequests[1])

        // Find and verify the NRI for the UID with both flags set
        val bothNri = nris.first { it.uids == uidRangesForUids(setOf(uidBoth)) }
        // Expect 3 layers now: TRACK_DEFAULT + SMS + Opt-in
        assertEquals(3, bothNri.mRequests.size)
        assertTrackDefaultRequest(bothNri.mRequests[0])
        assertSmsRequest(bothNri.mRequests[1])
        assertOptInRequest(bothNri.mRequests[2])
    }

    /**
     * Test createAppOptInNrisFromPolicyList returns correct NetworkRequestInfo for OTT UIDs.
     */
    @Test
    fun testCreateAppOptInNrisFromPolicyList_forOnlyOttUids() {
        val ottUid = PRIMARY_USER_HANDLE.getUid(TEST_PACKAGE_UID)
        val ottPolicy = listOf(AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                false /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(ottUid)))
        val nris = service.createNrisFromAppOptInPolicies(ottPolicy)
        assertEquals(1, nris.size)
        val nri = nris.valueAt(0)
        assertTrue(nri.isMultilayerRequest)
        assertEquals(PREFERENCE_ORDER_APP_OPT_IN, nri.mPreferenceOrder)
        // Verify the layers: Unmetered, ufc and TRACK_DEFAULT
        assertEquals(3, nri.mRequests.size)
        assertUnmeteredRequest(nri.mRequests[0])
        assertUfcRequest(nri.mRequests[1])
        assertTrackDefaultRequest(nri.mRequests[2])
    }

    /**
     * Test createAppOptInNrisFromPolicyList returns correct NetworkRequestInfo for a mixed
     * list of OTT, SMS, and Opt-In UIDs.
     */
    @Test
    fun testCreateAppOptInNrisFromPolicyList_differentPolicies_ForOttSmsAndOptInPolicy() {
        val ottUid = 1001
        val smsUid = 1002
        val optInUid = 1003

        val mixedPolicyList = listOf(
                AppOptInDefaultNetworkPolicy(
                        false /* isSatelliteOptIn */,
                        false /* isSatelliteRoleSms */,
                        true /* isOtt */,
                        setOf(ottUid)),
                AppOptInDefaultNetworkPolicy(
                        false /* isSatelliteOptIn */,
                        true /* isSatelliteRoleSms */,
                        false /* isOtt */,
                        setOf(smsUid)),
                AppOptInDefaultNetworkPolicy(
                        true /* isSatelliteOptIn */,
                        false /* isSatelliteRoleSms */,
                        false /* isOtt */,
                        setOf(optInUid))
        )

        val nris = service.createNrisFromAppOptInPolicies(mixedPolicyList)
        assertEquals(3, nris.size)

        // Find and verify the NRI for the OTT UID
        val ottNri = nris.first { it.uids == uidRangesForUids(setOf(ottUid)) }
        assertTrue(ottNri.isMultilayerRequest)
        assertEquals(3, ottNri.mRequests.size)
        assertUnmeteredRequest(ottNri.mRequests[0])
        assertUfcRequest(ottNri.mRequests[1])
        assertTrackDefaultRequest(ottNri.mRequests[2])

        // Find and verify the NRI for the SMS UID
        val smsNri = nris.first { it.uids == uidRangesForUids(setOf(smsUid)) }
        // This test now correctly expects 2 layers for the SMS fallback request.
        assertEquals(2, smsNri.mRequests.size)
        assertTrackDefaultRequest(smsNri.mRequests[0])
        assertSmsRequest(smsNri.mRequests[1])

        // Find and verify the NRI for the Opt-in UID
        val optInNri = nris.first { it.uids == uidRangesForUids(setOf(optInUid)) }
        // This test now correctly expects 2 layers for the Opt-In fallback request.
        assertEquals(2, optInNri.mRequests.size)
        assertTrackDefaultRequest(optInNri.mRequests[0])
        assertOptInRequest(optInNri.mRequests[1])
    }

    /**
     * Test that a single AppOptInDefaultNetworkInfo with both OTT and SMS role flags set
     * to true creates a single NRI with all the corresponding request layers.
     */
    @Test
    fun testCreateAppOptInNrisFromPolicyList_forCombinedOttAndSmsPolicy() {
        val commonUid = 1006
        val combinedPolicy = listOf(AppOptInDefaultNetworkPolicy(
                false /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(commonUid)))

        val nris = service.createNrisFromAppOptInPolicies(combinedPolicy)
        // A single policy object should result in a single NRI.
        assertEquals(1, nris.size)

        val nri = nris.valueAt(0)
        assertTrue(nri.isMultilayerRequest)
        // Expect 4 layers: OTT (2) + TRACK_DEFAULT (1) + SMS (1)
        assertEquals(4, nri.mRequests.size)

        // Verify the layers are created in the correct order.
        assertUnmeteredRequest(nri.mRequests[0]) // OTT Layer 1
        assertUfcRequest(nri.mRequests[1])   // OTT Layer 2
        // Default Layer is always added
        assertTrackDefaultRequest(nri.mRequests[2])

        // Verify the Satellite SMS layer
        assertSmsRequest(nri.mRequests[3])
    }

    /**
     * Test that a single AppOptInDefaultNetworkInfo with isOtt, isSatelliteRoleSms, and
     * isSatelliteOptIn all set to true creates a single NRI with OTT and SMS layers.
     */
    @Test
    fun testCreateAppOptInNrisFromPolicyList_forCombinedOttSmsAndOptInPolicy() {
        val commonUid = 1007
        val combinedPolicy = listOf(AppOptInDefaultNetworkPolicy(
                true /* isSatelliteOptIn */,
                true /* isSatelliteRoleSms */,
                true /* isOtt */,
                setOf(commonUid)))

        val nris = service.createNrisFromAppOptInPolicies(combinedPolicy)
        // A single policy object results in a single NRI.
        assertEquals(1, nris.size)

        val nri = nris.valueAt(0)
        assertTrue(nri.isMultilayerRequest)
        // Expect 5 layers: OTT (2) + TRACK_DEFAULT (1) + SMS (1) + OPT-IN (1)
        assertEquals(5, nri.mRequests.size)

        // Verify the layers are created in the correct order.
        assertUnmeteredRequest(nri.mRequests[0]) // OTT Layer 1
        assertUfcRequest(nri.mRequests[1])   // OTT Layer 2
        assertTrackDefaultRequest(nri.mRequests[2])

        // Verify the Satellite SMS layer is present
        assertSmsRequest(nri.mRequests[3])

        // Verify the Opt-in request layer
        assertOptInRequest(nri.mRequests[4])
    }

    /**
     * Helper to assert the content of an Unmetered request layer for OTT.
     */
    private fun assertUnmeteredRequest(request: NetworkRequest) {
        assertEquals(NetworkRequest.Type.REQUEST, request.type)
        val caps = request.networkCapabilities
        assertTrue(caps.hasCapability(NET_CAPABILITY_INTERNET))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_VCN_MANAGED))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_METERED))
    }

    /**
     * Helper to assert the content of a ufc request layer for OTT.
     */
    private fun assertUfcRequest(request: NetworkRequest) {
        assertEquals(NetworkRequest.Type.REQUEST, request.type)
        val caps = request.networkCapabilities
        assertTrue(caps.hasTransport(TRANSPORT_CELLULAR))
        assertTrue(caps.hasCapability(NET_CAPABILITY_PRIORITIZE_UNIFIED_COMMUNICATIONS))
        assertTrue(caps.hasCapability(NET_CAPABILITY_NOT_VCN_MANAGED))
    }
}
