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
 * Declarative policy records consumed by the static verifier chain.
 *
 * <p>A {@link org.atmosphere.verifier.policy.Policy} declares which tools are
 * permitted, which data flows are forbidden
 * ({@link org.atmosphere.verifier.policy.TaintRule}), and which sequences of
 * tool calls violate a security state machine
 * ({@link org.atmosphere.verifier.policy.SecurityAutomaton}). The same Policy
 * record is intended to feed both the static
 * {@link org.atmosphere.verifier.spi.PlanVerifier} chain and Atmosphere's
 * runtime {@code GovernancePolicy} chain — defense in depth from a single
 * declaration.</p>
 *
 * <p>Every field has a matching verifier in
 * {@link org.atmosphere.verifier.checks}: {@code allowedTools} →
 * {@code AllowlistVerifier}, {@code taintRules} → {@code TaintVerifier},
 * {@code automata} → {@code AutomatonVerifier}, capability data →
 * {@code CapabilityVerifier}, {@code numericInvariants} → the SMT-backed
 * {@code SmtChecker}, and
 * {@link org.atmosphere.verifier.policy.ControlFlowMode} →
 * {@code StructureVerifier}. The {@link org.atmosphere.verifier.policy.Condition}
 * grammar backs automaton guards and conditional-branch predicates.</p>
 */
package org.atmosphere.verifier.policy;
