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

package com.android.server.connectivity.proxy;

import static android.content.pm.PackageManager.MATCH_SYSTEM_ONLY;

import static com.android.net.module.util.DeviceConfigUtils.TETHERING_MODULE_NAME;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.ResolveInfo;
import android.net.ProxyInfo;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;

import com.android.commercial.PacKey;
import com.android.multipacprocessor.IMultiPacService;
import com.android.multiproxyhandler.IMultiProxyService;
import com.android.net.module.util.HandlerUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * This class is responsible for coordinating the PAC script download and managing the
 * MultiPacService and MultiProxyService services. It keeps track of the PAC scripts that are
 * currently in use and ensures that the corresponding PAC components serving these scripts are
 * running.
 *
 * @hide
 */
public class PacCoordinator {
    private static final String TAG = "PacCoordinator";

    private static final String MULTI_PAC_INTENT_ACTION =
            "com.android.server.connectivity.intent.service.MULTI_PAC_SERVICE";
    private static final String MULTI_PAC_SERVICE_NAME =
            "com.android.multipacprocessor.MultiPacService";

    private static final String MULTI_PROXY_INTENT_ACTION =
            "com.android.server.connectivity.intent.service.MULTI_PROXY_SERVICE";
    private static final String MULTI_PROXY_SERVICE_NAME =
            "com.android.multiproxyhandler.MultiProxyService";

    private static final String CONNECTIVITY_RES_PKG_DIR = "/apex/" + TETHERING_MODULE_NAME + "/";

    private final Context mContext;
    private final Handler mConnectivityServiceHandler;
    private final PacDownloader mPacDownloader;

    private IMultiPacService mMultiPacService;
    private IMultiProxyService mMultiProxyService;

    private ServiceConnection mMultiPacServiceConnection;
    private ServiceConnection mMultiProxyServiceConnection;

    // The listener (MultiProxyTracker) that should be notified when the proxy server is running AND
    // the PAC script is downloaded and set in PacProcessor for a certain entity.
    private final MultiPacProxyInstalledListener mListener;

    public PacCoordinator(
            Context context, Handler netThreadHandler, MultiPacProxyInstalledListener listener) {
        mContext = context;
        mConnectivityServiceHandler = netThreadHandler;
        mListener = listener;
        mPacDownloader = new PacDownloader();
    }

    /**
     * Binds to MultiProxyService and MultiPacService instances (if not bound yet). Schedules PAC
     * script download, notifies MultiProxyService and MultiPacService to start a pair of
     * {ProxyServer; PacProcessor} for the given PAC script. Note that if the {ProxyServer;
     * PacProcessor} is already running for the current key, this is noop. mListener will be
     * notified when the PAC setup is complete.
     */
    public void startServingPacScript(ProxyInfo proxy, Optional<Integer> netId) {
        ensureRunningOnHandlerThread();
        bindToPacComponentsIfNeeded();
        mPacDownloader.downloadPacScript(
                new PacKey(proxy.getPacFileUrl(), netId), this::onPacScriptDownloaded);
    }

    /**
     * Notifies MultiProxyService and MultiPacService to stop the pair of {ProxyServer;
     * PacProcessor} for the given PAC script and (optionally) network. Invoked by MultiProxyTracker
     * when the PAC script is no longer be used.
     */
    public void stopServingPacScript(ProxyInfo proxy, Optional<Integer> netId) {
        ensureRunningOnHandlerThread();
        throw new UnsupportedOperationException("not implemented");
    }

    private void ensureRunningOnHandlerThread() {
        HandlerUtils.ensureRunningOnHandlerThread(mConnectivityServiceHandler);
    }

    /**
     * Get the name of the package that handles the given intent. The service name of the found
     * service should match the given service name. This is used to locate packages of
     * MultiPacService and MultiProxyService.
     *
     * @param intentAction the intent action to match.
     * @param serviceName the service name to match.
     * @return the package name if exactly one matching package is found, otherwise empty.
     */
    private Optional<String> findPackageNameByIntent(String intentAction, String serviceName) {
        final Intent intent = new Intent(intentAction);
        final List<ResolveInfo> pkgs =
                new ArrayList<>(
                        mContext.getPackageManager()
                                .queryIntentServices(intent, MATCH_SYSTEM_ONLY));
        pkgs.removeIf(
                pkg ->
                        !pkg.serviceInfo.applicationInfo.sourceDir.startsWith(
                                CONNECTIVITY_RES_PKG_DIR));
        if (pkgs.size() > 1) {
            Log.wtf(
                    TAG,
                    String.format(
                            "More than one package found for intent %s with service %s",
                            intentAction, serviceName));
            return Optional.empty();
        }
        if (pkgs.isEmpty()) {
            Log.wtf(
                    TAG,
                    String.format(
                            "No package found for intent %s with service %s",
                            intentAction, serviceName));
            return Optional.empty();
        }

        return Optional.of(pkgs.get(0).serviceInfo.packageName);
    }

