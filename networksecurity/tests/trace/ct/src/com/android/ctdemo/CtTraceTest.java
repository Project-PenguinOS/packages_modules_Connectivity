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
package com.android.ctdemo;

import android.os.Trace;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

@RunWith(JUnit4.class)
public class CtTraceTest {

    @Test
    public void testCtVerification_success() throws Exception {
        for (int i = 0; i < 5; i++) {
            URL url = new URL("https://android.com");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

            Trace.beginSection("connect_android_com");
            connection.connect();
            Trace.endSection();
        }
    }

    @Test
    public void testCtVerification_fail() throws Exception {
        for (int i = 0; i < 5; i++) {
            URL url = new URL("https://no-sct.badssl.com");
            HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

            Trace.beginSection("connect_no-sct_baddssl_com");
            connection.connect();
            Trace.endSection();
        }
    }
}
