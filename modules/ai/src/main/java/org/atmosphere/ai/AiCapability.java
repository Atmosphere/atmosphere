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
package org.atmosphere.ai;

/**
 * Capabilities that an AI adapter or model may support. Used for:
 * <ul>
 *   <li>Smart model routing — only fail over to models with matching capabilities</li>
 *   <li>Tool calling — don't offer tools to a model that can't call them</li>
 *   <li>Multi-modal — don't send images to a text-only model</li>
 *   <li>Feature negotiation — endpoints can declare required capabilities</li>
 * </ul>
 *
 * <p>Adapters declare their capabilities via
 * {@link AiStreamingAdapter#capabilities()} and {@link AiSupport#capabilities()}.
 * The framework uses these to make routing and configuration decisions.</p>
 */
public enum AiCapability {

    /** Basic text streaming (all adapters support this). */
    TEXT_STREAMING,

    /** Model can call tools/functions and process their results. */
    TOOL_CALLING,

    /** Model can return structured output (JSON schema conformance). */
    STRUCTURED_OUTPUT,

    /** Model can process image inputs. */
    VISION,

    /** Model can process audio inputs. */
    AUDIO,

    /** Model can generate or process multi-modal content (images, audio, files). */
    MULTI_MODAL,

    /** Adapter manages its own conversation memory (e.g., Google ADK sessions). */
    CONVERSATION_MEMORY,

    /** Model supports system prompts. */
    SYSTEM_PROMPT,

    /** Adapter supports agent/multi-step orchestration (e.g., Embabel, ADK). */
    AGENT_ORCHESTRATION
}
