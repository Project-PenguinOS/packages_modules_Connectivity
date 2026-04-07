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

import android.app.LocaleManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.nsd.DiscoveryRequest
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.ConditionVariable
import android.os.Handler
import android.os.IBinder
import android.os.LocaleList
import android.text.Layout
import android.view.View
import android.widget.TextView
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.BoundedDiagnosingMatcher
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
import com.android.testutils.AutoCloseTestResourcesRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.com.android.testutils.CloseableGlobalSetting
import com.android.testutils.tryTest
import kotlin.test.assertTrue
import org.hamcrest.Description
import org.hamcrest.Matcher
import org.hamcrest.Matchers.not
import org.junit.After
import org.junit.Before
import org.junit.BeforeClass
import org.junit.ClassRule
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
private const val TEST_TIMEOUT_MS = 10_000L

@RunWith(DevSdkIgnoreRunner::class)
@IgnoreUpTo(Build.VERSION_CODES.S_V2)
@SmallTest
class NsdPickerActivityTest {
    private val mContext = ApplicationProvider.getApplicationContext<Context>()
    private val mMockConnector = mock(NsdPickerConnector::class.java)
    private lateinit var mScenario: ActivityScenario<NsdPickerActivity>
    private lateinit var mServiceReceiver: NsdServiceReceiver

