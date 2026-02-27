/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.net.cts;

import com.android.modules.utils.build.SdkLevel;

/**
 * Utils class to provide common shared test helper methods or constants that behave differently
 * depending on the SDK against which they are compiled.
 */
public class TestUtils {
    /**
     * Whether to test T+ APIs. This requires a) that the test be running on an T+ device, and
     * b) that the code be compiled against shims new enough to access these APIs.
     */
    public static boolean shouldTestTApis() {
        return SdkLevel.isAtLeastT();
    }

    /**
     * Whether to test U+ APIs. This requires a) that the test be running on an U+ device, and
     * b) that the code be compiled against shims new enough to access these APIs.
     */
    public static boolean shouldTestUApis() {
        return SdkLevel.isAtLeastU();
    }
}
