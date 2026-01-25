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

#include "libeventpolling/LoopbackEventHandler.h"

#include <gtest/gtest.h>

#include "bpf/KernelUtils.h"
#include "netd.h"

namespace android::net::eventpolling {

TEST(LoopbackEventHandlerTest, StartStop) {
    if (!android::bpf::isAtLeastKernelVersion(5, 10)) {
        GTEST_SKIP() << "BPF ring buffers not supported below 5.10";
    }

    if (access(LOOPBACK_ACCESS_RINGBUF_NETD_PATH, R_OK)) {
        GTEST_SKIP() << "BPF ring buffer not found at "
                     << LOOPBACK_ACCESS_RINGBUF_NETD_PATH;
    }
    LoopbackEventHandler::Stop();
    ASSERT_TRUE(LoopbackEventHandler::Start());
    ASSERT_TRUE(LoopbackEventHandler::Start());
    LoopbackEventHandler::Stop();

    // Make sure we can start after stopping.
    ASSERT_TRUE(LoopbackEventHandler::Start());
    LoopbackEventHandler::Stop();
}

} // namespace android::net::eventpolling