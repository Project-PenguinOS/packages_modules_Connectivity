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

#define LOG_TAG "BpfEventPollerNative"

#include <jni.h>
#include <nativehelper/JNIHelp.h>
#include <nativehelper/jni_macros.h>
#include <utils/misc.h>

#include "bpf/BpfUtils.h"
#include "libeventpolling/LoopbackEventHandler.h"

using android::net::eventpolling::LoopbackEventHandler;

namespace android {

static void nativeInitLoopbackEventConsumer(JNIEnv *env, jclass clazz) {
    LoopbackEventHandler::Start();
}

static const JNINativeMethod gMethods[] = {
    MAKE_JNI_NATIVE_METHOD_AUTOSIG("nativeInitLoopbackEventConsumer",
                                   nativeInitLoopbackEventConsumer),
};

int register_com_android_server_connectivity_BpfEventPoller(JNIEnv *env) {
    return jniRegisterNativeMethods(env,
                                    "android/net/connectivity/com/android/"
                                    "server/connectivity/BpfEventPoller",
                                    gMethods, NELEM(gMethods));
}

} // namespace android
