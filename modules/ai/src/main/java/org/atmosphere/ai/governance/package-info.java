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

/**
 * Declarative governance-policy SPI layered on top of {@link org.atmosphere.ai.AiGuardrail}.
 *
 * <p>Two vocabularies, one enforcement path:</p>
 * <ul>
 *   <li>{@link org.atmosphere.ai.AiGuardrail} — imperative Java SPI; an implementation
 *       is a class the application (or auto-configuration) instantiates directly.</li>
 *   <li>{@link org.atmosphere.ai.governance.GovernancePolicy} — declarative policy
 *       identity (name, source, version) plus an {@link org.atmosphere.ai.governance.PolicyContext}
 *       → {@link org.atmosphere.ai.governance.PolicyDecision} evaluation method. Policies
 *       can be instantiated directly in code or loaded via a
 *       {@link org.atmosphere.ai.governance.PolicyParser} implementation (YAML, Rego, Cedar).</li>
 * </ul>
 *
 * <p>Both SPIs are designed to land at the same {@code AiPipeline} admission seam via
 * adapters — the declarative layer is additive, so existing applications that wire
 * {@code AiGuardrail} beans directly keep working unchanged. This package ships the SPI
 * surface only; parser implementations, the pipeline wiring, and auto-configuration
 * bridges land in follow-up commits.</p>
 */
package org.atmosphere.ai.governance;
