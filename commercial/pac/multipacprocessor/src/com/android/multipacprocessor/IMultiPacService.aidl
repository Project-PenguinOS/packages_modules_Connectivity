/**
 * Copyright (c) 2026, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.multipacprocessor;

/**
 * This service is responsible for managing and using PacProcessors in
 * a multi-proxy environment. Unlike the legacy PacService, it supports running
 * multiple PacProcessors to allow using different PAC scripts to be used based
 * on context, such as network, user, or application UID.
 *
 * This service is part of the redesigned PAC support stack, intended to provide
 * more granular proxy configuration capabilities compared to the legacy
 * solution.
 *
 * @hide
 */
interface IMultiPacService {
}