    companion object {
        @get:ClassRule
        val autoCloseRule = AutoCloseTestResourcesRule()

        @JvmStatic
        @BeforeClass
        fun setUpClass() {
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            device.wakeUp()
            device.executeShellCommand("wm dismiss-keyguard")
            // As per Espresso prerequisites, disable animations
            autoCloseRule.add(
                CloseableGlobalSetting("transition_animation_scale").apply { setValue("0") })
            autoCloseRule.add(
                CloseableGlobalSetting("window_animation_scale").apply { setValue("0") })
            autoCloseRule.add(
                CloseableGlobalSetting("animator_duration_scale").apply { setValue("0") })
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

    private fun makeStartIntent(connector: NsdPickerConnector, appName: String): Intent {
        val intent = Intent(mContext, NsdPickerActivity::class.java)
        intent.component = ComponentName(mContext, NsdPickerActivity::class.java)
        val bundle = Bundle()
        bundle.putString(NsdPickerConnector.EXTRA_APP_NAME, appName)
        bundle.putBinder(NsdPickerConnector.EXTRA_CONNECTOR, ForwardingConnector(connector))
        bundle.putParcelable(NsdPickerConnector.EXTRA_REQUEST,
            DiscoveryRequest.Builder("_test._tcp").build())
        intent.putExtras(bundle)
        return intent
    }

    @Before
    fun setUp() {
        val receiverCaptor = ArgumentCaptor.forClass(NsdServiceReceiver::class.java)
        val intent = makeStartIntent(mMockConnector, TEST_APP_NAME)

        mScenario = ActivityScenario.launch(intent)

        verify(mMockConnector).setServiceReceiver(receiverCaptor.capture())
        mServiceReceiver = receiverCaptor.value
    }

    @After
    fun tearDown() {
        if (this::mScenario.isInitialized) {
            mScenario.close()
        }
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
        onView(withText(R.string.choose_device_title)).check(matches(isDisplayed()))

        assertFinishesActivity {
            mServiceReceiver.onCancelled()
        }
        verifyNoMoreInteractions(mMockConnector)
    }

    @Test
    fun testDiscoveryCancelled_whileStopped() {
        // Send a second intent while the dialog is already shown
        val otherAppName = "Other Test App"
        val otherConnector = mock(NsdPickerConnector::class.java)
        val newIntent = makeStartIntent(otherConnector, otherAppName)
        mScenario.onActivity { activity ->
            activity.onNewIntent(newIntent)
        }

        // Stop the activity (move from RESUMED to CREATED), then cancel the first discovery
        mScenario.moveToState(Lifecycle.State.CREATED)
        mServiceReceiver.onCancelled()

        // Reopen the activity: the new intent should be shown
        mScenario.moveToState(Lifecycle.State.RESUMED)
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, otherAppName))
        )
            .check(matches(isDisplayed()))
    }

    @Test
    fun testDiscoveryCancelled_whileDestroyedAndRecreated() {
        // Send a second intent while the dialog is already shown
        val otherAppName = "Other Test App"
        val otherConnector = mock(NsdPickerConnector::class.java)
        val newIntent = makeStartIntent(otherConnector, otherAppName)
        mScenario.onActivity { activity ->
            activity.onNewIntent(newIntent)
        }

        // Stop the activity, cancelling the request while it is destroyed, and recreate it before
        // resuming it.
        mScenario.moveToState(Lifecycle.State.CREATED)
        mServiceReceiver.onCancelled()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        // Do not use mScenario.recreate() as it resumes the activity before recreating it
        mScenario.onActivity {
            it.recreate()
        }
        mScenario.moveToState(Lifecycle.State.RESUMED)

        // The new intent should be shown on recreation
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, otherAppName))
        )
            .check(matches(isDisplayed()))
    }

    @Test
    fun testNewIntentWhileShown() {
        // Send a new intent while the dialog is already shown
        val otherAppName = "Other Test App"
        val otherConnector = mock(NsdPickerConnector::class.java)
        val newIntent = makeStartIntent(otherConnector, otherAppName)

        mScenario.onActivity { activity ->
            activity.onNewIntent(newIntent)
        }

        // First dialog should still be visible
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, TEST_APP_NAME))
        )
            .check(matches(isDisplayed()))

        // Close the first dialog
        onDialogView(withText(R.string.choose_device_cancel)).perform(click())

        // Second dialog should now be shown
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, otherAppName))
        )
            .check(matches(isDisplayed()))

        val receiverCaptor = ArgumentCaptor.forClass(NsdServiceReceiver::class.java)
        verify(otherConnector).setServiceReceiver(receiverCaptor.capture())
        val otherServiceReceiver = receiverCaptor.value

        val otherServiceName = "Other Service"
        val serviceInfo = createServiceInfo(otherServiceName)
        otherServiceReceiver.onServiceFound(serviceInfo)
        onServiceInList(otherServiceName).check(matches(isDisplayed()))
        onServiceInList(otherServiceName).perform(click())
        verify(otherConnector).notifyServiceSelected(argThat { it.serviceName == otherServiceName })
    }

    @Test
    fun testNewIntentQueued_survivesRecreation() {
        // Send a new intent while the dialog is already shown
        val otherAppName = "Other Test App"
        val otherConnector = mock(NsdPickerConnector::class.java)
        val newIntent = makeStartIntent(otherConnector, otherAppName)

        mScenario.onActivity { activity ->
            activity.onNewIntent(newIntent)
        }

        // Recreate the activity: the first dialog should still be shown with the next intent queued
        mScenario.recreate()
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, TEST_APP_NAME))
        )
            .check(matches(isDisplayed()))

        // Close the first dialog
        onDialogView(withText(R.string.choose_device_cancel)).perform(click())

        // Second dialog should now be shown
        onDialogView(
            withText(mContext.getString(R.string.choose_device_summary, otherAppName))
        )
            .check(matches(isDisplayed()))
    }

    @Test
    fun testOnNewIntent_restartsActivityIfFinishing() {
        val otherAppName = "Other Test App"
        val otherConnector = mock(NsdPickerConnector::class.java)
        val newIntent = makeStartIntent(otherConnector, otherAppName)

        mScenario.onActivity { activity ->
            // Simulate onNewIntent being called immediately after onStop(), while the activity is
            // finishing
            activity.setStopAction {
                Handler(activity.mainLooper).postAtFrontOfQueue {
                    activity.onNewIntent(newIntent)
                }
            }
        }

        // Close the first dialog
        onDialogView(withText(R.string.choose_device_cancel)).perform(click())

        // A new activity should have been started for the new intent
        onDialogView(withText(mContext.getString(R.string.choose_device_summary, otherAppName)))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testRtlLayout_summaryAlignedRight() {
        overrideLocales(LocaleList.forLanguageTags("ur"))

        tryTest {
            onDialogView(withId(android.R.id.summary)).check(matches(isTextLayoutRtl()))
        } cleanup {
            // Reset to system locales
            overrideLocales(LocaleList.getEmptyLocaleList())
        }
    }

    private fun assertFinishesActivity(action: () -> Unit) {
        val cv = ConditionVariable(false)
        mScenario.onActivity {
            it.lifecycle.addObserver(object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    cv.open()
                }
            })
        }
        action()
        assertTrue(cv.block(TEST_TIMEOUT_MS), "Activity did not finish with $TEST_TIMEOUT_MS ms")
    }
}

private fun onDialogView(matcher: Matcher<View>) = onView(matcher).inRoot(isDialog())

private fun onServiceInList(serviceName: String) =
    onData(ServiceMatcher(serviceName)).inRoot(isDialog())

private fun isTextLayoutRtl(): Matcher<View> {
    return object : BoundedDiagnosingMatcher<View, TextView>(TextView::class.java) {
        override fun describeMoreTo(description: Description) {
            description.appendText("with text layout RTL")
        }

        override fun matchesSafely(textView: TextView, mismatchDescription: Description): Boolean {
            val textLayout = textView.layout ?: run {
                mismatchDescription.appendText("TextView has no layout")
                return false
            }
            for (line in 0 until textLayout.lineCount) {
                val actualDir = textLayout.getParagraphDirection(line)
                if (actualDir != Layout.DIR_RIGHT_TO_LEFT) {
                    mismatchDescription.appendText("Line $line has paragraph direction $actualDir")
                    return false
                }
            }
            return true
        }
    }
}

private fun overrideLocales(locales: LocaleList) {
    InstrumentationRegistry.getInstrumentation().apply {
        runOnMainSync {
            context.getSystemService(LocaleManager::class.java).applicationLocales = locales
        }
    }
}

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
