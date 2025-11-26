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

package com.android.net.module.util

import com.android.net.module.util.DnsPacket.ParseException

import androidx.test.filters.SmallTest
import androidx.test.runner.AndroidJUnit4

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SmallTest
/**
 * Tests for [DnsHttpsPacket].
 *
 * Build, install and run with:
 * atest NetworkStaticLibTests:com.android.net.moduletests.util.DnsHttpsPacketTest
 */
class DnsHttpsPacketTest {

    @Test
    fun constructor_whenInvalidQuestionCount_throwsParseException() {
        assertFailsWith<ParseException>("Unexpected question count: 2") {
            DnsHttpsPacket(DnsSvcbTestUtils.INVALID_QUESTION_COUNT_RESPONSE)
        }
    }

    @Test
    fun constructor_whenInvalidQueryType_throwsParseException() {
        assertFailsWith<ParseException>("Unexpected query type: 64") {
            DnsHttpsPacket(DnsSvcbTestUtils.SVCB_QUERY_TYPE_RESPONSE)
        }
    }

    @Test
    fun getAllRecords_whenNoHttpsRecords_returnsEmptyList() {
        val packet = DnsHttpsPacket(DnsSvcbTestUtils.NO_HTTPS_TYPE_RESPONSE)

        val records = packet.getAllRecords()

        assertTrue(records.isEmpty())
    }

    @Test
    fun getAllRecords_whenSingleRecord_returnsSingleRecord() {
        val packet = DnsHttpsPacket(DnsSvcbTestUtils.VALID_SINGLE_HTTPS_RECORD_RESPONSE)

        val records = packet.getAllRecords()

        assertEquals(1, records.size)
        with(records[0]) {
            assertEquals(1, priority)
            assertEquals(".", targetName)
        }
    }

    @Test
    fun getAllRecords_whenMultipleRecords_returnsMultipleRecords() {
        val packet = DnsHttpsPacket(DnsSvcbTestUtils.VALID_MULTIPLE_HTTPS_RECORDS_RESPONSE)

        val records = packet.getAllRecords()

        assertEquals(3, records.size)
        // Just check the priorities of the different records, since we've
        // duplicated the records aside from the priority field.
        assertEquals(1, records[0].priority)
        assertEquals(2, records[1].priority)
        assertEquals(3, records[2].priority)
    }
}
