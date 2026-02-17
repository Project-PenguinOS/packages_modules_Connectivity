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

package com.android.net.module.util;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.net.module.util.ModuleFlagProvider.FlagProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ModuleFlagProviderTest {

    @Before
    public void setUp() {
        ModuleFlagProvider.resetForTest();
    }

    @After
    public void tearDown() {
        ModuleFlagProvider.resetForTest();
    }

    @Test
    public void testDefaultBehavior_NoProvider() {
        // When no provider is set, it should default to false
        assertThat(ModuleFlagProvider.isFeatureFlagEnabled("any_flag")).isFalse();
    }

    @Test
    public void testSetFlagProvider_Success() {
        FlagProvider mockProvider = mock(FlagProvider.class);
        when(mockProvider.isFeatureFlagEnabled("test_flag")).thenReturn(true);
        when(mockProvider.isFeatureFlagEnabled("other_flag")).thenReturn(false);

        ModuleFlagProvider.setFlagProvider(mockProvider);

        assertThat(ModuleFlagProvider.isFeatureFlagEnabled("test_flag")).isTrue();
        assertThat(ModuleFlagProvider.isFeatureFlagEnabled("other_flag")).isFalse();
        verify(mockProvider).isFeatureFlagEnabled("test_flag");
        verify(mockProvider).isFeatureFlagEnabled("other_flag");
    }

    @Test
    public void testSetFlagProvider_AlreadySet() {
        FlagProvider mockProvider1 = mock(FlagProvider.class);
        FlagProvider mockProvider2 = mock(FlagProvider.class);

        ModuleFlagProvider.setFlagProvider(mockProvider1);

        assertThrows(IllegalStateException.class, () -> {
            ModuleFlagProvider.setFlagProvider(mockProvider2);
        });
    }

    @Test
    public void testSetFlagProvider_Null() {
        assertThrows(NullPointerException.class, () -> {
            ModuleFlagProvider.setFlagProvider(null);
        });
    }
}
