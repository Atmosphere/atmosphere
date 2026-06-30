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
package org.atmosphere.ai.resume;

/**
 * The lifecycle status of a journaled effect (and, when passed to
 * {@link EffectJournal#markTerminal}, of the run as a whole).
 *
 * <p>The two-phase write is {@code PENDING} → {@code COMMITTED}: an effect is
 * appended {@code PENDING} <em>before</em> its side effect runs and flipped to
 * {@code COMMITTED} with its recorded result <em>after</em>. Only a
 * {@code COMMITTED} effect is a replay hit — a {@code PENDING} effect (a crash
 * landed between append and commit) or a {@code FAILED} effect re-runs on
 * resume, giving the documented at-least-once boundary.</p>
 *
 * @since 4.0
 */
public enum EffectStatus {

    /** Appended before the side effect ran; not yet a replay hit. */
    PENDING,

    /** The side effect completed and its result is recorded; a replay hit. */
    COMMITTED,

    /** The side effect failed; recorded for audit, re-runs on resume. */
    FAILED
}
