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

package com.android.server.connectivity;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.app.AppOpsManager;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Looper;
import android.system.Os;

import androidx.test.filters.SmallTest;

import com.android.server.connectivity.LocalNetEventListener.Dependencies;
import com.android.testutils.DevSdkIgnoreRule;
import com.android.testutils.DevSdkIgnoreRunner;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(DevSdkIgnoreRunner.class)
@SmallTest
@DevSdkIgnoreRule.IgnoreUpTo(Build.VERSION_CODES.R)
public class LocalNetEventListenerTest {
    @Rule
    public final MockitoRule mockito = MockitoJUnit.rule();

    // See {@link android.app.AppOpsManager#OPSTR_ACCESS_LOCAL_NETWORK}
    // TODO: make this string visible to mainline
    private static final String APP_OP = "android:access_local_network";
    private static final String TEST_PACKAGE_1 = "test.package";
    private static final String TEST_PACKAGE_2 = "another.test.package";
    private static final int TEST_TIMEOUT_MS = 5000;

    @Mock
    private Context mContext;
    @Mock
    private PackageManager mPm;
    @Mock
    private AppOpsManager mAppOpsManager;
    private Dependencies mDeps;
    private FileDescriptor[] mPipe;
    private FileOutputStream mWriteFd;

    @Before
    public void setUp() throws Exception {
        doReturn(mPm).when(mContext).getPackageManager();
        doReturn(mAppOpsManager).when(mContext).getSystemService(AppOpsManager.class);

        mDeps = spy(new Dependencies());
        mPipe = Os.pipe();
        FileDescriptor readEnd = mPipe[0];
        mWriteFd = new FileOutputStream(mPipe[1]);
        doReturn(readEnd).when(mDeps).getFileDescriptor();
    }

    @After
    public void tearDown() throws Exception {
        if (mWriteFd != null) {
            try {
                mWriteFd.close();
            } catch (IOException e) {
                // Ignore close errors in teardown
            }
        }
        if (mPipe != null) {
            Looper.getMainLooper().getQueue().removeOnFileDescriptorEventListener(mPipe[0]);
            Os.close(mPipe[0]);
            Os.close(mPipe[1]);
        }
    }

    @Test
    public void testLocalNetEventListener_reportMetrics() throws Exception {
        CountDownLatch eventLatch = new CountDownLatch(2);
        doAnswer(invocation -> {
            eventLatch.countDown();
            return null;
        }).when(mDeps).writeStats(anyInt(), anyLong());
        doReturn(new int[]{1001, 2002, 1001, 2002, 4444, 5555})
                .doReturn(new int[]{})
                .when(mDeps).consumeEvents();
        LocalNetEventListener listener = new LocalNetEventListener(mDeps, mContext,
                Looper.getMainLooper(), true, false);
        listener.start();
        triggerEvent();

        assertTrue("Should have written 2 events",
                eventLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mDeps).writeStats(1001, 2);
        verify(mDeps).writeStats(4444, 1);
    }

    @Test
    public void testLocalNetEventListener_reportNoteOps() throws Exception {
        CountDownLatch eventLatch = new CountDownLatch(2);
        doAnswer(invocation -> {
            eventLatch.countDown();
            return null;
        }).when(mAppOpsManager).noteOpNoThrow(eq(APP_OP), anyInt(), anyString(), isNull(),
                isNull());
        doReturn(new String[]{TEST_PACKAGE_1}).when(mPm).getPackagesForUid(1001);
        doReturn(new String[]{TEST_PACKAGE_2}).when(mPm).getPackagesForUid(4444);
        doReturn(new int[]{1001, 2002, 4444, 5555})
                .doReturn(new int[]{})
                .when(mDeps).consumeEvents();
        LocalNetEventListener listener = new LocalNetEventListener(mDeps, mContext,
                Looper.getMainLooper(), false, true);
        listener.start();
        triggerEvent();

        assertTrue("Should have reported 2 note ops",
                eventLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mAppOpsManager).noteOpNoThrow(APP_OP, 1001, TEST_PACKAGE_1, null, null);
        verify(mAppOpsManager).noteOpNoThrow(APP_OP, 4444, TEST_PACKAGE_2, null, null);
    }

    @Test
    public void testLocalNetEventListener_noPackagesFound_skipsUid() throws Exception {
        CountDownLatch eventLatch = new CountDownLatch(1);
        doAnswer(invocation -> {
            eventLatch.countDown();
            return null;
        }).when(mAppOpsManager).noteOpNoThrow(eq(APP_OP), anyInt(), anyString(), isNull(),
                isNull());
        // Return an empty package list for UID 1001.
        doReturn(new String[]{}).when(mPm).getPackagesForUid(1001);
        doReturn(new String[]{TEST_PACKAGE_2}).when(mPm).getPackagesForUid(4444);
        doReturn(new int[]{1001, 2002, 4444, 5555})
                .doReturn(new int[]{})
                .when(mDeps).consumeEvents();
        LocalNetEventListener listener = new LocalNetEventListener(mDeps, mContext,
                Looper.getMainLooper(), false, true);
        listener.start();
        triggerEvent();

        assertTrue("Should have reported 1 note op",
                eventLatch.await(TEST_TIMEOUT_MS, TimeUnit.MILLISECONDS));
        verify(mAppOpsManager, never()).noteOpNoThrow(eq(APP_OP), eq(1001), anyString(), isNull(),
                isNull());
        verify(mAppOpsManager).noteOpNoThrow(APP_OP, 4444, TEST_PACKAGE_2, null, null);
    }

    @Test
    public void testLocalNetEventListener_requiresStart() throws Exception {
        LocalNetEventListener listener = new LocalNetEventListener(mDeps, mContext,
                Looper.getMainLooper(), true, false);
        // Don't start the listener before triggering events.
        triggerEvent();
        verify(mDeps, never()).consumeEvents();
    }

    @Test
    public void testLocalNetEventListener_requiresFlagsEnabled() throws Exception {
        LocalNetEventListener listener = new LocalNetEventListener(mDeps, mContext,
                Looper.getMainLooper(), false, false);
        listener.start();
        triggerEvent();
        verify(mDeps, never()).consumeEvents();
    }

    private void triggerEvent() throws IOException {
        // Write a single byte to the file descriptor. This makes it readable so the listener can
        // start consuming events
        mWriteFd.write(1);
        mWriteFd.flush();
    }
}
