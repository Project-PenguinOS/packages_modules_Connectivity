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

package com.android.net.module.util;

import static android.net.DnsResolver.CLASS_IN;

import static com.android.internal.annotations.VisibleForTesting.Visibility.PACKAGE;
import static com.android.net.module.util.DnsHttpsPacket.TYPE_HTTPS;
import static com.android.net.module.util.SvcParam.KEY_ALPN;
import static com.android.net.module.util.SvcParam.KEY_DOHPATH;
import static com.android.net.module.util.SvcParam.KEY_ECH;
import static com.android.net.module.util.SvcParam.KEY_IPV4HINT;
import static com.android.net.module.util.SvcParam.KEY_IPV6HINT;
import static com.android.net.module.util.SvcParam.KEY_MANDATORY;
import static com.android.net.module.util.SvcParam.KEY_NO_DEFAULT_ALPN;
import static com.android.net.module.util.SvcParam.KEY_PORT;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.DnsResolver;
import android.util.SparseArray;

import com.android.internal.annotations.VisibleForTesting;

import com.android.net.module.util.DnsPacket.DnsRecord;
import com.android.net.module.util.DnsPacket.ParseException;
import com.android.net.module.util.DnsPacketUtils;

import com.android.net.module.util.SvcParam.SvcParamAlpn;
import com.android.net.module.util.SvcParam.SvcParamDohPath;
import com.android.net.module.util.SvcParam.SvcParamEch;
import com.android.net.module.util.SvcParam.SvcParamIpv4Hint;
import com.android.net.module.util.SvcParam.SvcParamIpv6Hint;
import com.android.net.module.util.SvcParam.SvcParamMandatory;
import com.android.net.module.util.SvcParam.SvcParamNoDefaultAlpn;
import com.android.net.module.util.SvcParam.SvcParamPort;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.BufferUnderflowException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Class to represent the response from a HTTPS DNS query in the Answer section.
 *
 * <p>A HTTPS DNS response will be roughly structured as follows:
 * <ul>
 *   <li>Name (variable length): if name compression is used, expected value of 0xc0 0x0c.
 *   <li>Record Type (2 octets): expected value of 0x00 0x41, to correspond to TYPE_HTTPS
 *   <li>Class (2 octets): expected value of 0x00 0x01, to correspond to CLASS_IN
 *   <li>TTL (4 octets)
 *   <li>Data length (2 octets)
 *   <li>Priority (2 octets): special case if priority is 0 (AliasMode).
 *   <li>Target name (variable length): special handling if zero-length.
 *   <li>Repeated SvcParam (variable length) in strict increasing numeric order, may be empty.
 * </ul>
 *
 * <p>See RFC 9460 for more details on the expected structure and contents.
 *
 * @hide
 */
public class DnsHttpsRecord extends DnsRecord {
    private final int mSvcPriority;
    @NonNull private final String mTargetName;
    @NonNull private final SparseArray<SvcParam> mSvcParams = new SparseArray<>();

    // TODO(b/454544870): move this to SvcParamUtils so it can be reused by DnsSvcbRecord
    @VisibleForTesting(visibility = PACKAGE)
    public static final String ZERO_LENGTH_TARGET_NAME = ".";
    @VisibleForTesting(visibility = PACKAGE)
    public static final int DEFAULT_PORT_VALUE = -1;

    public DnsHttpsRecord(@DnsPacket.RecordType int rType, @NonNull ByteBuffer buff)
            throws IllegalStateException, ParseException, BufferUnderflowException {
        super(rType, buff);

        if (nsType != TYPE_HTTPS) {
            throw new IllegalStateException("incorrect nsType: " + nsType);
        }

        if (nsClass != CLASS_IN) {
            throw new ParseException("incorrect nsClass: " + nsClass);
        }

        final byte[] rdata = getRR();
        if (rdata == null || rdata.length == 0) {
            throw new ParseException("HTTPS rdata is empty");
        }

        ByteBuffer buf = ByteBuffer.wrap(rdata).asReadOnlyBuffer();
        mSvcPriority = Short.toUnsignedInt(buf.getShort());

        // Mark the buffer so that we can reset it later.
        buf.mark();
        // If the target name is `"." (zero-length)`, RFC 9460 2.5 specifies special rules apply.
        if (buf.get() == 0) {
            mTargetName = ZERO_LENGTH_TARGET_NAME;
        } else {
            buf.reset(); // Reset the buffer to the start of the target name.
            mTargetName = DnsPacketUtils.DnsRecordParser.parseName(buf, /* depth= */ 0,
                /* isNameCompressionSupported= */ true);
        }

        if (mTargetName.length() > DnsPacket.DnsRecord.MAXNAMESIZE) {
            throw new ParseException(
                    "Failed to parse HTTPS target name, name size is too long: "
                            + mTargetName.length());
        }

        // Start here as 0 is a valid SvcParamKey value
        int previousKey = -1;

        while(buf.hasRemaining()) {
            final SvcParam svcParam = SvcParam.parseSvcParam(buf);
            int key = svcParam.getKey();
            if (mSvcParams.contains(key)) {
                throw new ParseException("Invalid DnsHttpsRecord: key " + key + " is repeated");
            }

            if (key <= previousKey) {
                throw new ParseException(
                    "Invalid DnsHttpsRecord: keys must be in increasing order");
            }

            mSvcParams.put(key, svcParam);
            previousKey = key;
        }
    }

