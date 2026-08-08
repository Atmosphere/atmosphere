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
 * Inbound durable batch job endpoint. Exposes registered {@code @Agent} /
 * {@code @AiEndpoint} pipelines behind an async submit/poll surface at
 * {@code /atmosphere/v1/batches}: submit N independent LLM requests as one
 * job, poll its status, fetch per-item results, cancel. Jobs survive JVM
 * restart when {@code atmosphere.ai.batch.db} points the store at a SQLite
 * file (jobs left in flight by a crash are marked failed on the next start).
 *
 * <p><strong>This is Atmosphere's own batch wire shape</strong> — inline JSON
 * items in, inline results out. It is <em>not</em> the OpenAI Batch API
 * (no file upload, no JSONL); only the error envelope is shared with the
 * OpenAI-compatible serving surface.</p>
 *
 * <p>Off by default. Opt in with {@code atmosphere.ai.batch.enabled=true};
 * see {@link org.atmosphere.ai.batch.BatchServing} for the full configuration
 * surface. Every batch item dispatches through
 * {@link org.atmosphere.ai.AiPipeline#execute(String, String,
 * org.atmosphere.ai.StreamingSession)} so admission governance, guardrails,
 * budgets, and cost accounting apply exactly as they do for interactive
 * traffic (Correctness Invariant #7, Mode Parity). The admin eval dataset
 * runner routes dataset replays through
 * {@link org.atmosphere.ai.batch.BatchExecutor} when this surface is
 * enabled.</p>
 */
package org.atmosphere.ai.batch;
