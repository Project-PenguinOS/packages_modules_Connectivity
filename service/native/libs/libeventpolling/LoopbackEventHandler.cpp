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
#define LOG_TAG "LoopbackEventHandler"

#include "libeventpolling/LoopbackEventHandler.h"

#include <memory>

#include <bpf/BpfRingbuf.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>

#include "netd.h"

namespace android::net::eventpolling {

using bpf::BpfRingbuf;
using bpf::RingbufEventPoller;

// static
bpf::RingbufEventPoller<LoopbackAccessEvent> *
LoopbackEventHandler::GetPoller() {
    static bpf::RingbufEventPoller<LoopbackAccessEvent> *const sPoller =
        []() -> bpf::RingbufEventPoller<LoopbackAccessEvent> * {
        auto rb = std::make_unique<BpfRingbuf<LoopbackAccessEvent>>(
            LOOPBACK_ACCESS_RINGBUF_NETD_PATH);

        auto callback = [](const std::vector<LoopbackAccessEvent> &events) {
            for (const LoopbackAccessEvent &event : events) {
                ALOGD("LoopbackEventPoller stream got src: %d, dst: %d",
                      event.src_uid, event.dst_uid);
            }
        };

        auto result = RingbufEventPoller<LoopbackAccessEvent>::Create(
            std::move(callback), std::move(rb));

        if (!result.ok()) {
            ALOGE("Failed to create RingbufEventPoller: %s",
                  result.error().message().c_str());
            return nullptr;
        }
        return result.value().release();
    }();
    return sPoller;
}

// static
bool LoopbackEventHandler::Start() {
    bpf::RingbufEventPoller<LoopbackAccessEvent> *poller = GetPoller();
    if (!poller) {
        ALOGE("LoopbackEventHandler failed to get poller instance for Start.");
        return false;
    }
    return poller->Start();
}

// static
void LoopbackEventHandler::Stop() {
    bpf::RingbufEventPoller<LoopbackAccessEvent> *poller = GetPoller();
    if (poller) {
        poller->Stop();
    } else {
        ALOGW(
            "LoopbackEventHandler::Stop() called when poller not initialized.");
    }
}

} // namespace android::net::eventpolling