    /**
     * Binds to MultiPacService instance.
     *
     * @param multiPacServiceIntent the intent to bind to MultiPacService.
     */
    private void startMultiPacService(Intent multiPacServiceIntent) {
        mMultiPacServiceConnection =
                new ServiceConnection() {
                    @Override
                    public void onServiceDisconnected(ComponentName component) {
                        ensureRunningOnHandlerThread();
                        Log.d(TAG, "MultiPacService is disconnected");
                    }

                    @Override
                    public void onServiceConnected(ComponentName component, IBinder binder) {
                        ensureRunningOnHandlerThread();
                        Log.d(TAG, "MultiPacService is connected");
                        mMultiPacService = IMultiPacService.Stub.asInterface(binder);
                        if (mMultiPacService == null) {
                            Log.e(TAG, "MultiPacService is null");
                        }
                    }
                };
        mContext.bindService(
                multiPacServiceIntent,
                // BIND_NOT_FOREGOUND is required to prevent PAC script from affecting the
                // system performance (for example, in a scenario where a script loops forever).
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                mConnectivityServiceHandler::post,
                mMultiPacServiceConnection);
    }

    /**
     * Binds to MultiProxyService instance.
     *
     * @param multiProxyServiceIntent the intent to bind to MultiProxyService.
     */
    private void startMultiProxyService(Intent multiProxyServiceIntent) {
        mMultiProxyServiceConnection =
                new ServiceConnection() {
                    @Override
                    public void onServiceDisconnected(ComponentName component) {
                        ensureRunningOnHandlerThread();
                        Log.d(TAG, "MultiProxyService is disconnected");
                    }

                    @Override
                    public void onServiceConnected(ComponentName component, IBinder binder) {
                        ensureRunningOnHandlerThread();
                        Log.d(TAG, "MultiProxyService is connected");
                        mMultiProxyService = IMultiProxyService.Stub.asInterface(binder);
                        if (mMultiProxyService == null) {
                            Log.e(TAG, "MultiProxyService is null");
                        }
                    }
                };
        mContext.bindService(
                multiProxyServiceIntent,
                // BIND_NOT_FOREGOUND is required to prevent PAC script from affecting the
                // system performance (for example, in a scenario where a script loops forever).
                // Even though PAC scripts are run in MultiPacService, MultiProxyService makes
                // a blocking call to MultiPacService when resolving a URL, so this flag is also
                // required here.
                Context.BIND_AUTO_CREATE | Context.BIND_NOT_FOREGROUND,
                mConnectivityServiceHandler::post,
                mMultiProxyServiceConnection);
    }

    /** Binds to MultiPacService and MultiProxyService instances (if not bound yet). */
    private void bindToPacComponentsIfNeeded() {
        if (mMultiPacServiceConnection != null && mMultiProxyServiceConnection != null) {
            // bindService was already called for both services.
            return;
        }

        Optional<String> multiPacPackageName =
                findPackageNameByIntent(MULTI_PAC_INTENT_ACTION, MULTI_PAC_SERVICE_NAME);
        if (multiPacPackageName.isEmpty()) {
            throw new IllegalStateException("MultiPacService can't be found");
        }

        Optional<String> multiProxyPackageName =
                findPackageNameByIntent(MULTI_PROXY_INTENT_ACTION, MULTI_PROXY_SERVICE_NAME);
        if (multiProxyPackageName.isEmpty()) {
            throw new IllegalStateException("MultiProxyService can't be found");
        }

        if (mMultiPacServiceConnection == null) {
            Intent intent = new Intent();
            intent.setClassName(multiPacPackageName.get(), MULTI_PAC_SERVICE_NAME);
            startMultiPacService(intent);
        }

        if (mMultiProxyServiceConnection == null) {
            Intent intent = new Intent();
            intent.setClassName(multiProxyPackageName.get(), MULTI_PROXY_SERVICE_NAME);
            startMultiProxyService(intent);
        }
    }

    /**
     * Invoked by PacDownloader when a PAC script is downloaded.
     *
     * @param pacKey PAC script identifier (URL and optional network ID).
     * @param pacScript the PAC script.
     */
    private void onPacScriptDownloaded(PacKey pacKey, String pacScript) {
        throw new UnsupportedOperationException("not implemented");
    }
}
