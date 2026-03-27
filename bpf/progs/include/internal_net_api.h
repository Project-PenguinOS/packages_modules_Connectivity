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

#pragma once

enum DropReasonType : uint64_t {
    DROP_REASON_NONE = 0,
    DROP_REASON_LNP = (1 << 0),
};

// Socket options for SOL_SOCKET
enum AndroidSocketOption : int32_t {
    SO_ANDROID_BASE = 0xAD01D00,
    SO_ANDROID_DROP_REASON,
};

// Socket options for SOL_TCP (aka IPPROTO_TCP)
enum AndroidTcpSocketOption : int32_t {
    TCP_ANDROID_BASE = 0xAD01D00,
    TCP_ANDROID_L4S,
};
