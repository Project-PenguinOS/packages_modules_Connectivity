/**
 * Copyright (c) 2025, The Android Open Source Project
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

#pragma once

#include <bpf/RingbufEventPoller.h>

#include "netd.h"

namespace android::net::eventpolling {

// LoopbackEventHandler handles events polled from the BPF loopback event ring
// buffer.
class LoopbackEventHandler {
  public:
    LoopbackEventHandler() = delete;
    LoopbackEventHandler(const LoopbackEventHandler &) = delete;
    LoopbackEventHandler &operator=(const LoopbackEventHandler &) = delete;

    // Starts the poller and event consumption. Returns whether the start was
    // successful.
    static bool Start();

    // Stops the poller and event consumption.
    static void Stop();

  private:
    static bpf::RingbufEventPoller<LoopbackAccessEvent> *GetPoller();
};

} // namespace android::net::eventpolling