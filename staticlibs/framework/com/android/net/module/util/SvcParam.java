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

import static com.android.net.module.util.DnsPacket.ParseException;
import static com.android.net.module.util.SvcParamUtils.sliceAndAdvance;

import android.annotation.NonNull;
import android.text.TextUtils;

import java.net.InetAddress;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.StringJoiner;

/**
 * The base class for all SvcParam.
 *
 * @hide
 */
public abstract class SvcParam<T> {
    private final int mKey;

    SvcParam(int key) {
        mKey = key;
    }

    int getKey() {
        return mKey;
    }

    abstract T getValue();

    static SvcParam parseSvcParam(@NonNull ByteBuffer buf) throws ParseException {
        try {
            final int key = Short.toUnsignedInt(buf.getShort());
            switch (key) {
                case KEY_MANDATORY:
                    return new SvcParamMandatory(buf);
                case KEY_ALPN:
                    return new SvcParamAlpn(buf);
                case KEY_NO_DEFAULT_ALPN:
                    return new SvcParamNoDefaultAlpn(buf);
                case KEY_PORT:
                    return new SvcParamPort(buf);
                case KEY_IPV4HINT:
                    return new SvcParamIpv4Hint(buf);
                case KEY_ECH:
                    return new SvcParamEch(buf);
                case KEY_IPV6HINT:
                    return new SvcParamIpv6Hint(buf);
                case KEY_DOHPATH:
                    return new SvcParamDohPath(buf);
                default:
                    return new SvcParamGeneric(key, buf);
            }
        } catch (BufferUnderflowException e) {
            throw new ParseException("Malformed packet", e);
        }
    }

    static class SvcParamMandatory extends SvcParam<short[]> {
        private final short[] mValue;

        private SvcParamMandatory(@NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(KEY_MANDATORY);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toShortArray(svcParamValue);
            if (mValue.length == 0) {
                throw new ParseException("mandatory value must be non-empty");
            }
        }

        @Override
        short[] getValue() {
            /* Not yet implemented */
            return null;
        }

        @Override
        public String toString() {
            final StringJoiner valueJoiner = new StringJoiner(",");
            for (short key : mValue) {
                valueJoiner.add(toKeyName(key));
            }
            return toKeyName(getKey()) + "=" + valueJoiner.toString();
        }
    }

    static class SvcParamAlpn extends SvcParam<List<String>> {
        private final List<String> mValue;

        SvcParamAlpn(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_ALPN);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toStringList(svcParamValue);
            if (mValue.isEmpty()) {
                throw new ParseException("alpn value must be non-empty");
            }
        }

