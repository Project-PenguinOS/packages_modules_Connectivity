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

import android.annotation.RequiresNoPermission;
import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.net.nsd.DiscoveryRequest;
import android.net.nsd.NsdServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;
import android.util.SparseArray;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.UiThread;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;

import com.android.connectivity.resources.aidl.NsdPickerConnector;
import com.android.connectivity.resources.aidl.NsdServiceReceiver;

import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Set;
import java.util.TreeSet;

@SuppressLint("UseRequiresApi")
@TargetApi(Build.VERSION_CODES.TIRAMISU) // NsdManager is only updatable on T+
public class NsdPickerFragment extends DialogFragment {
    // Note the log tag reuses the parent activity tag instead of being specific to this class
    private static final String TAG = NsdPickerActivity.class.getSimpleName();
    private static final boolean DBG = Log.isLoggable(TAG, Log.DEBUG);
    private static final String KEY_STATE = "state";

    // Accessed only on the main looper
    private static final SparseArray<ServiceReceiver> sActiveReceivers = new SparseArray<>();
    private static int sNextReceiverId = 0;

    /** Listener interface for the host activity (in particular for testing). */
    interface PickerDialogListener {
        /** Called when the picker dialog fragment is detached after selection is done. */
        @UiThread
        void onDetachedAfterSelection();
    }

    @Nullable
    private ServiceAdapter mAdapter;
    private State mState;
    private PickerDialogListener mListener;
    private boolean mIsSelectionDone = false;

    public NsdPickerFragment() {
        // The fragment is being recreated; the state will be in savedInstanceState
        this(null);
    }

    private NsdPickerFragment(State state) {
        mState = state;
    }

    @UiThread
    static NsdPickerFragment newInstance(
            @NonNull NsdPickerConnector connector, @NonNull String appName,
            @NonNull DiscoveryRequest request) {
        return new NsdPickerFragment(new State(sNextReceiverId++, connector, appName, request));
    }

    public static class State implements Parcelable {
        private final int mReceiverId;
        @NonNull
        private final NsdPickerConnector mConnector;
        @NonNull
        private final String mAppName;
        @NonNull
        private final DiscoveryRequest mRequest;

        public State(int receiverId, @NonNull NsdPickerConnector conn, @NonNull String appName,
                @NonNull DiscoveryRequest request) {
            mReceiverId = receiverId;
            mConnector = conn;
            mAppName = appName;
            mRequest = request;
        }

