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
package org.atmosphere.samples.springboot.a2astartup;

/**
 * Structured output record for market assessment. When an {@code @AiEndpoint}
 * or {@code @Agent} declares {@code responseAs = MarketAssessment.class}, the
 * framework instructs the LLM to return JSON matching this schema and emits
 * {@code EntityStart}, {@code StructuredField}, and {@code EntityComplete}
 * events with the parsed result.
 *
 * @param market     the market being analyzed (e.g., "AI fitness apps")
 * @param verdict    GO or NO_GO recommendation
 * @param confidence confidence level 1-10
 * @param reasoning  one-sentence rationale for the verdict
 * @param tamBillions total addressable market in billions USD
 */
public record MarketAssessment(
        String market,
        String verdict,
        int confidence,
        String reasoning,
        double tamBillions
) {}
