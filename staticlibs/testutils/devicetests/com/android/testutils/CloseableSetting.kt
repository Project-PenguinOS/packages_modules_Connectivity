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

package com.android.testutils.com.android.testutils

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.provider.Settings
import androidx.test.platform.app.InstrumentationRegistry
import com.android.testutils.runAsShell
import java.io.Closeable

abstract class CloseableSetting<T : Any>(val defaultRestoreValue: T) : Closeable {
    private var originalValue: T? = null

    protected abstract fun getValueInternal(): T?
    protected abstract fun setValueInternal(value: T)

    fun setValue(value: T) {
        if (originalValue == null) {
            originalValue = getValueInternal()
        }
        setValueInternal(value)
    }

    override fun close() {
        setValueInternal(originalValue ?: defaultRestoreValue)
    }
}

class CloseableGlobalSetting(val name: String) : CloseableSetting<String>(
    defaultRestoreValue = ""
) {
    private val context
        get() = InstrumentationRegistry.getInstrumentation().context

    override fun getValueInternal(): String? {
        return Settings.Global.getString(context.contentResolver, name)
    }

    override fun setValueInternal(value: String) {
        runAsShell(WRITE_SECURE_SETTINGS) {
            Settings.Global.putString(context.contentResolver, name, value)
        }
    }
}