        private State(Parcel parcel) {
            mReceiverId = parcel.readInt();
            mConnector = NsdPickerConnector.Stub.asInterface(parcel.readStrongBinder());
            mAppName = parcel.readString();
            mRequest = parcel.readParcelable(DiscoveryRequest.class.getClassLoader(),
                    DiscoveryRequest.class);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        @Override
        public void writeToParcel(Parcel dest, int flags) {
            dest.writeInt(mReceiverId);
            dest.writeStrongInterface(mConnector);
            dest.writeString(mAppName);
            dest.writeParcelable(mRequest, flags);
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

    /**
     * Adapter used to display services in a list.
     */
    private static class ServiceAdapter extends ArrayAdapter<NsdServiceInfo> {
        private final LayoutInflater mInflater = LayoutInflater.from(getContext());
        @Nullable
        private final String mDisplayNameAttribute;

        ServiceAdapter(Context context, @Nullable String displayNameAttribute) {
            super(context, R.layout.nsd_service_list_item);
            mDisplayNameAttribute = displayNameAttribute;
        }

        @UiThread
        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (convertView == null) {
                convertView = mInflater.inflate(R.layout.nsd_service_list_item, parent, false);
            }

            final NsdServiceInfo service = getItem(position);
            final TextView titleView = convertView.findViewById(android.R.id.title);
            // If the name is too long the title TextView is configured to handle it with
            // android:ellipsize="marquee".
            titleView.setText(getServiceDisplayName(service));

            convertView.findViewById(android.R.id.summary).setVisibility(View.GONE);

            final ImageView imageView = convertView.findViewById(android.R.id.icon);
            // TODO: set icon based on transport
            imageView.setImageResource(R.drawable.ic_wifi);

            return convertView;
        }

        @NonNull
        private String getServiceDisplayName(@NonNull NsdServiceInfo service) {
            if (mDisplayNameAttribute == null) {
                return service.getServiceName();
            }
            final byte[] value = service.getAttributes().get(mDisplayNameAttribute);
            if (value == null || value.length == 0) {
                return service.getServiceName();
            }
            // If the attribute value is not valid UTF-8, replacement characters will be used in the
            // string.
            return new String(value, StandardCharsets.UTF_8);
        }
    }

    @UiThread
    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        if (mState == null && savedInstanceState != null) {
            if (DBG) Log.d(TAG, "Dialog recreated, restoring state");
            mState = savedInstanceState.getParcelable(KEY_STATE, State.class);
        }
        if (mState == null) {
            throw new IllegalStateException("Missing state");
        }
        mAdapter = new ServiceAdapter(getContext(), mState.mRequest.getDisplayNameAttribute());
        final LayoutInflater inflater = LayoutInflater.from(getContext());
        final View customTitle = inflater.inflate(R.layout.nsd_picker_title, null);
        customTitle.<TextView>findViewById(android.R.id.summary).setText(
                getString(R.string.choose_device_summary, mState.mAppName));

        final DialogInterface.OnClickListener serviceSelectedListener =
                (dialogInterface, itemPosition) -> onServiceSelected(itemPosition);
        final AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setCustomTitle(customTitle)
                .setAdapter(mAdapter, serviceSelectedListener)
                .setNegativeButton(R.string.choose_device_cancel, (d, which) -> onCancel(d))
                .create();

        final ServiceReceiver receiver = makeOrGetServiceReceiver(
                mState.mReceiverId, mState.mConnector);
        receiver.setParent(this);

        return dialog;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (mState != null) {
            outState.putParcelable(KEY_STATE, mState);
        }
    }

    @UiThread
    private void onServiceSelected(int position) {
        if (mState != null) {
            try {
                final NsdServiceInfo service = mAdapter.getItem(position);
                if (DBG) Log.d(TAG, "Notify service selected for " + service);
                mState.mConnector.notifyServiceSelected(service);
            } catch (RemoteException e) {
                Log.e(TAG, "Failed to notify of selected service", e);
            }
        }
        selectionDone();
    }

    /**
     * Called when service selection is done, either by selecting a service or cancelling selection.
     */
    private void selectionDone() {
        mIsSelectionDone = true;
        dismiss();
        // onDetach() will be called after dismiss()
    }

    @UiThread
    public void onAttach(@NonNull Context context) {
        super.onAttach(context);
        if (context instanceof PickerDialogListener) {
            mListener = (PickerDialogListener) context;
        } else {
            throw new IllegalStateException("Hosting activity must implement PickerDialogListener");
        }
    }

    @UiThread
    @Override
    public void onDetach() {
        super.onDetach();
        if (DBG) Log.d(TAG, "Fragment detached");
        // The fragment may be detached when selection is done, or if the activity is being
        // recreated
        if (mIsSelectionDone) {
            if (mListener != null) {
                mListener.onDetachedAfterSelection();
            }
            removeReceiver();
        }
    }

    @Override
    public void onCancel(@NonNull DialogInterface dialog) {
        super.onCancel(dialog);
        if (DBG) Log.d(TAG, "Dialog cancelled");
        mIsSelectionDone = true;
        // onDetach will be called after onCancel
        // TODO: stop discovery, don't wait for the requesting app to time out and unregister
    }

    @UiThread
    void removeReceiver() {
        if (mState != null) {
            sActiveReceivers.remove(mState.mReceiverId);
        }
    }

