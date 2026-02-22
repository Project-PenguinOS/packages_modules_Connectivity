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
import android.content.ApexEnvironment;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.ArraySet;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.net.module.util.DeviceConfigUtils;
import com.android.server.connectivity.mdns.internal.ServiceAccessRepository.Service;

import java.io.File;
import java.util.ArrayList;

/**
 * A database to store service access allowlists on disk.
 *
 * <p>Note operations may generally fail notably if there is no disk space left. In that case this
 * class does not report errors but returns default values as if no allowlist was persisted.
 */
public class ServiceAccessDb {
    private static final String TAG = ServiceAccessDb.class.getSimpleName();
    private final ServiceAccessDbHelper mDbHelper;
    private final Clock mClock;

    public ServiceAccessDb(@NonNull Context context) {
        this(context, new Clock(), getDataDir());
    }

    @VisibleForTesting
    ServiceAccessDb(@NonNull Context context, @NonNull Clock clock, @NonNull File dataDir) {
        mDbHelper = new ServiceAccessDbHelper(context, dataDir);
        mClock = clock;
    }

    /**
     * A clock that can be mocked for testing.
     */
    public static class Clock {
        /**
         * Get the current wall clock time.
         *
         * <p>Note this may roll backwards/forward if system time is changed. This is only used to
         * sort which entries should be cleaned up in priority if an app has too many, so that is
         * fine.
         *
         * @see System#currentTimeMillis()
         */
        public long currentTimeMillis() {
            return System.currentTimeMillis();
        }
    }

    private static File getDataDir() {
        return ApexEnvironment.getApexEnvironment(DeviceConfigUtils.TETHERING_MODULE_NAME)
                .getDeviceProtectedDataDir();
    }

    /**
     * Close the currently open database if any.
     *
     * <p>The database is reopened automatically when necessary, but opening it is expensive, so
     * this may be done to free up resources if no access is expected for a while.
     */
    public void close() {
        mDbHelper.close();
    }

    private static final class PackageContract {
        private PackageContract() {}
        static final String TABLE_NAME = "package";
        static final String COL_UID = "uid";
        static final String COL_PACKAGE_NAME = "package_name";
        // Records when this package was last verified to still exist. While package uninstall
        // notifications are used to clean up the database, they may be missed (if the device shuts
        // down immediately after a uninstall for example), so this allows cleaning up package
        // allowlists if this happens.
        static final String COL_LAST_EXISTENCE_CHECKED_TIME = "last_existence_checked_time";

