/*
 * Copyright 2008-2026 Async-IO.org
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.channels;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class DeliveryReceiptTest {

    @Test
    void constructionWithMessageId() {
        var now = Instant.now();
        var receipt = new DeliveryReceipt(
                Optional.of("msg-123"),
                DeliveryReceipt.DeliveryStatus.SENT,
                now);
        assertEquals(Optional.of("msg-123"), receipt.channelMessageId());
        assertEquals(DeliveryReceipt.DeliveryStatus.SENT, receipt.status());
        assertEquals(now, receipt.timestamp());
    }

    @Test
    void constructionWithEmptyMessageId() {
        var now = Instant.now();
        var receipt = new DeliveryReceipt(
                Optional.empty(),
                DeliveryReceipt.DeliveryStatus.FAILED,
                now);
        assertEquals(Optional.empty(), receipt.channelMessageId());
        assertEquals(DeliveryReceipt.DeliveryStatus.FAILED, receipt.status());
    }

    @Test
    void deliveredStatus() {
        var receipt = new DeliveryReceipt(
                Optional.of("m1"),
                DeliveryReceipt.DeliveryStatus.DELIVERED,
                Instant.EPOCH);
        assertEquals(DeliveryReceipt.DeliveryStatus.DELIVERED, receipt.status());
    }

    @Test
    void readStatus() {
        var receipt = new DeliveryReceipt(
                Optional.of("m1"),
                DeliveryReceipt.DeliveryStatus.READ,
                Instant.EPOCH);
        assertEquals(DeliveryReceipt.DeliveryStatus.READ, receipt.status());
    }

    @ParameterizedTest
    @EnumSource(DeliveryReceipt.DeliveryStatus.class)
    void allStatusValuesExist(DeliveryReceipt.DeliveryStatus status) {
        assertNotNull(status);
    }

    @Test
    void deliveryStatusCount() {
        assertEquals(4, DeliveryReceipt.DeliveryStatus.values().length);
    }

    @Test
    void deliveryStatusValueOf() {
        assertEquals(DeliveryReceipt.DeliveryStatus.SENT,
                DeliveryReceipt.DeliveryStatus.valueOf("SENT"));
        assertEquals(DeliveryReceipt.DeliveryStatus.DELIVERED,
                DeliveryReceipt.DeliveryStatus.valueOf("DELIVERED"));
        assertEquals(DeliveryReceipt.DeliveryStatus.READ,
                DeliveryReceipt.DeliveryStatus.valueOf("READ"));
        assertEquals(DeliveryReceipt.DeliveryStatus.FAILED,
                DeliveryReceipt.DeliveryStatus.valueOf("FAILED"));
    }

    @Test
    void equalityForSameValues() {
        var ts = Instant.parse("2025-01-01T00:00:00Z");
        var a = new DeliveryReceipt(Optional.of("m1"), DeliveryReceipt.DeliveryStatus.SENT, ts);
        var b = new DeliveryReceipt(Optional.of("m1"), DeliveryReceipt.DeliveryStatus.SENT, ts);
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
    }

    @Test
    void timestampPreserved() {
        var ts = Instant.parse("2024-06-15T12:30:00Z");
        var receipt = new DeliveryReceipt(Optional.empty(),
                DeliveryReceipt.DeliveryStatus.SENT, ts);
        assertEquals(ts, receipt.timestamp());
    }
}
