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
package org.atmosphere.admin.ai;

import java.util.List;

/**
 * Optional application hook that supplies named example goals for the
 * Atmosphere Console's Validation tab. A verifier-enabled app implements
 * this (e.g. as a Spring {@code @Bean}) so the console can offer one-click
 * demonstrations — typically a mix of plans that pass the verifier chain and
 * plans that are refused (taint, SMT, allowlist, …) — without the operator
 * having to hand-author plan JSON.
 *
 * <p>When no {@code VerifierExampleSource} bean is present the Validation tab
 * still renders the verifier chain and policy summary and accepts free-text
 * goals; it simply offers no preset buttons.</p>
 */
public interface VerifierExampleSource {

    /**
     * A single clickable example surfaced in the Validation tab.
     *
     * @param id          stable identifier used as the button {@code data-testid}.
     * @param label       short button label.
     * @param goal        the natural-language goal handed to the planner.
     * @param description one-line explanation of what the example demonstrates
     *                    (e.g. "refused — SMT cannot prove count &le; quota").
     */
    record Example(String id, String label, String goal, String description) {
    }

    /**
     * @return the ordered list of examples to surface; never {@code null}.
     */
    List<Example> examples();
}
