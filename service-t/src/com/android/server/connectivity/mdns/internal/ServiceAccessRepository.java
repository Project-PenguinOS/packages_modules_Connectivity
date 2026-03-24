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

package com.android.server.connectivity.mdns.internal;

import static android.content.Intent.ACTION_PACKAGE_DATA_CLEARED;
import static android.content.Intent.ACTION_PACKAGE_REMOVED;
import static android.content.Intent.EXTRA_DATA_REMOVED;

import android.annotation.NonNull;
import android.annotation.RequiresApi;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.util.ArrayMap;
import android.util.ArraySet;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.DnsUtils;
import com.android.net.module.util.SharedLog;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Objects;

/**
 * A repository storing whether a given UID can discover a given service.
 *
 * <p>This class is not thread-safe, and all methods are expected to be called on the NsdService
 * handler thread.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class ServiceAccessRepository {

    /**
     * How many services should be persisted at most for a given package.
     *
     * <p>This is chosen using the same rule-of-thumb as the maximum number of concurrent requests
     * per client (see NsdService.ClientInfo#MAX_LIMIT).
     */
    private static final int MAX_PERSIST_SERVICES_COUNT = 200;
    private static final String SCHEME_PACKAGE = "package";

    /**
     * How many packages to check for existence when performing database maintenance.
     *
     * <p>Entries are deleted when a package is uninstalled, but this may be missed if the device
     * is shut down just after an uninstall. To make sure they get cleaned up, database maintenance
     * will check a few "unchecked for the longest time" packages to make sure they still exist.
     * This value is chosen arbitrarily as what feels right to minimize the cost of the maintenance,
     * while making sure entries get eventually cleaned up in a small number of maintenance cycles.
     */
    @VisibleForTesting
    static final int LEAST_REFRESHED_PACKAGES_CHECK_COUNT = 3;

    private static final long MIN_MAINTENANCE_INTERVAL_MS = 60_000L;

    private final ArrayMap<PackageEntry, ArraySet<Service>> mAllowedServices = new ArrayMap<>();
    private final Context mContext;
    private final Handler mHandler;
    private final SharedLog mSharedLog;
    private final ServiceAccessDb mDb;

    // Time of the last maintenance scheduled, as per SystemClock.elapsedRealtime()
    private long mLastMaintenanceScheduledMs;

    private final BroadcastReceiver mPackageClearReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (ACTION_PACKAGE_REMOVED.equals(action)) {
                // When an app is updated ACTION_PACKAGE_REMOVED is also sent.
                // Do not remove from the allowlist if this is just an app update, or if the app is
                // uninstalled while keeping data (so if the app is later reinstalled, the allowlist
                // will still be there). Apps removed while keeping data are still tracked by
                // PackageManager with the same AppId, so they would have the same UID when
                // reinstalled.
                if (!intent.getBooleanExtra(EXTRA_DATA_REMOVED, true)) {
                    return;
                }
            } else if (!ACTION_PACKAGE_DATA_CLEARED.equals(action)) {
                return;
            }
            mHandler.post(() -> {
                final int uid = intent.getIntExtra(Intent.EXTRA_UID, -1);
                final Uri packageData = intent.getData();
                if (uid == -1 || packageData == null) {
                    // Note uid == -1 is expected with ACTION_PACKAGE_DATA_CLEARED for instant apps
                    mSharedLog.w("Package cleared intent missing UID or data: " + intent);
                    return;
                }
                final String packageName = packageData.getSchemeSpecificPart();
                if (packageName == null) {
                    mSharedLog.w("Package cleared intent missing package name");
                    return;
                }
                handlePackageCleared(uid, packageName);
            });
        }
    };

    /**
     * Build a new {@link ServiceAccessRepository}.
     */
    public ServiceAccessRepository(@NonNull Context context, @NonNull Looper looper,
            @NonNull SharedLog sharedLog) {
        this(context, looper, sharedLog, new ServiceAccessDb(context));
    }

    @VisibleForTesting
    public ServiceAccessRepository(@NonNull Context context, @NonNull Looper looper,
            @NonNull SharedLog sharedLog, @NonNull ServiceAccessDb db) {
        mContext = context;
        mSharedLog = sharedLog;
        mDb = db;
        mHandler = new Handler(looper);
    }

    /**
     * Start monitoring packages to clean up stale entries in storage.
     */
    public void start() {
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PACKAGE_REMOVED);
        filter.addAction(ACTION_PACKAGE_DATA_CLEARED);
        filter.addDataScheme(SCHEME_PACKAGE);
        mContext.createContextAsUser(UserHandle.ALL, /* flags= */0)
                .registerReceiver(mPackageClearReceiver, filter);
    }

    /**
     * Add a service that the given UID is allowed to discover.
     */
    public void addAllowedService(int uid, @NonNull String packageName, @NonNull String serviceName,
            @NonNull String serviceType) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(serviceType);
        final PackageEntry pkg = new PackageEntry(uid, packageName);
        final Service service = new Service(serviceName, serviceType,
                /* needsSeenTimeRefresh= */false);
        ArraySet<Service> services = mAllowedServices.get(pkg);
        if (services == null) {
            services = new ArraySet<>();
            mAllowedServices.put(pkg, services);
        }
        final boolean added = services.add(service);
        mSharedLog.log("Added " + serviceName + "." + serviceType + " for UID " + uid);
        final int servicesCount = services.size();

        mHandler.post(() -> {
            // Only services count on disk is limited; the in-memory list remains complete, so this
            // is only limiting for the next time the NsdManager client connects.
            if (added && servicesCount >= MAX_PERSIST_SERVICES_COUNT) {
                mDb.deleteOlderEntries(uid, packageName, MAX_PERSIST_SERVICES_COUNT - 1);
            }
            mDb.addAllowedService(uid, packageName, serviceName, serviceType);
        });
    }

    /**
     * Query whether a given UID is allowed to discover a given service.
     */
    public boolean isServiceAllowed(int uid, @NonNull String packageName,
            @NonNull String serviceName, @NonNull String serviceType) {
        Objects.requireNonNull(packageName);
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(serviceType);
        final ArraySet<Service> services = mAllowedServices.get(new PackageEntry(uid, packageName));
        if (services == null) {
            return false;
        }
        final Service service = new Service(serviceName, serviceType,
                /* needsSeenTimeRefresh= */true);
        if (services.remove(service)) {
            // Ensure "needsSeenTimeRefresh" is set to true
            services.add(service);
            return true;
        }
        return false;
    }

    /**
     * Load the list of allowed services from disk for a given package.
     *
     * <p>This loads the list synchronously if not already in memory, and is expected to be called
     * once before any operation from the specified package.
     */
    public void loadPackage(int uid, @NonNull String packageName) {
        mDb.refreshPackage(uid, packageName);
        final PackageEntry pkg = new PackageEntry(uid, packageName);
        if (!mAllowedServices.containsKey(pkg)) {
            mAllowedServices.put(pkg, mDb.getAllowedServices(uid, packageName));
        }
    }

    /**
     * Unload the list of allowed services for a given UID.
     *
     * <p>Allowed services can be reloaded from disk using {@link #loadPackage(int, String)}.
     */
    public void unloadPackage(int uid, @NonNull String packageName) {
        final ArraySet<Service> services = mAllowedServices.remove(
                new PackageEntry(uid, packageName));
        if (services == null) {
            return;
        }
        // This records services as seen now, which is not exactly the right time, and some updates
        // may be missed if the device shuts down without unloading the package. But the last seen
        // time is only used to order which entries should be cleaned up in priority, so this is
        // fine.
        mDb.recordServicesSeen(uid, packageName, services);
        closeDbIfNoActivePackage();
    }

    private void handlePackageCleared(int uid, @NonNull String packageName) {
        // In theory this could race with service allowed entries being added, if the add is delayed
        // after the removal broadcast or if the app is cleared and restarted quickly. In that
        // case the database will have no entry for the package so database adds to the allowlist
        // will just fail until refreshPackage is called again.
        // This should be fine because very hard to trigger, and it only causes persistence to be
        // skipped until the next app start.
        // Entries are *not* removed from the in-memory copy (mAllowedServices), as this will be
        // done when unloadPackage is called, on binder death notifications (processes are killed
        // when their data is cleared). This means the race only affects persistence as described,
        // and avoids unexpected edge-cases where entries could be removed while the app is running.
        mSharedLog.log("Deleting entries for " + uid + ", " + packageName);
        mDb.deleteAllEntriesForPackage(uid, packageName);
        closeDbIfNoActivePackage();
    }

    /**
     * If no package is active, close the database to save memory. It is auto-reopened when needed,
     * although that is a bit expensive.
     */
    private void closeDbIfNoActivePackage() {
        if (mAllowedServices.isEmpty()) {
            mDb.close();
        }
    }

    /**
     * Schedule database maintenance for the next handler loop, if not rate-limited.
     */
    public void maybeScheduleDatabaseMaintenance() {
        final long now = SystemClock.elapsedRealtime();
        if (mLastMaintenanceScheduledMs != 0L
                && now - mLastMaintenanceScheduledMs < MIN_MAINTENANCE_INTERVAL_MS) {
            return;
        }
        mLastMaintenanceScheduledMs = now;
        // This is expected to run on the handler already, but run the maintenance in the next
        // handler loop to avoid blocking the caller
        mHandler.post(this::runDatabaseMaintenance);
    }

    private void runDatabaseMaintenance() {
        final ArrayList<PackageEntry> packages =
                mDb.getLeastRefreshedPackages(LEAST_REFRESHED_PACKAGES_CHECK_COUNT);

        for (PackageEntry pkg : packages) {
            try {
                final PackageManager userPm = mContext.createContextAsUser(
                        UserHandle.getUserHandleForUid(pkg.uid), 0).getPackageManager();
                final int uid = userPm.getPackageUid(pkg.packageName, /* flags= */0);
                if (uid == pkg.uid) {
                    mDb.refreshPackage(pkg.uid, pkg.packageName);
                    continue;
                }
                mSharedLog.w("Package was reinstalled with new UID, clearing list: " + pkg);
            } catch (PackageManager.NameNotFoundException e) {
                // Fall through
                mSharedLog.w("Package was removed, clearing list: " + pkg);
            }
            mDb.deleteAllEntriesForPackage(pkg.uid, pkg.packageName);
        }
    }

    /**
     * Dump the contents of the repository for logging purposes.
     */
    public void dump(PrintWriter pw) {
        for (int i = 0; i < mAllowedServices.size(); i++) {
            final PackageEntry pkg = mAllowedServices.keyAt(i);
            final ArraySet<Service> services = mAllowedServices.valueAt(i);
            pw.println(pkg + ":");
            for (int j = 0; j < services.size(); j++) {
                final Service service = services.valueAt(j);
                pw.println("  " + service);
            }
        }
    }

    public static class PackageEntry {
        public final int uid;
        @NonNull
        public final String packageName;

        PackageEntry(int uid, @NonNull String packageName) {
            this.uid = uid;
            this.packageName = packageName;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PackageEntry)) return false;
            PackageEntry pkg = (PackageEntry) o;
            return Objects.equals(packageName, pkg.packageName) && uid == pkg.uid;
        }

        @Override
        public int hashCode() {
            // Similar to Objects.hashCode but avoids boxing into an array
            return 31 * uid + packageName.hashCode();
        }

        @NonNull
        @Override
        public String toString() {
            return packageName + " (" + uid + ")";
        }
    }

    public static class Service {
        @NonNull
        final String mName;
        @NonNull
        final String mType;

        /**
         * Whether the service was seen by the app since the app allowlist was loaded.
         *
         * <p>Note this is not used for lookups, so not in equals/hashCode
         */
        final boolean mNeedsSeenTimeRefresh;

        @VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
        public Service(@NonNull String name, @NonNull String type, boolean needsSeenTimeRefresh) {
            this.mName = DnsUtils.toDnsUpperCase(name);
            this.mType = DnsUtils.toDnsUpperCase(type);
            this.mNeedsSeenTimeRefresh = needsSeenTimeRefresh;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Service)) return false;
            Service service = (Service) o;
            return Objects.equals(mName, service.mName) && Objects.equals(mType, service.mType);
        }

        @Override
        public int hashCode() {
            // Similar to Objects.hashCode but avoids boxing into an array
            return 31 * mName.hashCode() + mType.hashCode();
        }

        @NonNull
        @Override
        public String toString() {
            return mName + "." + mType;
        }
    }
}
