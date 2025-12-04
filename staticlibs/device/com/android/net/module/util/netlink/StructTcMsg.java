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
package com.android.net.module.util.netlink;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.net.module.util.Struct;

import java.nio.ByteBuffer;

/**
 * struct tcmsg
 *
 * see also:
 *
 *     include/uapi/linux/rtnetlink.h
 *
 * @hide
 */
public class StructTcMsg extends Struct {
    // Already aligned.
    public static final int STRUCT_SIZE = 20;

    @Field(order = 0, type = Type.U8, padding = 3)
    public final short family; // Address family of tc.
    @Field(order = 1, type = Type.S32)
    public final int ifindex;
    @Field(order = 2, type = Type.U32)
    public final long handle;
    @Field(order = 3, type = Type.U32)
    public final long parent;
    @Field(order = 4, type = Type.U32)
    public final long info;

    @VisibleForTesting
    public StructTcMsg(short family, int ifindex, int handle, int parent, int info) {
        this.family = family;
        this.ifindex = ifindex;
        this.handle = handle;
        this.parent = parent;
        this.info = info;
    }

    /**
     * Parse a tcmsg struct from a {@link ByteBuffer}.
     *
     * @param byteBuffer The buffer from which to parse the tcmsg.
     * @return the parsed tcmsg struct, or {@code null} if the tcmsg struct
     *         could not be parsed successfully (for example, if it was truncated).
     */
    @Nullable
    public static StructTcMsg parse(@NonNull final ByteBuffer byteBuffer) {
        if (byteBuffer.remaining() < STRUCT_SIZE) return null;

        // The ByteOrder must already have been set to native order.
        return Struct.parse(StructTcMsg.class, byteBuffer);
    }

    /**
     * Write a tcmsg struct to {@link ByteBuffer}.
     */
    public void pack(@NonNull final ByteBuffer byteBuffer) {
        // The ByteOrder must already have been set to native order.
        this.writeToByteBuffer(byteBuffer);
    }
}
