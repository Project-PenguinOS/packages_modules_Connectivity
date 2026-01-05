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

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.fragment.app.FragmentActivity;

import com.android.connectivity.resources.aidl.NsdPickerConnector;

public class NsdPickerActivity extends FragmentActivity {
    private static final String TAG = NsdPickerActivity.class.getSimpleName();
    private static final String FRAGMENT_PICKER_DIALOG = "picker_dialog";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Fragments will be auto-recreated and re-added on activity recreation, only add if
        // this is the first creation.
        if (savedInstanceState == null) {
            addPickerFragment();
        }
    }

    private void addPickerFragment() {
        final Bundle intentBundle = getIntent().getExtras();
        final NsdPickerConnector connector = NsdPickerConnector.Stub.asInterface(
                intentBundle.getBinder(EXTRA_CONNECTOR));

        if (connector == null) {
            Log.wtf(TAG, "Missing connector");
            finish();
            return;
        }

        NsdPickerFragment.newInstance(connector, intentBundle.getString(EXTRA_APP_NAME))
                .show(getSupportFragmentManager(), FRAGMENT_PICKER_DIALOG);
    }

    @Override
    public void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // TODO: handle multiple requests for discovery
    }
}
