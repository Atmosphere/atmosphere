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
package org.atmosphere.a2a.runtime;

/**
 * Marker for an A2A skill handler that drives the streaming
 * {@code AiPipeline} and therefore records its own tape run. {@link
 * A2aProtocolHandler} records a single-run "completed dispatch" tape for a
 * tool-agent skill (a plain {@code @AgentSkillHandler} that returns directly and
 * never enters the pipeline) so the child appears in a coordination tree; but it
 * must NOT do so for a pipeline-backed handler, which the pipeline already
 * tapes — that would double-tape the same dispatch. Handlers whose instance
 * implements this interface are skipped by the tool-dispatch tape.
 */
public interface PipelineBackedSkill {
}
