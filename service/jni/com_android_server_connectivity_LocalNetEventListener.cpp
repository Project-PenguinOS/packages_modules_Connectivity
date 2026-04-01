
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
#define LOG_TAG "LocalNetEventListenerNative"

#include <android-base/unique_fd.h>
#include <android/file_descriptor_jni.h>
#include <jni.h>
#include <nativehelper/JNIPlatformHelp.h>
#include <nativehelper/jni_macros.h>
#include <nativehelper/scoped_local_ref.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "libeventpolling/LocalNetEventHandler.h"

namespace android {

using android::net::eventpolling::LocalNetEventHandler;

static jobject nativeGetLocalNetAccessRingbufFd(JNIEnv *env, jclass clazz) {
    android::base::unique_fd fd = LocalNetEventHandler::GetNewRingbufFd();
    if (!fd.ok()) {
        ALOGE("Failed to get local net event ring buffer.");
        return nullptr;
    }
    return jniCreateFileDescriptor(env, fd.release());
}

static jintArray nativeConsumeAllLocalNetAccessEvents(JNIEnv *env,
                                                      jclass clazz) {
    std::vector<uint32_t> uids_pids = LocalNetEventHandler::ConsumeAll();
    if (uids_pids.empty()) {
        return env->NewIntArray(0);
    }

    ScopedLocalRef<jintArray> result(env, env->NewIntArray(uids_pids.size()));
    if (!result.get()) {
        ALOGE("Failed to allocate jintArray");
        return nullptr;
    }
    env->SetIntArrayRegion(result.get(), 0, uids_pids.size(),
                           reinterpret_cast<const jint *>(uids_pids.data()));
    return result.release();
}

static const JNINativeMethod gMethods[] = {
    MAKE_JNI_NATIVE_METHOD("nativeGetLocalNetAccessRingbufFd",
                           "()Ljava/io/FileDescriptor;",
                           nativeGetLocalNetAccessRingbufFd),
    MAKE_JNI_NATIVE_METHOD_AUTOSIG("nativeConsumeAllLocalNetAccessEvents",
                                   nativeConsumeAllLocalNetAccessEvents),
};

int register_com_android_server_connectivity_LocalNetEventListener(
    JNIEnv *env) {
    return jniRegisterNativeMethods(env,
                                    "android/net/connectivity/com/android/"
                                    "server/connectivity/LocalNetEventListener",
                                    gMethods, NELEM(gMethods));
}
} // namespace android
