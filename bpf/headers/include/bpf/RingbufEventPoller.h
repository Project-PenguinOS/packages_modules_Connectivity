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

#include <android-base/result.h>
#include <android-base/thread_annotations.h>
#include <log/log.h>
#include <sys/epoll.h>
#include <sys/eventfd.h>

#include "BpfRingbuf.h"
#include "BpfUtils.h"

namespace android::bpf {

// RingbufEventPoller is an event-driven processor that continuously reads
// events from the provided eBPF ring buffer.
template <typename T> class RingbufEventPoller {
  public:
    using EventSink = std::function<void(const std::vector<T> &)>;

    ~RingbufEventPoller() { Stop(); }

    // Creates a new RingbufEventPoller. Returns an error if any arguments are
    // invalid.
    static base::Result<std::unique_ptr<RingbufEventPoller<T>>>
    Create(EventSink callback, std::unique_ptr<bpf::BpfRingbuf<T>> ringBuffer);

    // Starts event polling on a newly created worker thread.
    bool Start() EXCLUDES(mMutex);

    // Stops polling and release any held state.
    void Stop() EXCLUDES(mMutex);

  private:
    RingbufEventPoller(EventSink callback,
                       std::unique_ptr<bpf::BpfRingbuf<T>> ringBuffer)
        : mCallback(std::move(callback)), mRingBuffer(std::move(ringBuffer)) {}

    // Never grab mMutex from the event loop, as it can cause deadlock when
    // stopping.
    void RunEventLoop() EXCLUDES(mMutex);

    std::mutex mMutex;
    std::thread mWorker GUARDED_BY(mMutex);
    bool mRunning GUARDED_BY(mMutex) = false;
    // Used to break the server thread from epoll_wait
    static const int kEpollStopEventId = 1;
    android::base::unique_fd mEpollFd;
    // FD where we write / poll for stop events, to break from epoll_wait
    android::base::unique_fd mStopEventFd GUARDED_BY(mMutex);
    EventSink mCallback;
    std::unique_ptr<bpf::BpfRingbuf<T>> mRingBuffer;
};

// -----------------------------------------------------------------------------
// Implementation
// -----------------------------------------------------------------------------

template <typename T>
base::Result<std::unique_ptr<RingbufEventPoller<T>>>
RingbufEventPoller<T>::Create(EventSink callback,
                              std::unique_ptr<bpf::BpfRingbuf<T>> ringBuffer) {
    if (ringBuffer == nullptr) {
        errno = EINVAL;
        return android::base::ErrnoError() << "ringBuffer cannot be null";
    }
    if (callback == nullptr) {
        errno = EINVAL;
        return android::base::ErrnoError() << "callback cannot be null";
    }
    return std::unique_ptr<RingbufEventPoller<T>>(
        new RingbufEventPoller<T>(std::move(callback), std::move(ringBuffer)));
}

template <typename T> bool RingbufEventPoller<T>::Start() {
    ALOGD("Starting RingbufEventPoller");
    std::scoped_lock<std::mutex> lock(mMutex);
    if (mRunning) {
        ALOGW("Start() called while already running.");
        return true;
    }

    mEpollFd.reset(epoll_create1(EPOLL_CLOEXEC));
    if (mEpollFd.ok()) {
        struct epoll_event event = {};
        event.events = EPOLLIN;
        if (mRingBuffer->epoll_ctl_add(mEpollFd.get(), &event) != 0) {
            ALOGW("Failed to add ringbuf to epoll: %d", errno);
            return false;
        }
    } else {
        ALOGW("Failed to create epoll FD: %d", errno);
        return false;
    }

    mStopEventFd.reset(eventfd(0, EFD_CLOEXEC | EFD_NONBLOCK));
    if (mStopEventFd.ok()) {
        struct epoll_event event = {};
        event.events = EPOLLIN;
        event.data.u64 = kEpollStopEventId;
        if (epoll_ctl(mEpollFd.get(), EPOLL_CTL_ADD, mStopEventFd.get(),
                      &event) != 0) {
            ALOGW("Failed to add fd to epoll: %d", errno);
            return false;
        }
    } else {
        ALOGW("failed to create event FD: %d", errno);
        return false;
    }

    mRunning = true;
    mWorker = std::thread(&RingbufEventPoller<T>::RunEventLoop, this);
    return true;
}

template <typename T> void RingbufEventPoller<T>::Stop() {
    ALOGD("Stopping RingbufEventPoller");
    std::scoped_lock<std::mutex> lock(mMutex);
    if (!mRunning) return;
    mRunning = false;

    // Send the stop event. This should unblock epoll_wait and make the worker
    // joinable.
    uint64_t value = 1;
    ssize_t rc = write(mStopEventFd.get(), &value, sizeof(value));
    if (rc == -1) {
        ALOGW("write to event fd failed");
    }

    // Make sure the worker thread has stopped before resetting any of the FDs
    if (mWorker.joinable()) {
        mWorker.join();
    }
    mEpollFd.reset();
    mStopEventFd.reset();
    ALOGD("RingbufEventPoller stopped");
}

template <typename T> void RingbufEventPoller<T>::RunEventLoop() {
    // Monitoring 2 FDs (the ring buffer and stop events)
    const int MAX_EVENTS = 2;

    while (true) {
        struct epoll_event events[MAX_EVENTS];
        int num_ready = TEMP_FAILURE_RETRY(
            epoll_wait(mEpollFd.get(), events, MAX_EVENTS, -1));
        if (num_ready <= 0) {
            if (errno == EINTR) continue;
            ALOGD("epoll_wait failed: %d", errno);
            std::this_thread::sleep_for(std::chrono::milliseconds(200));
            continue;
        }

        // Check for a stop event
        for (int i = 0; i < num_ready; ++i) {
            struct epoll_event &event = events[i];
            if (event.data.u64 == kEpollStopEventId) return;
        }

        std::vector<T> rb_events;
        base::Result<int> ret = mRingBuffer->ConsumeAll(
            [&](const T &event) { rb_events.push_back(event); });
        if (!ret.ok()) {
            ALOGW("Failed to poll ringbuf: %s", ret.error().message().c_str());
        }
        mCallback(rb_events);
    }
    ALOGD("RingbufEventPoller worker thread stopping.");
}

} // namespace android::bpf
