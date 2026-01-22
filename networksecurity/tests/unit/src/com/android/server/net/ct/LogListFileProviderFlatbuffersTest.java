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

import com.android.server.net.ct.internal.fbs.LogList;

import com.google.flatbuffers.FlatBufferBuilder;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Tests for the {@link LogListFileProviderFlatbuffers}. */
@RunWith(JUnit4.class)
public class LogListFileProviderFlatbuffersTest {

    private final File mTestDir =
            InstrumentationRegistry.getInstrumentation().getContext().getFilesDir();

    private final LogListFileProviderFlatbuffers mLogListFileProviderFlatbuffers =
            new LogListFileProviderFlatbuffers(mTestDir);

    @Test
    public void testReadLogList_fromMemory() throws IOException {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int offset = LogList.createLogList(builder, 1, 2, 3, 0);
        builder.finish(offset);

        LogListFile logList =
                mLogListFileProviderFlatbuffers.fromBytes(builder.sizedByteArray());

        assertThat(logList.directory().getName()).isEqualTo("logs-1.2");
        assertThat(logList.file().getName()).isEqualTo("log_list.ctfb");
        assertThat(logList.timestamp()).isEqualTo(3);
    }

    @Test
    public void testReadLogList_fromFile() throws IOException {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int offset = LogList.createLogList(builder, 4, 5, 6, 0);
        builder.finish(offset);

        File logFile = File.createTempFile("log-list", "ctfb", mTestDir);
        try (FileOutputStream outputStream = new FileOutputStream(logFile)) {
            outputStream.write(builder.sizedByteArray());
        }

        LogListFile logList = mLogListFileProviderFlatbuffers.fromFile(logFile);

        assertThat(logList.directory().getName()).isEqualTo("logs-4.5");
        assertThat(logList.file().getName()).isEqualTo("log_list.ctfb");
        assertThat(logList.timestamp()).isEqualTo(6L);
    }
}
