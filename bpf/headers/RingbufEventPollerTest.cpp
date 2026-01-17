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

#include "bpf/RingbufEventPoller.h"

#include <android-base/macros.h>
#include <android-base/result-gmock.h>
#include <fcntl.h>
#include <gmock/gmock.h>
#include <gtest/gtest.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <mutex>
#include <thread>
#include <vector>

#include "BpfSyscallWrappers.h"
#include "bpf/BpfRingbuf.h"

#define TEST_RINGBUF_MAGIC_NUM 12345

namespace android::bpf {

using ::android::base::testing::HasError;
using ::android::base::testing::WithCode;
using bpf::BpfRingbuf;
using ::testing::ElementsAre;
using ::testing::IsEmpty;

class RingbufEventPollerTest : public ::testing::Test {
  protected:
    RingbufEventPollerTest()
        : mProgPath("/sys/fs/bpf/prog_bpfRingbufProg_skfilter_ringbuf_test"),
          mRingbufPath("/sys/fs/bpf/map_bpfRingbufProg_test_ringbuf") {}

    void SetUp() override {
        if (!android::bpf::isAtLeastKernelVersion(5, 10)) {
            GTEST_SKIP() << "BPF ring buffers not supported below 5.10";
        }

        errno = 0;
        mProgram.reset(bpf::retrieveProgram(mProgPath.c_str()));
        EXPECT_EQ(errno, 0);
        ASSERT_GE(mProgram.get(), 0)
            << mProgPath << " was either not found or inaccessible.";

        mReceivedEvents.clear();

        auto result = BpfRingbuf<uint64_t>::Create(mRingbufPath.c_str());
        ASSERT_RESULT_OK(result);
        mRingbuf = std::move(result.value());
    }

    void RunProgram() {
        char fake_skb[128] = {};
        EXPECT_EQ(bpf::runProgram(mProgram, fake_skb, sizeof(fake_skb)), 0);
    }

    std::string mProgPath;
    std::string mRingbufPath;
    android::base::unique_fd mProgram;
    std::unique_ptr<BpfRingbuf<uint64_t>> mRingbuf;
    std::vector<uint64_t> mReceivedEvents;
    std::mutex mMutex;
    std::condition_variable mCv;
};

TEST_F(RingbufEventPollerTest, NullRingBuffer) {
    auto callback = [](const std::vector<uint64_t> &evts __unused) {};
    EXPECT_THAT(
        RingbufEventPoller<uint64_t>::Create(std::move(callback), nullptr),
        HasError(WithCode(EINVAL)));
}

TEST_F(RingbufEventPollerTest, NullCallback) {
    EXPECT_THAT(
        RingbufEventPoller<uint64_t>::Create(nullptr, std::move(mRingbuf)),
        HasError(WithCode(EINVAL)));
}

TEST_F(RingbufEventPollerTest, StartStopCycle) {
    auto callback = [](const std::vector<uint64_t> &evts __unused) {};
    auto result = RingbufEventPoller<uint64_t>::Create(std::move(callback),
                                                       std::move(mRingbuf));
    ASSERT_RESULT_OK(result);
    std::unique_ptr<RingbufEventPoller<uint64_t>> poller =
        std::move(result.value());

    EXPECT_TRUE(poller->Start());
    // Calling Start() twice should be fine
    EXPECT_TRUE(poller->Start());
    poller->Stop();
    // Calling Stop() twice should be fine
    poller->Stop();
}

TEST_F(RingbufEventPollerTest, StopBeforeStart) {
    auto callback = [](const std::vector<uint64_t> &evts __unused) {};
    auto result = RingbufEventPoller<uint64_t>::Create(std::move(callback),
                                                       std::move(mRingbuf));
    ASSERT_RESULT_OK(result);

    std::unique_ptr<RingbufEventPoller<uint64_t>> poller =
        std::move(result.value());
    poller->Stop();
    EXPECT_TRUE(poller->Start());
}

TEST_F(RingbufEventPollerTest, ConsumeEvents) {
    auto callback = [this](const std::vector<uint64_t> &evts) {
        std::lock_guard<std::mutex> lock(mMutex);
        if (!evts.empty()) {
            mReceivedEvents.insert(mReceivedEvents.end(), evts.begin(),
                                   evts.end());
            mCv.notify_one();
        }
    };
    // Keep a raw pointer to the ringbuf to verify internal state
    BpfRingbuf<uint64_t> *ringbufPtr = mRingbuf.get();

    auto result = RingbufEventPoller<uint64_t>::Create(std::move(callback),
                                                       std::move(mRingbuf));
    ASSERT_RESULT_OK(result);
    EXPECT_TRUE(result.value()->Start());
    EXPECT_TRUE(ringbufPtr->isEmpty());
    EXPECT_TRUE(mReceivedEvents.empty());

    // Trigger 3 events added to the ringbuf
    int numRuns = 3;
    for (int i = 0; i < numRuns; i++) {
        RunProgram();
    }

    {
        std::unique_lock<std::mutex> lock(mMutex);
        EXPECT_TRUE(mCv.wait_for(
            lock, std::chrono::seconds(5),
            [&numRuns, this] { return mReceivedEvents.size() == numRuns; }))
            << "Callback not triggered within timeout, num mReceivedEvents is "
            << mReceivedEvents.size();
        EXPECT_THAT(mReceivedEvents,
                    ElementsAre(TEST_RINGBUF_MAGIC_NUM, TEST_RINGBUF_MAGIC_NUM,
                                TEST_RINGBUF_MAGIC_NUM));
    }
    // Ringbuffer events should have been consumed
    EXPECT_TRUE(ringbufPtr->isEmpty());
}

} // namespace android::bpf
