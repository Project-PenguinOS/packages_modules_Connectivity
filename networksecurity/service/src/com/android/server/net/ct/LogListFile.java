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

import com.google.auto.value.AutoValue;

import java.io.File;
import java.io.IOException;

/** Class to represent an installed log list file. */
@AutoValue
public abstract class LogListFile {

    /**
     * A {@link LogListfile} provider interprets the content of a log list file file and determine
     * the directory where the file should be installed, as well as the name of the installed file.
     */
    interface Provider {

        LogListFile fromBytes(byte[] content) throws IOException;

        LogListFile fromFile(File file) throws IOException;

        String getFileName();
    }

    /**
     * The directory where the log list will be installed.
     *
     * @return the directory
     */
    abstract File directory();

    /**
     * The file containing the log list.
     *
     * @return the file
     */
    abstract File file();

    /**
     * The timestamp of the log list.
     *
     * @return the timestamp
     */
    abstract long timestamp();

    @AutoValue.Builder
    abstract static class Builder {
        abstract Builder setDirectory(File directory);

        abstract Builder setFile(File file);

        abstract Builder setTimestamp(long timestamp);

        abstract LogListFile build();
    }

    static Builder builder() {
        return new AutoValue_LogListFile.Builder();
    }
}
