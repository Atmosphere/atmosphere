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
package org.atmosphere.checkpoint.temporal;

/**
 * Wire-shape of one workflow step for the Temporal workflow input: the step's
 * stable name plus its retry budget translated to Temporal terms
 * ({@code maxAttempts} = the step's {@code maxRetries() + 1}, fixed backoff of
 * {@code retryDelayMillis} between attempts). The step's code never crosses
 * the wire — it executes in the JVM that started the run.
 */
public record StepSpec(String name, int maxAttempts, long retryDelayMillis) { }