    @UiThread
    private void updateProgressSpinner(boolean hasItems) {
        final Dialog dialog = getDialog();
        if (dialog == null) return;
        final View progressSpinner = dialog.findViewById(android.R.id.progress);
        if (progressSpinner == null) {
            return;
        }
        progressSpinner.setVisibility(hasItems ? View.GONE : View.VISIBLE);
    }

    @UiThread
    void onServicesUpdated(Set<NsdServiceInfo> services) {
        if (DBG) Log.d(TAG, "Services updated to " + services);
        updateProgressSpinner(!services.isEmpty());
        mAdapter.setNotifyOnChange(false);
        mAdapter.clear();
        mAdapter.addAll(services);
        mAdapter.notifyDataSetChanged();
    }

    @UiThread
    private static ServiceReceiver makeOrGetServiceReceiver(
            int id, NsdPickerConnector connector) {
        ServiceReceiver receiver = sActiveReceivers.get(id, null);
        if (receiver != null) {
            return receiver;
        }

        receiver = new ServiceReceiver(Looper.getMainLooper());
        sActiveReceivers.put(id, receiver);
        try {
            connector.setServiceReceiver(receiver);
        } catch (RemoteException e) {
            // The other end of the connector (NsdService) is in the system_server.
            throw e.rethrowFromSystemServer();
        }
        return receiver;
    }

    /**
     * A receiver registered with NsdService.
     *
     * <p>This receiver stays alive until unregistered, including if the fragment/activity are
     * destroyed/recreated. {@link ServiceReceiver#setParent(NsdPickerFragment)} is used to update
     * the fragment that gets service updates.
     */
    private static class ServiceReceiver extends NsdServiceReceiver.Stub {
        // Services are keyed by (serviceName, Network). The dialog only handles one service type
        // since each discovery request has a given service type, so it is always the same.
        @NonNull
        private final Set<NsdServiceInfo> mKnownServices = new TreeSet<>(
                Comparator.comparing(NsdServiceInfo::getServiceName)
                        .thenComparing(info ->
                                info.getNetwork() == null
                                        ? 0L
                                        : info.getNetwork().getNetworkHandle()
                        )
        );
        private final Handler mHandler;
        private WeakReference<NsdPickerFragment> mParent;

        @UiThread
        ServiceReceiver(Looper looper) {
            mHandler = new Handler(looper);
        }

        @UiThread
        void setParent(NsdPickerFragment fragment) {
            mParent = new WeakReference<>(fragment);
            // Notify the parent of existing services in the next handler loop, as the parent may
            // be initializing.
            mHandler.post(this::notifyServicesUpdated);
        }

        // The receiver is only registered on the connector, so no permission checks are necessary
        @RequiresNoPermission
        @Override
        public void onServiceFound(NsdServiceInfo service) {
            if (DBG) Log.d(TAG, "Service found: " + service);
            mHandler.post(() -> {
                mKnownServices.add(service);
                notifyServicesUpdated();
            });
        }

        @RequiresNoPermission
        @Override
        public void onServiceLost(NsdServiceInfo service) {
            if (DBG) Log.d(TAG, "Service lost: " + service);
            mHandler.post(() -> {
                mKnownServices.remove(service);
                notifyServicesUpdated();
            });
        }

        @UiThread
        private void notifyServicesUpdated() {
            final NsdPickerFragment parent = mParent.get();
            if (parent != null) {
                parent.onServicesUpdated(mKnownServices);
            }
            // If the parent was null, no update is necessary: this method will be triggered again
            // in setParent.
        }

        @RequiresNoPermission
        @Override
        public void onCancelled() {
            if (DBG) Log.d(TAG, "Service discovery cancelled");
            mHandler.post(() -> {
                final NsdPickerFragment parent = mParent.get();
                if (parent == null) return;
                parent.selectionDone();
            });
        }
    }
}
