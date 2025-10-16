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

import static android.system.OsConstants.AF_UNSPEC;

import static org.junit.Assert.assertArrayEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class StructTcMsgTest {
    @Test
    public void testQdiscTcMsg() {
        final String expectHex =
                "00000000"      // family = AF_UNSPEC
                + "01000000"    // ifindex
                + "0000FFFF"    // handle
                + "F1FFFFFF"    // parent
                + "00000000";   // info

        final short family = (short) AF_UNSPEC;
        final int ifindex = 1;
        final int handle = 0xffff0000;
        final int parent = 0xfffffff1;
        final int info = 0;
        final StructTcMsg tcMsg = new StructTcMsg(family, ifindex, handle, parent, info);
        final ByteBuffer buffer = ByteBuffer.allocate(StructTcMsg.STRUCT_SIZE);
        buffer.order(ByteOrder.LITTLE_ENDIAN);
        tcMsg.pack(buffer);
        assertArrayEquals(HexDump.hexStringToByteArray(expectHex), buffer.array());
    }
}
