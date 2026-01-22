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

import android.annotation.RequiresApi;
import android.os.Build;

import com.android.server.net.ct.internal.fbs.LogList;

import java.io.File;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;

/** A provider of log list backed by flatbuffers. */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class LogListFileProviderFlatbuffers implements LogListFile.Provider {

    static final String LOGS_DIR_PREFIX = "logs-";
    static final String LOGS_LIST_FILE_NAME = "log_list.ctfb";

    private final File mVersionDirectory;

    LogListFileProviderFlatbuffers(File versionDirectory) {
        mVersionDirectory = versionDirectory;
    }

    @Override
    public LogListFile fromBytes(byte[] content) throws IOException {
        LogList logList;
        try {
            logList = LogList.getRootAsLogList(ByteBuffer.wrap(content));
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("invalid log list format", e);
        }
        return toLogListFile(logList);
    }

    @Override
    public LogListFile fromFile(File file) throws IOException {
        LogList logList;

        try (FileChannel channel = FileChannel.open(file.toPath())) {
            MappedByteBuffer map = channel.map(FileChannel.MapMode.READ_ONLY, 0, channel.size());
            logList = LogList.getRootAsLogList(map);
        } catch (IndexOutOfBoundsException e) {
            throw new IOException("invalid log list format", e);
        }
        return toLogListFile(logList);
    }

    private LogListFile toLogListFile(LogList logList) throws IOException {
        if (logList == null) {
            throw new IOException("invalid log list format");
        }

        String version = String.format("%d.%d", logList.versionMajor(), logList.versionMinor());
        File logsDir = new File(mVersionDirectory, LOGS_DIR_PREFIX + version);
        return LogListFile.builder()
                .setDirectory(logsDir)
                .setFile(new File(logsDir, LOGS_LIST_FILE_NAME))
                .setTimestamp(logList.timestamp())
                .build();
    }

    @Override
    public String getFileName() {
        return LOGS_LIST_FILE_NAME;
    }
}
