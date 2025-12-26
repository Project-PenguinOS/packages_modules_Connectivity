/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.net.nsd;

import android.annotation.FlaggedApi;
import android.annotation.LongDef;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Network;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;

import com.android.net.flags.Flags;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.util.Objects;

/**
 * Encapsulates parameters for {@link NsdManager#discoverServices}.
 */
@FlaggedApi(Flags.FLAG_NSD_SUBTYPES_SUPPORT_ENABLED)
public final class DiscoveryRequest implements Parcelable {

    /**
     * Flags for {@link DiscoveryRequest#getFlags()}.
     *
     * @hide
     */
    @LongDef(flag = true, prefix = { "FLAG_" }, value = {
            FLAG_NO_PICKER,
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface DiscoveryFlags {}

    /**
     * Indicates that a UI service picker should never be shown to the user for this request.
     *
     * <p>If the caller does not have {@link android.Manifest.permission.ACCESS_LOCAL_NETWORK},
     * this will cause the request to fail with
     * {@link android.net.nsd.NsdManager.DiscoveryListener#onStartDiscoveryFailed(String, int)} and
     * {@link NsdManager#FAILURE_PERMISSION_DENIED}.
     * @hide
     */
    @DiscoveryFlags
    public static final long FLAG_NO_PICKER = 1L << 0;

    // TODO: consider a FLAG_INCLUDE_USER_APPROVED which would allow the caller to discover
    // any services that were previously allow-listed for the app (through the picker).
    // FLAG_NO_PICKER | FLAG_INCLUDE_USER_APPROVED would only discover these services and not
    // send an error callback if the caller doesn't have permissions.
    // FLAG_SHOW_PICKER | FLAG_INCLUDE_USER_APPROVED would discover both services that the user
    // previously approved, plus the additional service the user may choose in the picker.

    private final int mProtocolType;

    @NonNull
    private final String mServiceType;

    @Nullable
    private final String mSubtype;

    @Nullable
    private final Network mNetwork;

    private final long mFlags;

    @NonNull
    public static final Creator<DiscoveryRequest> CREATOR =
            new Creator<>() {
                @Override
                public DiscoveryRequest createFromParcel(Parcel in) {
                    int protocolType = in.readInt();
                    String serviceType = in.readString();
                    String subtype = in.readString();
                    Network network =
                            in.readParcelable(Network.class.getClassLoader(), Network.class);
                    long flags = in.readLong();
                    return new DiscoveryRequest(protocolType, serviceType, subtype, network, flags);
                }

                @Override
                public DiscoveryRequest[] newArray(int size) {
                    return new DiscoveryRequest[size];
                }
            };

    private DiscoveryRequest(int protocolType, @NonNull String serviceType,
            @Nullable String subtype, @Nullable Network network, @DiscoveryFlags long flags) {
        mProtocolType = protocolType;
        mServiceType = serviceType;
        mSubtype = subtype;
        mNetwork = network;
        mFlags = flags;
    }

    /**
     * Returns the service type in format of dot-joint string of two labels.
     *
     * For example, "_ipp._tcp" for internet printer and "_matter._tcp" for <a
     * href="https://csa-iot.org/all-solutions/matter">Matter</a> operational device.
     */
    @NonNull
    public String getServiceType() {
        return mServiceType;
    }

    /**
     * Returns the subtype without the trailing "._sub" label or {@code null} if no subtype is
     * specified.
     *
     * For example, the return value will be "_printer" for subtype "_printer._sub".
     */
    @Nullable
    public String getSubtype() {
        return mSubtype;
    }

    /**
     * Returns the service discovery protocol.
     *
     * @hide
     */
    public int getProtocolType() {
        return mProtocolType;
    }

    /**
     * Returns the {@link Network} on which the query should be sent or {@code null} if no
     * network is specified.
     */
    @Nullable
    public Network getNetwork() {
        return mNetwork;
    }

    /**
     * Returns the discovery flags.
     *
     * @hide
     */
    @DiscoveryFlags
    public long getFlags() {
        return mFlags;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(", protocolType: ").append(mProtocolType)
            .append(", serviceType: ").append(mServiceType)
            .append(", subtype: ").append(mSubtype)
            .append(", network: ").append(mNetwork)
            .append(", flags: 0x").append(Long.toHexString(mFlags));
        return sb.toString();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        } else if (!(other instanceof DiscoveryRequest)) {
            return false;
        } else {
            DiscoveryRequest otherRequest = (DiscoveryRequest) other;
            return mProtocolType == otherRequest.mProtocolType
                    && Objects.equals(mServiceType, otherRequest.mServiceType)
                    && Objects.equals(mSubtype, otherRequest.mSubtype)
                    && Objects.equals(mNetwork, otherRequest.mNetwork)
                    && mFlags == otherRequest.mFlags;
        }
    }

    @Override
    public int hashCode() {
        return Objects.hash(mProtocolType, mServiceType, mSubtype, mNetwork, mFlags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeInt(mProtocolType);
        dest.writeString(mServiceType);
        dest.writeString(mSubtype);
        dest.writeParcelable(mNetwork, flags);
        dest.writeLong(mFlags);
    }

    /** The builder for creating new {@link DiscoveryRequest} objects. */
    public static final class Builder {
        private final int mProtocolType;

        @NonNull
        private String mServiceType;

        @Nullable
        private String mSubtype;

        @Nullable
        private Network mNetwork;

        private long mFlags;

        /**
         * Creates a new default {@link Builder} object with given service type.
         *
         * @throws IllegalArgumentException if {@code serviceType} is {@code null} or an empty
         * string
         */
        public Builder(@NonNull String serviceType) {
            this(NsdManager.PROTOCOL_DNS_SD, serviceType);
        }

        /** @hide */
        public Builder(int protocolType, @NonNull String serviceType) {
            NsdManager.checkProtocol(protocolType);
            mProtocolType = protocolType;
            setServiceType(serviceType);
        }

        /**
         * Sets the service type to be discovered or {@code null} if no services should be queried.
         *
         * The {@code serviceType} must be a dot-joint string of two labels. For example,
         * "_ipp._tcp" for internet printer. Additionally, the first label must start with
         * underscore ('_') and the second label must be either "_udp" or "_tcp". Otherwise, {@link
         * NsdManager#discoverServices} will fail with {@link NsdManager#FAILURE_BAD_PARAMETER}.
         *
         * @throws IllegalArgumentException if {@code serviceType} is {@code null} or an empty
         * string
         *
         * @hide
         */
        @NonNull
        public Builder setServiceType(@NonNull String serviceType) {
            if (TextUtils.isEmpty(serviceType)) {
                throw new IllegalArgumentException("Service type cannot be empty");
            }
            mServiceType = serviceType;
            return this;
        }

        /**
         * Sets the optional subtype of the services to be discovered.
         *
         * If a non-empty {@code subtype} is specified, it must start with underscore ('_') and
         * have the trailing "._sub" removed. Otherwise, {@link NsdManager#discoverServices} will
         * fail with {@link NsdManager#FAILURE_BAD_PARAMETER}. For example, {@code subtype} should
         * be "_printer" for DNS name "_printer._sub._http._tcp". In this case, only services with
         * this {@code subtype} will be queried, rather than all services of the base service type.
         *
         * Note that a non-empty service type must be specified with {@link #setServiceType} if a
         * non-empty subtype is specified by this method.
         */
        @NonNull
        public Builder setSubtype(@Nullable String subtype) {
            mSubtype = subtype;
            return this;
        }

        /**
         * Sets the {@link Network} on which the discovery queries should be sent.
         *
         * @param network the discovery network or {@code null} if the query should be sent on
         * all supported networks
         */
        @NonNull
        public Builder setNetwork(@Nullable Network network) {
            mNetwork = network;
            return this;
        }

        /**
         * Set all the discovery flags.
         *
         * @param flags A bitmask of flags that should be enabled, or {@code 0} to disable all flags
         * @see #setFlags(long, long)
         * @hide
         */
        public Builder setFlags(@DiscoveryFlags long flags) {
            mFlags = flags;
            return this;
        }

        /**
         * Sets the discovery flags.
         *
         * <p>Multiple flags may be enabled or disabled by passing the logical OR of the flags.
         *
         * <p>For example, to set {@link #FLAG_NO_PICKER}:
         * {@code setFlags(FLAG_NO_PICKER, FLAG_NO_PICKER)}
         *
         * <p>To disable {@link #FLAG_NO_PICKER}: {@code setFlags(0, FLAG_NO_PICKER)}
         * @param flags a bitmask of values to set; may be a single flag,
         *              the logical OR of multiple flags, or 0 to clear
         * @param mask a bitmask indicating which flags to modify
         * @hide
         */
        public Builder setFlags(@DiscoveryFlags long flags, @DiscoveryFlags long mask) {
            mFlags = (mFlags & ~mask) | (flags & mask);
            return this;
        }

        /**
         * Creates a new {@link DiscoveryRequest} object.
         */
        @NonNull
        public DiscoveryRequest build() {
            return new DiscoveryRequest(mProtocolType, mServiceType, mSubtype, mNetwork, mFlags);
        }
    }
}
