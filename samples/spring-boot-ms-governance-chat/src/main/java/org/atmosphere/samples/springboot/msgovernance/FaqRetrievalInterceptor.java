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
package org.atmosphere.samples.springboot.msgovernance;

import org.atmosphere.ai.AiInterceptor;
import org.atmosphere.ai.AiRequest;
import org.atmosphere.cpr.AtmosphereResource;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tier 6.2 — retrieval layer for the MS-governance sample. Looks up the
 * user's message against {@link FaqKnowledgeBase}; on a hit, tags the
 * retrieved snippet + category onto {@code AiRequest.metadata()}.
 *
 * <p>Why through metadata and not a transformed message: the governance
 * chain still sees the original user text (so scope classification, PII
 * detection, and audit snapshots stay honest), while downstream consumers
 * — the LLM prompt builder, the RAG content-injection classifier, the
 * admin trail — can read the retrieved snippet alongside. Mutating the
 * message would hide the user's real question from every policy that
 * runs after this interceptor.</p>
 *
 * <p>Integration with the governance chain: the snippet written under
 * {@link FaqKnowledgeBase#RAG_SNIPPET_METADATA_KEY} feeds
 * {@code org.atmosphere.ai.governance.rag.RagContentInjectionPolicy} —
 * if a malicious snippet slipped into the KB (indirect prompt injection),
 * the policy catches it before the LLM sees it.</p>
 */
public final class FaqRetrievalInterceptor implements AiInterceptor {

    private final FaqKnowledgeBase kb;

    /** Default — the Example Corp FAQ bundled with the sample. */
    public FaqRetrievalInterceptor() {
        this(FaqKnowledgeBase.exampleCorp());
    }

    public FaqRetrievalInterceptor(FaqKnowledgeBase kb) {
        if (kb == null) {
            throw new IllegalArgumentException("kb must not be null");
        }
        this.kb = kb;
    }

    @Override
    public AiRequest preProcess(AiRequest request, AtmosphereResource resource) {
        if (request == null || request.message() == null || request.message().isBlank()) {
            return request;
        }
        var match = kb.retrieve(request.message());
        if (match.isEmpty()) {
            return request;
        }
        var hit = match.get();
        var metadata = new LinkedHashMap<String, Object>(
                request.metadata() == null ? Map.of() : request.metadata());
        metadata.put(FaqKnowledgeBase.RAG_SNIPPET_METADATA_KEY, hit.entry().answer());
        metadata.put(FaqKnowledgeBase.RAG_CATEGORY_METADATA_KEY, hit.entry().category());
        metadata.put("rag.score", hit.score());
        return request.withMetadata(Map.copyOf(metadata));
    }

    /** Exposed for tests + admin introspection. */
    public FaqKnowledgeBase knowledgeBase() {
        return kb;
    }
}
