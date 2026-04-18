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
package org.atmosphere.ai.business;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the {@code business.*} metadata namespace so a typo in an
 * observability backend isn't a silent no-op. The v0.8 gap #1 (Business
 * Outcome metadata) depends on this namespace being stable.
 */
class BusinessMetadataTest {

    @Test
    void keysUseTheBusinessPrefix() {
        // OpenTelemetry convention: attribute keys share a stable prefix
        // so dashboards can glob-match by namespace.
        assertTrue(BusinessMetadata.TENANT_ID.startsWith("business."));
        assertTrue(BusinessMetadata.CUSTOMER_ID.startsWith("business."));
        assertTrue(BusinessMetadata.CUSTOMER_SEGMENT.startsWith("business."));
        assertTrue(BusinessMetadata.SESSION_REVENUE.startsWith("business."));
        assertTrue(BusinessMetadata.SESSION_COST.startsWith("business."));
        assertTrue(BusinessMetadata.SESSION_CURRENCY.startsWith("business."));
        assertTrue(BusinessMetadata.SESSION_ID.startsWith("business."));
        assertTrue(BusinessMetadata.EVENT_KIND.startsWith("business."));
        assertTrue(BusinessMetadata.EVENT_SUBJECT.startsWith("business."));
    }

    @Test
    void eventKindsRoundTripThroughWire() {
        for (var kind : BusinessMetadata.EventKind.values()) {
            assertEquals(kind, BusinessMetadata.EventKind.fromWire(kind.wireName()),
                    "wire name must be reversible: " + kind);
        }
    }

    @Test
    void unknownEventWireNameFallsBackToOther() {
        assertEquals(BusinessMetadata.EventKind.OTHER,
                BusinessMetadata.EventKind.fromWire("future_kind_not_yet_defined"));
        assertEquals(BusinessMetadata.EventKind.OTHER,
                BusinessMetadata.EventKind.fromWire(null));
    }
}
