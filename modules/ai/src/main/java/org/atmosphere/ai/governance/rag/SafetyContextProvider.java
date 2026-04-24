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
package org.atmosphere.ai.governance.rag;

import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.governance.AuditEntry;
import org.atmosphere.ai.governance.GovernanceDecisionLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Decorator that wraps any {@link ContextProvider} and runs every
 * retrieved document through an {@link InjectionClassifier} before it
 * reaches the LLM. Addresses OWASP Agentic Top-10 A04 (Indirect Prompt
 * Injection) — the attacker plants a malicious document in a RAG store,
 * the agent retrieves it, and the embedded instructions redirect the
 * assistant.
 *
 * <h2>Breach policy</h2>
 * <ul>
 *   <li>{@link Breach#DROP} (default) — remove the flagged document from
 *       the return list; downstream consumers never see it. Audit entry
 *       records the drop.</li>
 *   <li>{@link Breach#FLAG} — keep the document but rewrite its metadata
 *       with {@code rag.safety.flagged = true} so downstream interceptors
 *       can reason about it (e.g., render a "possibly unsafe source"
 *       disclaimer to the user).</li>
 *   <li>{@link Breach#SANITIZE} — replace the flagged document's content
 *       with a sanitized placeholder ("[retrieved content was flagged as
 *       potential prompt injection and removed]") so the LLM sees a
 *       non-actionable marker instead of the raw payload.</li>
 * </ul>
 *
 * <h2>Classifier failures</h2>
 * A classifier returning {@link InjectionClassifier.Outcome#ERROR} is
 * treated as a DROP (fail-closed) by default — an undetectable injection
 * is worse than a missed retrieval. Operators who prefer fail-open set
 * {@link Builder#failOpen(boolean)}.
 *
 * <h2>Runtime-agnostic</h2>
 * All enforcement is at this decorator layer; the classifier itself
 * consumes {@link org.atmosphere.ai.EmbeddingRuntime} or
 * {@link org.atmosphere.ai.AgentRuntime} via the standard SPIs, so every
 * runtime adapter participates without per-runtime wiring.
 */
public final class SafetyContextProvider implements ContextProvider {

    private static final Logger logger = LoggerFactory.getLogger(SafetyContextProvider.class);

    /** Metadata key set on flagged documents under {@link Breach#FLAG}. */
    public static final String METADATA_FLAGGED_KEY = "rag.safety.flagged";

    /** Metadata key holding the classifier reason on flagged or sanitized documents. */
    public static final String METADATA_REASON_KEY = "rag.safety.reason";

    /** Metadata key holding the classifier confidence on flagged / sanitized documents. */
    public static final String METADATA_CONFIDENCE_KEY = "rag.safety.confidence";

    /** Placeholder used by {@link Breach#SANITIZE} so the LLM sees a non-actionable marker. */
    public static final String SANITIZED_PLACEHOLDER =
            "[retrieved content was flagged as potential prompt injection and removed]";

    private final ContextProvider delegate;
    private final InjectionClassifier classifier;
    private final Breach onBreach;
    private final boolean failOpen;
    private final String policyName;

    private SafetyContextProvider(Builder b) {
        if (b.delegate == null) {
            throw new IllegalArgumentException("delegate ContextProvider must not be null");
        }
        this.delegate = b.delegate;
        this.classifier = b.classifier != null
                ? b.classifier
                : InjectionClassifierResolver.resolve(b.tier);
        this.onBreach = b.onBreach;
        this.failOpen = b.failOpen;
        this.policyName = b.policyName != null ? b.policyName : "rag-safety";
    }

    public static Builder wrapping(ContextProvider delegate) {
        var b = new Builder();
        b.delegate = delegate;
        return b;
    }

    @Override
    public List<Document> retrieve(String query, int maxResults) {
        var retrieved = delegate.retrieve(query, maxResults);
        if (retrieved == null || retrieved.isEmpty()) {
            return retrieved;
        }
        var result = new ArrayList<Document>(retrieved.size());
        for (var doc : retrieved) {
            InjectionClassifier.Decision decision;
            try {
                decision = classifier.evaluate(doc);
            } catch (RuntimeException e) {
                logger.error("InjectionClassifier ({}) threw on document from '{}': {}",
                        classifier.getClass().getSimpleName(), doc.source(), e.toString());
                decision = InjectionClassifier.Decision.error(
                        "classifier threw: " + e.getMessage());
            }
            var handled = applyDecision(doc, decision);
            if (handled != null) {
                result.add(handled);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public String transformQuery(String originalQuery) {
        return delegate.transformQuery(originalQuery);
    }

    @Override
    public List<Document> rerank(String query, List<Document> documents) {
        return delegate.rerank(query, documents);
    }

    @Override
    public void ingest(List<Document> documents) {
        delegate.ingest(documents);
    }

    @Override
    public boolean isAvailable() {
        return delegate.isAvailable();
    }

    /**
     * Apply the configured breach policy to one document / decision pair.
     * Returns {@code null} when the doc should be dropped.
     */
    private Document applyDecision(Document doc, InjectionClassifier.Decision decision) {
        switch (decision.outcome()) {
            case SAFE -> {
                return doc;
            }
            case ERROR -> {
                recordAudit(doc, decision, failOpen ? "audit" : "drop");
                if (failOpen) {
                    return doc;
                }
                logger.warn("SafetyContextProvider dropping document from '{}' — classifier error: {}",
                        doc.source(), decision.reason());
                return null;
            }
            case INJECTED -> {
                recordAudit(doc, decision, breachLabel());
                return switch (onBreach) {
                    case DROP -> {
                        logger.warn("SafetyContextProvider dropping document from '{}': {}",
                                doc.source(), decision.reason());
                        yield null;
                    }
                    case FLAG -> flagged(doc, decision);
                    case SANITIZE -> sanitized(doc, decision);
                };
            }
            default -> {
                // Defensive — unknown outcomes drop with a warning.
                logger.warn("Unknown classifier outcome {} for '{}' — dropping",
                        decision.outcome(), doc.source());
                return null;
            }
        }
    }

    private Document flagged(Document doc, InjectionClassifier.Decision decision) {
        var md = new LinkedHashMap<String, String>(
                doc.metadata() == null ? java.util.Map.of() : doc.metadata());
        md.put(METADATA_FLAGGED_KEY, "true");
        md.put(METADATA_REASON_KEY, decision.reason());
        md.put(METADATA_CONFIDENCE_KEY, Double.toString(decision.confidence()));
        return new Document(doc.content(), doc.source(), doc.score(), java.util.Map.copyOf(md));
    }

    private Document sanitized(Document doc, InjectionClassifier.Decision decision) {
        var md = new LinkedHashMap<String, String>(
                doc.metadata() == null ? java.util.Map.of() : doc.metadata());
        md.put(METADATA_FLAGGED_KEY, "true");
        md.put(METADATA_REASON_KEY, decision.reason());
        md.put(METADATA_CONFIDENCE_KEY, Double.toString(decision.confidence()));
        return new Document(SANITIZED_PLACEHOLDER, doc.source(), doc.score(),
                java.util.Map.copyOf(md));
    }

    private String breachLabel() {
        return switch (onBreach) {
            case DROP -> "drop";
            case FLAG -> "flag";
            case SANITIZE -> "sanitize";
        };
    }

    private void recordAudit(Document doc, InjectionClassifier.Decision decision, String action) {
        var snapshot = new LinkedHashMap<String, Object>();
        snapshot.put("phase", "rag_retrieval");
        snapshot.put("source", doc.source());
        snapshot.put("classifier", classifier.getClass().getSimpleName());
        snapshot.put("tier", classifier.tier().name());
        snapshot.put("outcome", decision.outcome().name());
        snapshot.put("confidence", decision.confidence());
        snapshot.put("breach_action", action);
        var entry = new AuditEntry(
                Instant.now(),
                policyName,
                "code:" + getClass().getSimpleName(),
                "1.0",
                decision.outcome() == InjectionClassifier.Outcome.INJECTED ? "deny" : "error",
                decision.reason(),
                snapshot,
                0.0);
        GovernanceDecisionLog.installed().record(entry);
    }

    /** How a classified-injection document is handled. */
    public enum Breach {
        /** Remove the document entirely; downstream consumers never see it. */
        DROP,
        /** Keep the document; annotate metadata so downstream can flag it. */
        FLAG,
        /** Replace the document content with a non-actionable placeholder. */
        SANITIZE
    }

    public static final class Builder {
        private ContextProvider delegate;
        private InjectionClassifier classifier;
        private InjectionClassifier.Tier tier = InjectionClassifier.Tier.EMBEDDING_SIMILARITY;
        private Breach onBreach = Breach.DROP;
        private boolean failOpen = false;
        private String policyName;

        private Builder() { }

        /** Explicit classifier; overrides {@link #tier}. */
        public Builder classifier(InjectionClassifier classifier) {
            this.classifier = classifier;
            return this;
        }

        /** Select the default classifier tier (ignored if {@link #classifier} is set). */
        public Builder tier(InjectionClassifier.Tier tier) {
            this.tier = tier;
            return this;
        }

        public Builder onBreach(Breach onBreach) {
            this.onBreach = onBreach;
            return this;
        }

        /**
         * If {@code true}, classifier ERROR outcomes admit the document
         * rather than drop it. Default {@code false} (fail-closed).
         */
        public Builder failOpen(boolean failOpen) {
            this.failOpen = failOpen;
            return this;
        }

        /** Name used in {@link AuditEntry#policyName()} for audit entries. */
        public Builder policyName(String policyName) {
            this.policyName = policyName;
            return this;
        }

        public SafetyContextProvider build() {
            return new SafetyContextProvider(this);
        }
    }
}
