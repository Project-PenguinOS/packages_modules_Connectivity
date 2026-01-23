
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

package com.android.connectivity.resources

import android.content.Context
import android.content.Intent
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.View
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedMatcher
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.filters.SmallTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.android.connectivity.resources.aidl.NsdPickerConnector
import com.android.connectivity.resources.aidl.NsdServiceReceiver
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.ArgumentMatchers.argThat
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions

private const val TEST_APP_NAME = "Test App"
private const val SERVICE_NAME_1 = "Service 1"
private const val SERVICE_NAME_2 = "Service 2"

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@SmallTest
class NsdPickerActivityTest {
    private val mContext = ApplicationProvider.getApplicationContext<Context>()
    private val mMockConnector = mock(NsdPickerConnector::class.java)
    private lateinit var mScenario: ActivityScenario<NsdPickerActivity>
    private lateinit var mServiceReceiver: NsdServiceReceiver

    companion object {
        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            device.waitForIdle()
        }
    }

    /**
     * A NsdPickerConnector.Stub that delegates to a mock.
     *
     * This allows using a real NsdPickerConnector.Stub that can be serialized/deserialized, but
     * still using mockito to verify invocations on the mock.
     */
    private class ForwardingConnector(private val connector: NsdPickerConnector) :
        NsdPickerConnector.Stub(), NsdPickerConnector by connector {
        // asBinder is implemented by both base class and delegate: specify explicitly
        override fun asBinder(): IBinder {
            return super.asBinder()
        }
    }

    @Before
    fun setUp() {
        val receiverCaptor = ArgumentCaptor.forClass(NsdServiceReceiver::class.java)
        val intent = Intent(mContext, NsdPickerActivity::class.java)
        val bundle = Bundle()
        bundle.putString(NsdPickerConnector.EXTRA_APP_NAME, TEST_APP_NAME)
        bundle.putBinder(NsdPickerConnector.EXTRA_CONNECTOR, ForwardingConnector(mMockConnector))
        intent.putExtras(bundle)

        mScenario = ActivityScenario.launch(intent)

        verify(mMockConnector).setServiceReceiver(receiverCaptor.capture())
        mServiceReceiver = receiverCaptor.value
    }

    @Test
    fun testSpinnerVisibility() {
        val spinnerMatcher = withId(android.R.id.progress)
        onDialogView(spinnerMatcher).check(matches(isDisplayed()))

        // Spinner should be hidden after a service is found
        val serviceInfo = createServiceInfo("Test Service")
        mServiceReceiver.onServiceFound(serviceInfo)
        onDialogView(withText("Test Service")).check(matches(isDisplayed()))
        onDialogView(spinnerMatcher).check(matches(not(isDisplayed())))

        // Spinner should be visible again if all services are lost
        mServiceReceiver.onServiceLost(serviceInfo)
        onDialogView(withId(android.R.id.progress)).check(matches(isDisplayed()))
    }

    @Test
    fun testServiceList() {
        val serviceInfo1 = createServiceInfo(SERVICE_NAME_1)
        val serviceInfo2 = createServiceInfo(SERVICE_NAME_2)

        mServiceReceiver.onServiceFound(serviceInfo1)
        mServiceReceiver.onServiceFound(serviceInfo2)

        // Look for the view instead of adapter data, to be able to verify when it does not exist
        onDialogView(withText(SERVICE_NAME_1)).check(matches(isDisplayed()))
        onDialogView(withText(SERVICE_NAME_2)).check(matches(isDisplayed()))

        mServiceReceiver.onServiceLost(serviceInfo1)
        onDialogView(withText(SERVICE_NAME_1)).check(doesNotExist())
        onDialogView(withText(SERVICE_NAME_2)).check(matches(isDisplayed()))
    }

    @Test
    fun testServiceSelection() {
        val serviceInfo1 = createServiceInfo(SERVICE_NAME_1)
        val serviceInfo2 = createServiceInfo(SERVICE_NAME_2)

        mServiceReceiver.onServiceFound(serviceInfo1)
        mServiceReceiver.onServiceFound(serviceInfo2)

        onServiceInList(SERVICE_NAME_2).perform(click())
        verify(mMockConnector).notifyServiceSelected(argThat { it.serviceName == SERVICE_NAME_2 })
    }

    @Test
    fun testActivityRecreated() {
        val serviceInfo1 = createServiceInfo(SERVICE_NAME_1)
        mServiceReceiver.onServiceFound(serviceInfo1)

        mScenario.recreate()
        val serviceInfo2 = createServiceInfo(SERVICE_NAME_2)
        mServiceReceiver.onServiceFound(serviceInfo2)

        // Both services (received before and after recreation) should be displayed
        onServiceInList(SERVICE_NAME_1).check(matches(isDisplayed()))
        onServiceInList(SERVICE_NAME_2).check(matches(isDisplayed()))
        onServiceInList(SERVICE_NAME_2).perform(click())
        verify(mMockConnector).notifyServiceSelected(argThat { it.serviceName == SERVICE_NAME_2 })
    }

    @Test
    fun testDiscoveryCancelled() {
        onView(withText(R.string.connect_to_service_title)).check(matches(isDisplayed()))
        mServiceReceiver.onCancelled()

        onView(withText(R.string.connect_to_service_title)).check(doesNotExist())
        verifyNoMoreInteractions(mMockConnector)
    }
}

private fun onDialogView(matcher: Matcher<View>) = onView(matcher).inRoot(isDialog())

private fun onServiceInList(serviceName: String) =
    onData(ServiceMatcher(serviceName)).inRoot(isDialog())

private class ServiceMatcher(private val serviceName: String) :
    BoundedMatcher<Any, NsdServiceInfo>(NsdServiceInfo::class.java) {
    override fun describeTo(description: Description) {
        description.appendText("with service name: $serviceName")
    }

    override fun matchesSafely(item: NsdServiceInfo): Boolean {
        return item.serviceName == serviceName
    }
}

private fun createServiceInfo(serviceName: String): NsdServiceInfo {
    val serviceInfo = NsdServiceInfo()
    serviceInfo.serviceName = serviceName
    serviceInfo.serviceType = "_test._tcp"
    return serviceInfo
}
