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

package com.android.connectivity.resources;

import static com.android.connectivity.resources.aidl.NsdPickerConnector.EXTRA_APP_NAME;
import static com.android.connectivity.resources.aidl.NsdPickerConnector.EXTRA_CONNECTOR;
import static com.android.connectivity.resources.aidl.NsdPickerConnector.EXTRA_REQUEST;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Intent;
import android.net.nsd.DiscoveryRequest;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.annotation.VisibleForTesting;
import androidx.fragment.app.FragmentActivity;

import com.android.connectivity.resources.NsdPickerFragment.PickerDialogListener;
import com.android.connectivity.resources.aidl.NsdPickerConnector;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Queue;

@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.TIRAMISU) // NsdManager is only updatable on T+
public class NsdPickerActivity extends FragmentActivity implements PickerDialogListener {
    private static final String TAG = NsdPickerActivity.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String FRAGMENT_PICKER_DIALOG = "picker_dialog";
    private static final String KEY_INTENT_QUEUE = "intent_queue";

    private final Queue<Intent> mIntentQueue = new ArrayDeque<>();
    private Runnable mStopReceiver;

    @UiThread
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (savedInstanceState == null) {
            mIntentQueue.add(getIntent());
        } else {
            ArrayList<Intent> savedQueue = savedInstanceState.getParcelableArrayList(
                    KEY_INTENT_QUEUE);
            if (savedQueue != null) {
                mIntentQueue.addAll(savedQueue);
            }
        }
        showNextPickerFragmentOrFinish();
    }

    @VisibleForTesting
    void setStopAction(Runnable stopReceiver) {
        mStopReceiver = stopReceiver;
    }

    @UiThread
    @Override
    protected void onStop() {
        super.onStop();
        if (mStopReceiver != null) {
            mStopReceiver.run();
        }
    }

    @UiThread
    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelableArrayList(KEY_INTENT_QUEUE, new ArrayList<>(mIntentQueue));
    }

    @UiThread
    private void showNextPickerFragmentOrFinish() {
        if (getSupportFragmentManager().findFragmentByTag(FRAGMENT_PICKER_DIALOG) != null) {
            // A dialog fragment is already shown
            if (DBG) Log.d(TAG, "showNextPickerFragmentOrFinish: fragment already shown");
            return;
        }

        while (!mIntentQueue.isEmpty()) {
            final Intent intent = mIntentQueue.poll();
            if (intent == null) {
                Log.wtf(TAG, "Null intent in queue");
                continue;
            }

            final Bundle intentBundle = intent.getExtras();
            if (intentBundle == null) {
                Log.wtf(TAG, "Invalid request intent: missing extras");
                continue;
            }

            final NsdPickerConnector connector = NsdPickerConnector.Stub.asInterface(
                    intentBundle.getBinder(EXTRA_CONNECTOR));
            final String appName = intentBundle.getString(EXTRA_APP_NAME);
            final DiscoveryRequest request =
                    intentBundle.getParcelable(EXTRA_REQUEST, DiscoveryRequest.class);
            if (connector == null || appName == null || request == null) {
                Log.wtf(TAG, "Invalid request intent: missing connector, appName or request: "
                        + intentBundle);
                continue;
            }

            if (DBG) Log.d(TAG, "showNextPickerFragmentOrFinish: showing a new dialog");
            NsdPickerFragment.newInstance(connector, appName, request)
                    .show(getSupportFragmentManager(), FRAGMENT_PICKER_DIALOG);
            return;
        }

        // No picker currently shown and no valid intents left to show a picker for.
        if (DBG) Log.d(TAG, "showNextPickerFragmentOrFinish: empty queue, finishing");
        finish();
    }

    @UiThread
    @Override
    public void onNewIntent(@NonNull Intent intent) {
        super.onNewIntent(intent);
        if (DBG) Log.d(TAG, "Received new intent: " + intent);
        if (isFinishing()) {
            // There is a possible race where finish() was called already, and onNewIntent is
            // received immediately afterwards instead of a new activity being created and receiving
            // onCreate. Create a new activity manually in that case instead of handling the intent
            // in a dying activity.
            Log.w(TAG, "onNewIntent: restarting activity after finish");
            startActivity(intent);
            return;
        }
        mIntentQueue.add(intent);
        showNextPickerFragmentOrFinish();
    }

    @UiThread
    @Override
    public void onDetachedAfterSelection() {
        showNextPickerFragmentOrFinish();
    }
}