        @Override
        List<String> getValue() {
            return Collections.unmodifiableList(mValue);
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + TextUtils.join(",", mValue);
        }
    }

    static class SvcParamNoDefaultAlpn extends SvcParam<Void> {
        SvcParamNoDefaultAlpn(@NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(KEY_NO_DEFAULT_ALPN);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = buf.getShort();
            if (len != 0) {
                throw new ParseException("no-default-alpn value must be empty");
            }
        }

        @Override
        Void getValue() {
            return null;
        }

        @Override
        public String toString() {
            return toKeyName(getKey());
        }
    }

    static class SvcParamPort extends SvcParam<Integer> {
        private final int mValue;

        SvcParamPort(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_PORT);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = buf.getShort();
            if (len != Short.BYTES) {
                throw new ParseException("key port len is not 2 but " + len);
            }
            mValue = Short.toUnsignedInt(buf.getShort());
        }

        @Override
        Integer getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + mValue;
        }
    }

    static class SvcParamIpHint extends SvcParam<List<InetAddress>> {
        private final List<InetAddress> mValue;

        private SvcParamIpHint(int key, @NonNull ByteBuffer buf, int addrLen)
                throws BufferUnderflowException, ParseException {
            super(key);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final ByteBuffer svcParamValue = sliceAndAdvance(buf, len);
            mValue = SvcParamUtils.toInetAddressList(svcParamValue, addrLen);
            if (mValue.isEmpty()) {
                throw new ParseException(toKeyName(getKey()) + " value must be non-empty");
            }
        }

        @Override
        List<InetAddress> getValue() {
            return Collections.unmodifiableList(mValue);
        }

        @Override
        public String toString() {
            final StringJoiner valueJoiner = new StringJoiner(",");
            for (InetAddress ip : mValue) {
                valueJoiner.add(ip.getHostAddress());
            }
            return toKeyName(getKey()) + "=" + valueJoiner.toString();
        }
    }

    static class SvcParamIpv4Hint extends SvcParamIpHint {
        SvcParamIpv4Hint(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_IPV4HINT, buf, NetworkStackConstants.IPV4_ADDR_LEN);
        }
    }

    static class SvcParamIpv6Hint extends SvcParamIpHint {
        SvcParamIpv6Hint(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_IPV6HINT, buf, NetworkStackConstants.IPV6_ADDR_LEN);
        }
    }

    static class SvcParamEch extends SvcParamGeneric {
        SvcParamEch(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_ECH, buf);
        }
    }

    static class SvcParamDohPath extends SvcParam<String> {
        private final String mValue;

        SvcParamDohPath(@NonNull ByteBuffer buf) throws BufferUnderflowException, ParseException {
            super(KEY_DOHPATH);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            final byte[] value = new byte[len];
            buf.get(value);
            mValue = new String(value, StandardCharsets.UTF_8);
        }

        @Override
        String getValue() {
            return mValue;
        }

        @Override
        public String toString() {
            return toKeyName(getKey()) + "=" + mValue;
        }
    }

    // For other unrecognized and unimplemented SvcParams, they are stored as SvcParamGeneric.
    static class SvcParamGeneric extends SvcParam<byte[]> {
        private final byte[] mValue;

        SvcParamGeneric(int key, @NonNull ByteBuffer buf)
                throws BufferUnderflowException, ParseException {
            super(key);
            // The caller already read 2 bytes for SvcParamKey.
            final int len = Short.toUnsignedInt(buf.getShort());
            mValue = new byte[len];
            buf.get(mValue);
        }

        @Override
        byte[] getValue() {
            /* Not yet implemented */
            return null;
        }

        @Override
        public String toString() {
            final StringBuilder out = new StringBuilder();
            out.append(toKeyName(getKey()));
            if (mValue != null && mValue.length > 0) {
                out.append("=");
                out.append(HexDump.toHexString(mValue));
            }
            return out.toString();
        }
    }

    private static String toKeyName(int key) {
        switch (key) {
            case KEY_MANDATORY:
                return "mandatory";
            case KEY_ALPN:
                return "alpn";
            case KEY_NO_DEFAULT_ALPN:
                return "no-default-alpn";
            case KEY_PORT:
                return "port";
            case KEY_IPV4HINT:
                return "ipv4hint";
            case KEY_ECH:
                return "ech";
            case KEY_IPV6HINT:
                return "ipv6hint";
            case KEY_DOHPATH:
                return "dohpath";
            default:
                return "key" + key;
        }
    }

    /**
     * The following SvcParamKeys KEY_* are defined in
     * https://www.iana.org/assignments/dns-svcb/dns-svcb.xhtml.
     */

    // The SvcParamKey "mandatory". The associated implementation of SvcParam is SvcParamMandatory.
    static final int KEY_MANDATORY = 0;

    // The SvcParamKey "alpn". The associated implementation of SvcParam is SvcParamAlpn.
    static final int KEY_ALPN = 1;

    // The SvcParamKey "no-default-alpn". The associated implementation of SvcParam is
    // SvcParamNoDefaultAlpn.
    static final int KEY_NO_DEFAULT_ALPN = 2;

    // The SvcParamKey "port". The associated implementation of SvcParam is SvcParamPort.
    static final int KEY_PORT = 3;

    // The SvcParamKey "ipv4hint". The associated implementation of SvcParam is SvcParamIpv4Hint.
    static final int KEY_IPV4HINT = 4;

    // The SvcParamKey "ech". The associated implementation of SvcParam is SvcParamEch.
    static final int KEY_ECH = 5;

    // The SvcParamKey "ipv6hint". The associated implementation of SvcParam is SvcParamIpv6Hint.
    static final int KEY_IPV6HINT = 6;

    // The SvcParamKey "dohpath". The associated implementation of SvcParam is SvcParamDohPath.
    static final int KEY_DOHPATH = 7;

    // The minimal size of a SvcParam.
    // https://www.ietf.org/archive/id/draft-ietf-dnsop-svcb-https-12.html#name-rdata-wire-format
    static final int MINSVCPARAMSIZE = 4;
}
