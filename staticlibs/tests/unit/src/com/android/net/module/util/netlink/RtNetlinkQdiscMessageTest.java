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

import static com.android.net.module.util.netlink.RtNetlinkQdiscMessage.CLSACT;

import static org.junit.Assert.assertArrayEquals;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.HexDump;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class RtNetlinkQdiscMessageTest {

    @Test
    public void testCreateRtmNewQdiscMessage() {
        // Hexadecimal representation of our created packet.
        final String expectedNewQdiscHex =
                    // struct nlmsghdr
                    "30000000"            // length = 48
                    + "2400"              // type = 36 (RTM_NEWQDISC)
                    + "0506"              // flags = NLM_F_REQUEST | NLM_F_ACK |
                                          //         NLM_F_EXCL | NLM_F_CREATE
                    + "00000000"          // seqno = 0
                    + "00000000"          // pid = 0 (send to kernel)
                    // struct tcmsg
                    + "00000000"          // family = AF_UNSPEC
                    + "01000000"          // ifindex = 1
                    + "0000FFFF"          // handle
                    + "F1FFFFFF"          // parent
                    + "00000000"          // info
                    // struct nlattr: TCA_KIND
                    + "0B00"              // len = 10
                    + "0100"              // type = TCP_KIND
                    + "636C736163740000"; // str = "clsact" + padding
        final byte[] bytes =
                RtNetlinkQdiscMessage.newRtmNewQdiscMessage(
                    1 /* ifIndex */,
                    CLSACT /* qdisc */);
        assertArrayEquals(HexDump.hexStringToByteArray(expectedNewQdiscHex), bytes);
    }

    @Test
    public void testCreateRtmDelQdiscMessage() {
        // Hexadecimal representation of our created packet.
        final String expectedDelQdiscHex =
                // struct nlmsghdr
                "30000000"             // length = 48
                + "2500"               // type = 37 (RTM_DELQDISC)
                + "0500"               // flags = NLM_F_REQUEST | NLM_F_ACK
                + "00000000"           // seqno = 0
                + "00000000"           // pid = 0 (send to kernel)
                // struct tcmsg
                + "00000000"           // family = AF_UNSPEC
                + "01000000"           // ifindex = 1
                + "0000FFFF"           // handle
                + "F1FFFFFF"           // parent
                + "00000000"           // info
                // struct nlattr: TCA_KIND
                + "0B00"               // len = 10
                + "0100"               // type = TCP_KIND
                + "636C736163740000";  // str = "clsact" + padding
        final byte[] bytes =
                RtNetlinkQdiscMessage.newRtmDelQdiscMessage(
                    1 /* ifIndex */,
                    CLSACT /* qdisc */);
        assertArrayEquals(HexDump.hexStringToByteArray(expectedDelQdiscHex), bytes);
    }
}
