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

import android.annotation.NonNull;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.net.module.util.DnsUtils;
import com.android.net.module.util.SharedLog;

import java.io.PrintWriter;
import java.util.Objects;

/**
 * A repository storing whether a given UID can discover a given service.
 *
 * <p>This class is not thread-safe, and all methods are expected to be called on the NsdService
 * handler thread.
 */
public class ServiceAccessRepository {
    // UID -> allowed services mapping
    private final SparseArray<ArraySet<Service>> mAllowedServices = new SparseArray<>();
    private final SharedLog mSharedLog;

    /**
     * Build a new {@link ServiceAccessRepository}.
     */
    public ServiceAccessRepository(@NonNull SharedLog sharedLog) {
        mSharedLog = sharedLog;
    }

    /**
     * Add a service that the given UID is allowed to discover.
     */
    public void addAllowedService(int uid, @NonNull String serviceName,
            @NonNull String serviceType) {
        // TODO: make the allowlist also package name-specific
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(serviceType);
        final Service service = new Service(serviceName, serviceType);
        ArraySet<Service> services = mAllowedServices.get(uid);
        if (services == null) {
            services = new ArraySet<>();
            mAllowedServices.put(uid, services);
        }
        services.add(service);
        mSharedLog.log("Added " + serviceName + "." + serviceType + " for UID " + uid);

        // TODO: asynchronously persist the service to disk
    }

    /**
     * Query whether a given UID is allowed to discover a given service.
     */
    public boolean isServiceAllowed(int uid, @NonNull String serviceName,
            @NonNull String serviceType) {
        Objects.requireNonNull(serviceName);
        Objects.requireNonNull(serviceType);
        final ArraySet<Service> services = mAllowedServices.get(uid);
        if (services == null) {
            return false;
        }
        return services.contains(new Service(serviceName, serviceType));
    }

    /**
     * Load the list of allowed services from disk for a given UID.
     */
    public void loadUid(int uid) {
        // TODO: load allowed services from disk
    }

    /**
     * Unload the list of allowed services for a given UID.
     *
     * <p>Allowed services can be reloaded from disk using {@link #loadUid(int)}.
     */
    public void unloadUid(int uid) {
        mAllowedServices.remove(uid);
    }

    /**
     * Dump the contents of the repository for logging purposes.
     */
    public void dump(PrintWriter pw) {
        for (int i = 0; i < mAllowedServices.size(); i++) {
            final int uid = mAllowedServices.keyAt(i);
            final ArraySet<Service> services = mAllowedServices.valueAt(i);
            pw.println("UID " + uid + ":");
            for (int j = 0; j < services.size(); j++) {
                final Service service = services.valueAt(j);
                pw.println("  " + service.mName + "." + service.mType);
            }
        }
    }

    public static class Service {
        @NonNull
        final String mName;
        @NonNull
        final String mType;

        Service(@NonNull String name, @NonNull String type) {
            this.mName = DnsUtils.toDnsUpperCase(name);
            this.mType = DnsUtils.toDnsUpperCase(type);
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
            return Objects.hash(mName, mType);
        }
    }
}