        static void create(@NonNull SQLiteDatabase db) {
            // Access is allowlisted per package name as a single UID may have multiple
            // different packages; but also by UID as a package may be installed on multiple
            // users.
            // While entries are removed on uninstall, this may be delayed or missed if the
            // device shuts down immediately after a uninstall. So this also helps guard against
            // races where a package is uninstalled and immediately swapped with a different one
            // with the same name (the UID would be different), and where it is uninstalled, the
            // system reboots and reuses the UID for another newly installed package (the
            // package name would generally be different).
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + COL_UID + " INTEGER NOT NULL, "
                    + COL_PACKAGE_NAME + " TEXT NOT NULL, "
                    + COL_LAST_EXISTENCE_CHECKED_TIME + " INTEGER NOT NULL, "
                    + "PRIMARY KEY (" + COL_UID + ", " + COL_PACKAGE_NAME + "))");
            // Allow efficiently querying packages that have not been refreshed for the longest time
            db.execSQL("CREATE INDEX idx_pkg_last_checked_time ON "
                    + TABLE_NAME + " (" + COL_UID + ", " + COL_PACKAGE_NAME + ", "
                    + COL_LAST_EXISTENCE_CHECKED_TIME + ")");
        }
    }

    private static final class ServiceAccessContract {
        private ServiceAccessContract() {}
        static final String TABLE_NAME = "service_access";
        static final String COL_ID = "id";
        static final String COL_UID = "uid";
        static final String COL_PACKAGE_NAME = "package_name";
        static final String COL_SERVICE_NAME = "service_name";
        static final String COL_SERVICE_TYPE = "service_type";
        static final String COL_LAST_SEEN_TIME = "last_seen_time";

        static void create(@NonNull SQLiteDatabase db) {
            db.execSQL("CREATE TABLE " + TABLE_NAME + " ("
                    + COL_ID + " INTEGER PRIMARY KEY, "
                    + COL_UID + " INTEGER NOT NULL, "
                    + COL_PACKAGE_NAME + " TEXT NOT NULL, "
                    + COL_SERVICE_NAME + " TEXT NOT NULL, "
                    + COL_SERVICE_TYPE + " TEXT NOT NULL, "
                    + COL_LAST_SEEN_TIME + " INTEGER NOT NULL, "
                    + "FOREIGN KEY (" + COL_UID + ", " + COL_PACKAGE_NAME + ") REFERENCES "
                    + PackageContract.TABLE_NAME + " (" + PackageContract.COL_UID + ", "
                    + PackageContract.COL_PACKAGE_NAME + ") ON DELETE CASCADE, "
                    + "UNIQUE (" + COL_UID + ", " + COL_PACKAGE_NAME + ", "
                    + COL_SERVICE_NAME + ", " + COL_SERVICE_TYPE + "))");
            // Allow efficiently querying entries for a uid+package (which is a prefix of the
            // index), and deleting the oldest entries for a given package+uid
            db.execSQL("CREATE INDEX idx_service_access_pkg_time ON "
                    + TABLE_NAME + " (" + COL_UID + ", " + COL_PACKAGE_NAME + ", "
                    + COL_LAST_SEEN_TIME + ")");
        }
    }

    private static class ServiceAccessDbHelper extends SQLiteOpenHelper {
        private static final int DATABASE_VERSION = 1;
        private static final String DATABASE_FILENAME = "NsdServiceAccess.db";

        ServiceAccessDbHelper(@NonNull Context context, @NonNull File dataDir) {
            super(context, new File(dataDir, DATABASE_FILENAME).getAbsolutePath(),
                    /* cursorFactory= */null, DATABASE_VERSION);
        }

        @Override
        public void onConfigure(SQLiteDatabase db) {
            db.setForeignKeyConstraintsEnabled(true);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            db.beginTransaction();
            try {
                PackageContract.create(db);
                ServiceAccessContract.create(db);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {}
    }

    /**
     * Initialize the database for a given package.
     *
     * <p>This will record the package as having been verified to exist at this time.
     */
    public void refreshPackage(int uid, @NonNull String packageName) {
        try {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();

            // Do not use replaceOrThrow (or the equivalent insertWithOnConflict + CONFLICT_REPLACE)
            // as that would delete/re-add rows on conflict, which can delete allowlist entries due
            // to foreign key constraints.
            final long now = mClock.currentTimeMillis();
            db.execSQL("INSERT INTO " + PackageContract.TABLE_NAME + "("
                    + PackageContract.COL_UID + ", "
                    + PackageContract.COL_PACKAGE_NAME + ", "
                    + PackageContract.COL_LAST_EXISTENCE_CHECKED_TIME
                    + ") VALUES(" + uid + ", ?, " + now
                    + ") ON CONFLICT (" + PackageContract.COL_UID + ", "
                    + PackageContract.COL_PACKAGE_NAME + ") DO UPDATE SET "
                    + PackageContract.COL_LAST_EXISTENCE_CHECKED_TIME + "=" + now,
                    new Object[] { packageName }
            );
        } catch (SQLiteException e) {
            Log.e(TAG, "Error initializing package", e);
        }
    }

    /**
     * Get all allowed services for a UID/package.
     */
    public ArraySet<Service> getAllowedServices(int uid, @NonNull String packageName) {
        final String[] queryCols = new String[] {
                ServiceAccessContract.COL_SERVICE_NAME,
                ServiceAccessContract.COL_SERVICE_TYPE
        };
        final String selection = ServiceAccessContract.COL_UID + "=" + uid
                + " AND " + ServiceAccessContract.COL_PACKAGE_NAME + "=?";
        try {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(ServiceAccessContract.TABLE_NAME, queryCols,
                    selection, new String[] { packageName }, null, null, null)) {

                final ArraySet<Service> services = new ArraySet<>();
                while (cursor.moveToNext()) {
                    services.add(new Service(cursor.getString(0), cursor.getString(1)));
                }
                return services;
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Error getting allowed services", e);
            // Assume no services are allowed
            return new ArraySet<>(0);
        }
    }

    /**
     * Add an allowed service for a UID/package.
     *
     * <p>This assumes that {@link #refreshPackage(int, String)} has been called.
     */
    public void addAllowedService(int uid, @NonNull String packageName, @NonNull String serviceName,
            @NonNull String serviceType) {
        try {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues(5);
            values.put(ServiceAccessContract.COL_UID, uid);
            values.put(ServiceAccessContract.COL_PACKAGE_NAME, packageName);
            values.put(ServiceAccessContract.COL_SERVICE_NAME, serviceName);
            values.put(ServiceAccessContract.COL_SERVICE_TYPE, serviceType);
            values.put(ServiceAccessContract.COL_LAST_SEEN_TIME, mClock.currentTimeMillis());
            db.replaceOrThrow(ServiceAccessContract.TABLE_NAME, null, values);
        } catch (SQLiteException e) {
            Log.e(TAG, "Error adding allowed service", e);
        }
    }

    /**
     * Record that the specified services were seen at the current time, if they are in the DB.
     */
    public void recordServicesSeen(int uid, @NonNull String packageName,
            @NonNull ArraySet<Service> services) {
        final long now = mClock.currentTimeMillis();
        try {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            final ContentValues values = new ContentValues(1);
            values.put(ServiceAccessContract.COL_LAST_SEEN_TIME, now);

            final String selection = ServiceAccessContract.COL_PACKAGE_NAME + "=?"
                    + " AND " + ServiceAccessContract.COL_SERVICE_NAME + "=?"
                    + " AND " + ServiceAccessContract.COL_SERVICE_TYPE + "=?"
                    + " AND " + ServiceAccessContract.COL_UID + "=" + uid;
            db.beginTransaction();
            try {
                services.forEach(s -> db.update(
                        ServiceAccessContract.TABLE_NAME, values,
                        selection, new String[]{packageName, s.mName, s.mType}));
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Error recording service access", e);
        }
    }

    /**
     * Delete older allowlist entries for a uid/package.
     *
     * @param numEntriesToKeep How many entries to *keep* in the allowlist for this package.
     */
    public void deleteOlderEntries(int uid, @NonNull String packageName, int numEntriesToKeep) {
        try {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            // Delete all entries where the ID is in the list of entry IDs for the uid/package,
            // skipping the first numEntriesToKeep (ordering by most recent).
            db.delete(ServiceAccessContract.TABLE_NAME, ServiceAccessContract.COL_ID + " IN ("
                    + "SELECT " + ServiceAccessContract.COL_ID
                    + " FROM " + ServiceAccessContract.TABLE_NAME
                    + " WHERE " + ServiceAccessContract.COL_UID + "=" + uid
                    + " AND " + ServiceAccessContract.COL_PACKAGE_NAME + "=?"
                    + " ORDER BY " + ServiceAccessContract.COL_LAST_SEEN_TIME + " DESC"
                    // No limit, but skip the first X entries
                    + " LIMIT -1 OFFSET " + numEntriesToKeep
                    + ")",
                    new String[] { packageName });
        } catch (SQLiteException e) {
            Log.e(TAG, "Error deleting older entries for package " + packageName, e);
        }
    }

    /**
     * Delete all entries in the allowlist for a uid/package.
     */
    public void deleteAllEntriesForPackage(int uid, @NonNull String packageName) {
        try {
            final SQLiteDatabase db = mDbHelper.getWritableDatabase();
            // Delete from the package table; the foreign key CASCADE constraint will handle
            // deleting from the service_access table.
            db.delete(PackageContract.TABLE_NAME, PackageContract.COL_UID + "=" + uid
                            + " AND " + PackageContract.COL_PACKAGE_NAME + "=?",
                    new String[] { packageName });
        } catch (SQLiteException e) {
            Log.e(TAG, "Error deleting entries for package " + packageName, e);
        }
    }

    /**
     * Return value for {@link #getLeastRefreshedPackages(int)}.
     */
    public static class PackageEntry {
        public final int uid;
        @NonNull
        public final String packageName;

        PackageEntry(int uid, @NonNull String packageName) {
            this.uid = uid;
            this.packageName = packageName;
        }
    }

    /**
     * Get packages for which {@link #refreshPackage(int, String)} has not been called for the
     * longest time.
     *
     * <p>The intended usage is for the caller to check whether the returned packages are still
     * installed, and either call {@link #deleteAllEntriesForPackage(int, String)} or
     * {@link #refreshPackage(int, String)} to clean up or refresh them.
     */
    public ArrayList<PackageEntry> getLeastRefreshedPackages(int maxCount) {
        final String[] queryCols = new String[] {
                PackageContract.COL_UID,
                PackageContract.COL_PACKAGE_NAME
        };
        try {
            final SQLiteDatabase db = mDbHelper.getReadableDatabase();
            try (Cursor cursor = db.query(PackageContract.TABLE_NAME, queryCols,
                    /* selection= */null, /* selectionArgs= */null, /* groupBy= */null,
                    /* having= */null,
                    /* orderBy= */PackageContract.COL_LAST_EXISTENCE_CHECKED_TIME,
                    /* limit= */String.valueOf(maxCount))) {

                final ArrayList<PackageEntry> packages = new ArrayList<>();
                while (cursor.moveToNext()) {
                    packages.add(new PackageEntry(cursor.getInt(0), cursor.getString(1)));
                }
                return packages;
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Error getting least refreshed packages", e);
            // Assume there is no package to clean up
            return new ArrayList<>(0);
        }
    }
}
