/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.commercial;

import android.annotation.NonNull;
import android.net.Uri;
import android.os.Parcel;
import android.os.Parcelable;
import java.util.Objects;
import java.util.Optional;

/**
 * This is a structure that holds the information needed to identify a PAC script. Each script is
 * uniquely identified by - the URL from which it is downloaded. - and the network for which this
 * PAC script is intended to be used. If not specified, the script is intended on the default
 * network.
 *
 * @hide
 */
public final class PacKey implements Parcelable {
    public final Uri pacUrl;
    public final Optional<Integer> networkId;

    public PacKey(Uri pacUrl, Optional<Integer> networkId) {
        this.pacUrl = pacUrl;
        this.networkId = networkId;
    }

    private PacKey(@NonNull Parcel in) {
        this.pacUrl = Uri.parse(in.readString());
        int rawNetworkId = in.readInt();
        this.networkId = rawNetworkId == -1 ? Optional.empty() : Optional.of(rawNetworkId);
    }

    @Override
    public void writeToParcel(@NonNull Parcel dest, int flags) {
        dest.writeString(pacUrl.toString());
        dest.writeInt(networkId.orElse(-1));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @NonNull
    public static final Creator<PacKey> CREATOR = new Creator<PacKey>() {
        @Override
        public PacKey createFromParcel(Parcel in) {
            return new PacKey(in);
        }

        @Override
        public PacKey[] newArray(int size) {
            return new PacKey[size];
        }
    };

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PacKey)) return false;
        PacKey pacKey = (PacKey) o;
        return Objects.equals(networkId, pacKey.networkId) && Objects.equals(pacUrl, pacKey.pacUrl);
    }

    @Override
    public int hashCode() {
        return Objects.hash(pacUrl, networkId);
    }

    @Override
    public String toString() {
        return "PacKey{" +
                "pacUrl='" + pacUrl + '\'' +
                ", networkId=" + networkId +
                '}';
    }
}

