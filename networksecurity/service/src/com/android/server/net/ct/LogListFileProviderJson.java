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

import static java.nio.charset.StandardCharsets.UTF_8;

import android.annotation.RequiresApi;
import android.os.Build;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * A {@link LogListfile.Provider} to determine the directory where a json log list file should be
 * installed. A json log list file is installed in a logs-_version_/ directory, with file name
 * log_list.json.
 */
@RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
class LogListFileProviderJson implements LogListFile.Provider {

    static final String LOGS_DIR_PREFIX = "logs-";
    static final String LOGS_LIST_FILE_NAME = "log_list.json";

    private final File mVersionDirectory;

    LogListFileProviderJson(File versionDirectory) {
        mVersionDirectory = versionDirectory;
    }

    @Override
    public LogListFile fromBytes(byte[] content) throws IOException {
        File logsDir = null;
        long timestamp;
        try {
            JSONObject contentJson = new JSONObject(new String(content, UTF_8));
            logsDir =
                    new File(mVersionDirectory, LOGS_DIR_PREFIX + contentJson.getString("version"));
            timestamp = contentJson.getLong("log_list_timestamp");
        } catch (JSONException e) {
            throw new IOException("invalid log list format", e);
        }

        return LogListFile.builder()
                .setDirectory(logsDir)
                .setFile(new File(logsDir, LOGS_LIST_FILE_NAME))
                .setTimestamp(timestamp)
                .build();
    }

    @Override
    public LogListFile fromFile(File file) throws IOException {
        byte[] content = null;
        try (InputStream logListInputStream = new FileInputStream(file)) {
            content = logListInputStream.readAllBytes();
        }

        return fromBytes(content);
    }

    @Override
    public String getFileName() {
        return LOGS_LIST_FILE_NAME;
    }
}
