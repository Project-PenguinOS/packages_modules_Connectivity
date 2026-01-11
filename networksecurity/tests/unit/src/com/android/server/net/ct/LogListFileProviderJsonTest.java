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
package com.android.server.net.ct;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;

/** Tests for the {@link LogListFileProviderJson}. */
@RunWith(JUnit4.class)
public class LogListFileProviderJsonTest {

    private final File mTestDir =
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir();

    private final LogListFileProviderJson mLogListFileProvider =
            new LogListFileProviderJson(mTestDir);

    @Test
    public void testReadLogList_fromMemory() throws Exception {
        String version = "123.456";
        long timestamp = 666L;

        LogListFile logList =
                mLogListFileProvider.fromBytes(
                        makeLogListJson(version, timestamp).toString().getBytes());

        assertThat(logList.directory().getName()).isEqualTo("logs-123.456");
        assertThat(logList.file().getName()).isEqualTo("log_list.json");
        assertThat(logList.timestamp()).isEqualTo(666L);
    }

    @Test
    public void testReadLogList_fromFile() throws Exception {
        String version = "654.321";
        long timestamp = 777L;
        File logFile = File.createTempFile("log-list", "json", mTestDir);
        try (FileOutputStream outputStream = new FileOutputStream(logFile)) {
            outputStream.write(makeLogListJson(version, timestamp).toString().getBytes());
        }

        LogListFile logList = mLogListFileProvider.fromFile(logFile);

        assertThat(logList.directory().getName()).isEqualTo("logs-654.321");
        assertThat(logList.file().getName()).isEqualTo("log_list.json");
        assertThat(logList.timestamp()).isEqualTo(timestamp);
    }

    private JSONObject makeLogListJson(String version, long timestamp) throws JSONException {
        return new JSONObject().put("version", version).put("log_list_timestamp", timestamp);
    }
}
