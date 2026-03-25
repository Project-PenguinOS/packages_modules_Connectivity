/*
 * Copyright (C) 2026 The Android Open Source Project
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

package com.android.server.connectivity.proxy;

import com.android.commercial.PacKey;

import java.util.function.BiConsumer;

/**
 * This class is responsible for downloading PAC scripts.
 *
 * @hide
 */
public class PacDownloader {
    /**
     * Downloads PAC script from the given url. If networkId is present, download the script on the
     * given network, otherwise use the default network of the system server. When the PAC script is
     * successfully downloaded, the callback is invoked passing the downloaded file. On error, an
     * error message is logged and the download is rescheduled.
     */
    public static void downloadPacScript(PacKey pacKey, BiConsumer<PacKey, String> callback) {
        throw new UnsupportedOperationException("not implemented");
    }
}
