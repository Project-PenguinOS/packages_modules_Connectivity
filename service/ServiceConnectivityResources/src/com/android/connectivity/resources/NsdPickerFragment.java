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

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.Dialog;
import android.content.DialogInterface;
import android.os.Build;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.android.connectivity.resources.aidl.NsdPickerConnector;

@TargetApi(Build.VERSION_CODES.TIRAMISU)
public class NsdPickerFragment extends DialogFragment {
    // Note the log tag reuses the parent activity tag instead of being specific to this class
    private static final String TAG = NsdPickerActivity.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String KEY_STATE = "state";

    private State mState;

    public NsdPickerFragment() {
        // The fragment is being recreated; the state will be in savedInstanceState
        this(null);
    }

    private NsdPickerFragment(State state) {
        mState = state;
    }

    static NsdPickerFragment newInstance(
            @NonNull NsdPickerConnector connector, @NonNull String appName) {
        return new NsdPickerFragment(new State(connector, appName));
    }

    public static class State implements Parcelable {
        @NonNull
        private final NsdPickerConnector mConnector;
        @NonNull
        private final String mAppName;
        // TODO: add more state used by the fragment once it has logic to receive services

        public State(@NonNull NsdPickerConnector conn, @NonNull String appName) {
            mConnector = conn;
            mAppName = appName;
        }

        private State(Parcel parcel) {
            mConnector = NsdPickerConnector.Stub.asInterface(parcel.readStrongBinder());
            mAppName = parcel.readString();
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeStrongInterface(mConnector);
            dest.writeString(mAppName);
        }

        public static final Creator<State> CREATOR = new Creator<State>() {
            @Override
            public State createFromParcel(Parcel in) {
                return new State(in);
            }

            @Override
            public State[] newArray(int size) {
                return new State[size];
            }
        };
    }

    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mState == null && savedInstanceState != null) {
            if (DBG) Log.d(TAG, "Dialog recreated, restoring state");
            mState = savedInstanceState.getParcelable(KEY_STATE, State.class);
        }
        if (mState == null) {
            throw new IllegalStateException("Missing state");
        }
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final View customTitle = inflater.inflate(R.layout.nsd_picker_title, null);
        customTitle.<TextView>findViewById(android.R.id.summary).setText(
                getString(R.string.connect_to_service_summary, mState.mAppName));

        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setCustomTitle(customTitle)
                // TODO: add adapter to display services
                // .setAdapter( ... )
                .setNegativeButton(android.R.string.cancel, (d, which) -> onCancel(d))
                .create();
        // TODO: call mState.mConnector.setServiceReceiver and handle services
        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mState != null) {
            outState.putParcelable(KEY_STATE, mState);
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (DBG) Log.d(TAG, "Dialog cancelled");
        final Activity activity = getActivity();
        if (activity != null) {
            activity.finish();
        }
        // TODO: stop discovery, don't wait for the requesting app to time out and unregister
    }
}
