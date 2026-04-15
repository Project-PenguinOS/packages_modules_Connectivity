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

#include <gtest/gtest.h>
#include <linux/netlink.h>
#include <linux/bpf.h>

#include <stdlib.h>
#include <string.h>

#include "BpfSyscallWrappers.h"
#include "bpf/BpfUtils.h"

namespace android {
namespace bpf {

class UeventSkfilterTest : public ::testing::Test {
 protected:
  unique_fd mProgram;
  const char* mProgPath = "/sys/fs/bpf/vendor/prog_filterPowerSupplyEvents_skfilter_power_supply";

  void SetUp() override {
    mProgram.reset(retrieveProgram(mProgPath));
    ASSERT_GE(mProgram.get(), 0) << "BPF program " << mProgPath << " not found or inaccessible.";
  }

  void checkFilter(int prefixBytes, const char* str, int postfixBytes, bool shouldPass) {
    int len_str = str ? strlen(str) : 0;
    int len_0str0 = str ? 1 + len_str + 1 : 0;  // with NUL byte on both sides
    int len_payload = prefixBytes + len_0str0 + postfixBytes;
    int len_total = 14 + sizeof(nlmsghdr) + len_payload;

    constexpr int SIZE = 1024;

    ASSERT_LE(len_total, SIZE) << "Generated packet size exceeds buffer capacity";

    union {
      struct {
        char pad[2];
        char raw[SIZE - 2];
      } r;
      struct {
        char pad[2];   // needed for alignment of nlmsghdr
        char eth[14];  // dst mac, src mac, ethertype
        nlmsghdr hdr;
        char data[SIZE - 2 - 14 - sizeof(nlmsghdr)];
      } s;
    } packet = {};

    _Static_assert(sizeof(packet) == SIZE);
    _Static_assert(sizeof(packet.r) == SIZE);
    _Static_assert(sizeof(packet.s) == SIZE);

    for (int i = 0; i < len_total; ++i) packet.r.raw[i] = (char)rand();

    if (str) {
      int offset = prefixBytes;
      packet.s.data[offset++] = 0;
      memcpy(packet.s.data + offset, str, len_str);
      offset += len_str;
      packet.s.data[offset++] = 0;
    }

    uint32_t retval = 0xDEADBEEF;
    ASSERT_EQ(runProgram(mProgram, &packet.s.eth, len_total, nullptr, 0, &retval), 0);

    if (shouldPass) {
      ASSERT_EQ(retval, len_total - 14)
        << "Packet should have been passed by the filter: "
        << prefixBytes << " " << str << " " << postfixBytes;
    } else {
      ASSERT_EQ(retval, 0)
        << "Packet should have been dropped by the filter: "
        << prefixBytes << " " << str << " " << postfixBytes;
    }
  }
};

// If there is no SUBSYSTEM= string at all, the filter shouldn't drop it.
TEST_F(UeventSkfilterTest, NoSubsystemNotDropped) {
  for (int i = 0; i < 300; ++i) checkFilter(i, nullptr, 0, true);
}

// No matter where SUBSYSTEM=power_supply is it should accept it
TEST_F(UeventSkfilterTest, MatchPowerSupply) {
  for (int pre = 0; pre < 300; ++pre) {
    for (int post = 0; post < 32; ++post) {
      checkFilter(pre, "SUBSYSTEM=power_supply", post, true);
    }
  }
}

// Dropping is more difficult, since not every location is within the match range
// uevent: nlmsghdr ACTION=...\0 DEVPATH=...\0 SUBSYSTEM=...\0 ...
// ACTION=\0DEVPATH= is 16 characters and is first location where it starts to match
static bool invalid(int offset) {
  if (offset < strlen("ACTION=") + 1 + strlen("DEVPATH=")) return true;
  if (sizeof(nlmsghdr) + offset >= 256) return true;
  return false;
}

TEST_F(UeventSkfilterTest, NoMatchEmptySubsystem) {
  for (int pre = 0; pre < 300; ++pre) {
    for (int post = 0; post < 32; ++post) {
      checkFilter(pre, "SUBSYSTEM=", post, invalid(pre));
    }
  }
}

TEST_F(UeventSkfilterTest, NoMatchOtherSubsystem) {
  for (int pre = 0; pre < 300; ++pre) {
    for (int post = 0; post < 32; ++post) {
      checkFilter(pre, "SUBSYSTEM=other", post, invalid(pre));
    }
  }
}

}  // namespace bpf
}  // namespace android
