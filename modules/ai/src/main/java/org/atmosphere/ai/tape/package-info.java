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
 * The session tape: an append-only, durable, per-run record of the typed
 * {@link org.atmosphere.ai.AiEvent} / legacy-call stream crossing the
 * {@link org.atmosphere.ai.StreamingSession} boundary, with per-step
 * provenance. Recorded "as-produced at the session boundary, post-decorator."
 *
 * <p>The tape is a distinct artifact from the three existing histories —
 * {@code RunJournal} (wire reattach, transient, string frames),
 * {@code EffectJournal} (compute re-drive, two-phase), and
 * {@code InteractionStore} (typed durable step log for the interactions
 * surface only): it is the typed event stream at the session boundary,
 * durable, append-only, surviving completion, across the endpoint and
 * pipeline dispatch paths.</p>
 *
 * @see org.atmosphere.ai.tape.TapeSupport
 * @see org.atmosphere.ai.tape.TapeStore
 */
package org.atmosphere.ai.tape;
