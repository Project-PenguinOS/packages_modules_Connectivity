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

#pragma once

#include <mutex>
#include <optional>

namespace android {
namespace netdutils {

// Wraps a type T and performs lazy initialization on first access via
// operator->(). Requires T to be DefaultConstructible.
// Thread-safety: initialization is thread-safe.
template <typename T> class Lazy {
    // Mutable to allow lazy initialization from const context.
    mutable std::once_flag mOnceFlag;
    mutable std::optional<T> mLazyVal;

  public:
    const T *operator->() const {
        std::call_once(mOnceFlag, [this]() { mLazyVal.emplace(); });

        // From the docs: The return from the returning call synchronizes-with
        // the returns from all passive calls on the same flag: this means that
        // all concurrent calls to std::call_once are guaranteed to observe any
        // side-effects made by the active call, with no additional
        // synchronization.
        return &mLazyVal.value();
    }

    // Refers to const version of operator->() above.
    T *operator->() {
        return const_cast<T *>(
            static_cast<const Lazy<T> &>(*this).operator->());
    }

    // Don't allow copies. Note that this also prevents generation of move
    // constructors.
    Lazy<T>() = default;
    Lazy<T>(const Lazy<T> &) = delete;
    Lazy<T> &operator=(const Lazy<T> &) = delete;
};

} // namespace netdutils
} // namespace android
