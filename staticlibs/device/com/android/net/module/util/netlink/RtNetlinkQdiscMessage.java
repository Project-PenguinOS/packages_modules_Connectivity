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

import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_ACK;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_EXCL;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_CREATE;
import static com.android.net.module.util.netlink.StructNlMsgHdr.NLM_F_REQUEST;

import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

public class RtNetlinkQdiscMessage {
    public static final String TAG = "NetlinkQdiscMessage";
    public static final String CLSACT = "clsact";

    public static final short TCA_KIND = 1;
    public static final int TC_H_INGRESS = 0xFFFFFFF1;
    public static final int TC_H_MAJ_MASK = 0xFFFF0000;
    public static final int TC_H_MIN_MASK = 0x0000FFFF;

    private static int tcHMake(int maj, int min) {
        return (maj & TC_H_MAJ_MASK) | (min & TC_H_MIN_MASK);
    }

    private static byte[] createRtmQdiscMessage(
            int ifindex, @NonNull String qdisc, boolean add) {

        final StructNlAttr kind = new StructNlAttr(TCA_KIND, qdisc);

        final int length =
                StructNlMsgHdr.STRUCT_SIZE + StructTcMsg.STRUCT_SIZE + kind.getAlignedLength();

        final byte[] bytes = new byte[length];
        final ByteBuffer byteBuffer = ByteBuffer.wrap(bytes);
        byteBuffer.order(ByteOrder.nativeOrder());

        final StructNlMsgHdr nlmsghdr = new StructNlMsgHdr();
        if (add) {
            nlmsghdr.nlmsg_type = NetlinkConstants.RTM_NEWQDISC;
            nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK | NLM_F_EXCL | NLM_F_CREATE;
        } else {
            nlmsghdr.nlmsg_type = NetlinkConstants.RTM_DELQDISC;
            nlmsghdr.nlmsg_flags = NLM_F_REQUEST | NLM_F_ACK;
        }
        nlmsghdr.nlmsg_len = length;
        nlmsghdr.pack(byteBuffer);

        final StructTcMsg tcMsg = new StructTcMsg(
                (short) AF_UNSPEC, ifindex, tcHMake(TC_H_INGRESS, 0), TC_H_INGRESS, 0);
        tcMsg.pack(byteBuffer);
        kind.pack(byteBuffer);
        return bytes;
    }

    /**
     * A convenience method to create a RTM_NEWQDISC message.
     */
    public static byte[] newRtmNewQdiscMessage(int ifindex, @NonNull String qdisc) {
        return createRtmQdiscMessage(ifindex, qdisc, true /* add */);
    }

    /**
     * A convenience method to create a RTM_DELQDISC message.
     */
    public static byte[] newRtmDelQdiscMessage(int ifindex, @NonNull String qdisc) {
        return createRtmQdiscMessage(ifindex, qdisc, false /* add */);
    }
}

