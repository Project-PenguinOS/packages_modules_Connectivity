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
#define LOG_TAG "LocalNetEventHandler"

#include "libeventpolling/LocalNetEventHandler.h"

#include <memory>
#include <vector>

#include <bpf/BpfRingbuf.h>
#include <bpf/BpfUtils.h>
#include <log/log.h>
#include <statslog_connectivity_sdk30.h>

#include "netd.h"

namespace android::net::eventpolling {

using bpf::BpfRingbuf;
using bpf::RingbufEventPoller;

// static
LocalNetEventHandler::LocalNetEventRingbuf *LocalNetEventHandler::GetRingbuf() {
    static LocalNetEventRingbuf *const sRingbuf =
        []() -> LocalNetEventRingbuf * {
        auto rb = std::make_unique<LocalNetEventRingbuf>(
            LOCAL_NET_NOTE_OP_RINGBUF_PATH);
        return rb.release();
    }();
    return sRingbuf;
}

// static
std::vector<uint32_t> LocalNetEventHandler::ConsumeAll() {
    std::vector<uint32_t> uids_pids;
    base::Result<int> ret =
        GetRingbuf()->ConsumeAll([&](const LocalNetNoteOp &event) {
            uids_pids.push_back(event.uid);
            uids_pids.push_back(event.pid);
        });
    if (!ret.ok()) {
        ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
        return {};
    }
    return uids_pids;
}

} // namespace android::net::eventpolling