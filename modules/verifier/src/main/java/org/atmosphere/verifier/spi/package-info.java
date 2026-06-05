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
 * Plan-verification SPI — discovered via {@link java.util.ServiceLoader}.
 *
 * <p>Each {@link org.atmosphere.verifier.spi.PlanVerifier} runs an independent
 * static check against a {@link org.atmosphere.verifier.ast.Workflow} +
 * {@link org.atmosphere.verifier.policy.Policy} pair and returns a
 * {@link org.atmosphere.verifier.spi.VerificationResult} carrying any
 * {@link org.atmosphere.verifier.spi.Violation}s. Verifiers are pure
 * functions: same inputs → same result, no side effects, no tool calls.</p>
 *
 * <p>Verifiers register via
 * {@code META-INF/services/org.atmosphere.verifier.spi.PlanVerifier}.
 * The module ships six structural verifiers — {@code AllowlistVerifier},
 * {@code WellFormednessVerifier}, {@code CapabilityVerifier},
 * {@code TaintVerifier}, {@code AutomatonVerifier}, and {@code SmtVerifier}.
 * {@code SmtVerifier} wraps the {@link SmtChecker} SPI. The in-tree no-op
 * default is {@code NoOpSmtChecker}; a real SMT backend (SMTInterpol, pure-JVM)
 * ships as the {@code atmosphere-verifier-smt} module and is selected by
 * {@link SmtChecker#resolve()} when present. A native Z3/CVC5 backend can be
 * added as a higher-priority {@code SmtChecker} without code changes here.</p>
 */
package org.atmosphere.verifier.spi;
