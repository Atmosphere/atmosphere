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
package org.atmosphere.ai.decision;

import org.atmosphere.ai.intent.IntentRouting;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins {@link IntentRouting#MAX_ROUTES} to the largest choice the
 * {@link RuntimeDecisionModel} answers with letter codes: past it, the route
 * choice could no longer be scored from the model's distribution, which is
 * the reason for the bound.
 */
class IntentRoutingBoundTest {

    @Test
    void maxRoutesMatchesTheCodedChoiceCeiling() {
        assertEquals(RuntimeDecisionModel.MAX_CODED_OPTIONS, IntentRouting.MAX_ROUTES);
    }
}