    /**
     * Returns the priority of the HTTPS record.
     *
     * <p>See RFC 9460 2.4 for more details.
     */
    public int getPriority() {
        return mSvcPriority;
    }

    /**
     * Returns the target name of the HTTPS record.
     *
     * <p>If the target name is `.`, RFC 9460 2.5 specifies that this indicates special rules.
     * For AliasMode RRs, this indicates that the service is not available or does not exist.
     * For ServiceMode RRs, this indicates the record's owner name is the effective target name.
     */
    public @NonNull String getTargetName() {
        return mTargetName;
    }

    /**
     * Returns the list of mandatory SvcParamKeys contained in the HTTPS record.
     *
     * <p>See RFC 9460 8 for more details.
     */
    public @NonNull List<String> getMandatory() {
        // TODO(b/454544870): implement this after fixing the return type of SvcParamMandatory
        return Collections.emptyList();
    }

    /**
     * Returns the list of ALPN protocols contained in the HTTPS record.
     *
     * <p>See RFC 9460 7.1 for more details.
     */
    public @NonNull List<String> getAlpn() {
        final SvcParam svcParamAlpn = mSvcParams.get(KEY_ALPN);
        if (svcParamAlpn == null || !(svcParamAlpn instanceof SvcParamAlpn)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(((SvcParamAlpn) svcParamAlpn).getValue());
    }

    /**
     * Returns whether the HTTPS record has the `no-default-alpn` SvcParam.
     *
     * <p>See RFC 9460 7.1 for more details.
     */
    public boolean isNoDefaultAlpn() {
        final SvcParam svcParamNoDefaultAlpn = mSvcParams.get(KEY_NO_DEFAULT_ALPN);
        if (svcParamNoDefaultAlpn == null ||
                !(svcParamNoDefaultAlpn instanceof SvcParamNoDefaultAlpn)) {
            return false;
        }
        return true;
    }

    /**
     * Returns the port of the HTTPS record.
     *
     * <p>See RFC 9460 7.2 for more details.
     */
    public int getPort() {
        final SvcParam svcParamPort = mSvcParams.get(KEY_PORT);
        if (svcParamPort == null || !(svcParamPort instanceof SvcParamPort)) {
            return DEFAULT_PORT_VALUE;
        }
        return ((SvcParamPort) svcParamPort).getValue();
    }

    /**
     * Returns the list of IPv4 hints contained in the HTTPS record.
     *
     * <p>See RFC 9460 7.3 for more details.
     */
    public @NonNull List<InetAddress> getIpv4Hint() {
        final SvcParam svcParamIpv4Hint = mSvcParams.get(KEY_IPV4HINT);
        if (svcParamIpv4Hint == null || !(svcParamIpv4Hint instanceof SvcParamIpv4Hint)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(((SvcParamIpv4Hint) svcParamIpv4Hint).getValue());
    }

    /**
     * Returns the ECH config list contained in the HTTPS record, if present.
     *
     * <p>Due to constraints with being unable to read the Conscrypt ECH flag from the staticlibs/
     * directory, this method returns a raw byte array, which will be used to construct an
     * EchConfigList object in Connectivity/framework.
     */
    @Nullable
    public byte[] getEchConfigList() {
        // TODO(b/419020719): return an EchConfigList object directly once flags are fully rolled
        // out and cleaned up
        final SvcParamEch sp = (SvcParamEch) mSvcParams.get(KEY_ECH);
        return (sp != null) ? sp.getValue() : null;
    }

    /**
     * Returns the list of IPv6 hints contained in the HTTPS record.
     *
     * <p>See RFC 9460 7.3 for more details.
     */
    public @NonNull List<InetAddress> getIpv6Hint() {
        final SvcParam svcParamIpv6Hint = mSvcParams.get(KEY_IPV6HINT);
        if (svcParamIpv6Hint == null || !(svcParamIpv6Hint instanceof SvcParamIpv6Hint)) {
            return Collections.emptyList();
        }
        return Collections.unmodifiableList(((SvcParamIpv6Hint) svcParamIpv6Hint).getValue());
    }

    /**
     * Returns the DoH path contained in the HTTPS record, if present.
     *
     * <p>See RFC 9461 5 for more details.
     */
    public @Nullable String getDohPath() {
        final SvcParam svcParamDohPath = mSvcParams.get(KEY_DOHPATH);
        if (svcParamDohPath == null || !(svcParamDohPath instanceof SvcParamDohPath)) {
            return null;
        }
        return ((SvcParamDohPath) svcParamDohPath).getValue();
    }
}
