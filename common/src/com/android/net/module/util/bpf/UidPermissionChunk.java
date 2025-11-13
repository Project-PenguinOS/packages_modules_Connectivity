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

package com.android.net.module.util.bpf;

import com.android.net.module.util.Struct;
import com.android.net.module.util.Struct.Field;
import com.android.net.module.util.Struct.Type;

/**
 * A Struct for UidPermissionChunk type.
 *
 * A UidPermissionChunk contains one long array. Each long variable in the array can
 * store permission for a group of UIDs. When used in a
 * map<ChunkId, UidPermissionChunk>, <Chunk ID, Index, Shift> uniquely identify
 * permission bits for UID using the following fomula
 *
 * (val[chunkId][index] >> shift) & UID_PERMISSION_MASK
 */
public class UidPermissionChunk extends Struct {

    // LINT.IfChange(uid_permission_chunk_type)
    // Each UID has 3 permission bits (ACCESS_LOCAL_NETWORK / INTERNET /
    // UPDATE_DEVICE_STATS). One int64 can store up to 21 UIDs (3 * 21 = 63 bits)
    public final static int PERMISSION_COUNT = 3;
    public final static int UIDS_PER_INT64 = 21;
    public final static int CHUNK_INT64_COUNT = 128;
    public final static int CHUNK_UID_COUNT = 2688; // 128 * 21 = 2688
    public final static long UID_PERMISSION_MASK = 7L; // mask for 3 permission bits
    // LINT.ThenChange(../../../../../../../../bpf/progs/netd.h)

    @Struct.Field(order = 0, type = Struct.Type.S64Array,
            arraysize = CHUNK_INT64_COUNT)
    public final long[] val;

    public UidPermissionChunk(final long[] val) {
        this.val = val;
    }

    /**
     * Return Chunk ID for UID
     */
    public static int getChunkId(final int uid) {
        return uid / CHUNK_UID_COUNT;
    }

    /**
     * Return index for UID, which is used to access the long variable
     * in UidPermissionChunk long array.
     */
    public static int getIndex(final int uid) {
        return uid / UIDS_PER_INT64 % CHUNK_INT64_COUNT;
    }

    /**
     * Return shift for UID. A long variable can store permission bits for group of UIDs.
     * shift is used to access permission bits for the given UID.
     */
    public static int getShift(final int uid) {
        return (uid % UIDS_PER_INT64 * PERMISSION_COUNT) & 63;
    }
}