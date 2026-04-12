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
package org.atmosphere.ai.cache;

import java.util.Optional;

/**
 * Pipeline-level response cache. Stores full runtime responses keyed by a
 * hash of the prompt, system prompt, history, and tool set so that repeated
 * requests with identical inputs can skip the runtime entirely and stream
 * the cached response through the session.
 *
 * <p>This is an alternative to provider-side prompt caching (OpenAI
 * {@code prompt_cache_key}, Anthropic ephemeral, Gemini {@code CachedContent})
 * — it works regardless of which runtime is active because the cache lives
 * at the {@link org.atmosphere.ai.AiPipeline} layer. It does not replace
 * provider-side caching; the two can stack.</p>
 *
 * <p>Opt-in via {@link org.atmosphere.ai.llm.CacheHint} on the execution
 * context's {@code metadata} map. The pipeline consults the cache only
 * when the hint is enabled.</p>
 */
public interface ResponseCache {

    /** Look up a cached response by key. */
    Optional<CachedResponse> get(String key);

    /** Store a response under the given key. */
    void put(String key, CachedResponse response);

    /** Remove a single entry. */
    void invalidate(String key);

    /** Current entry count. */
    int size();

    /** Remove all entries. */
    void clear();
}
