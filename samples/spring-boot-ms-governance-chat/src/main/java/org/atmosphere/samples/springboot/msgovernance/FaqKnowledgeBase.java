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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Tiny in-memory FAQ — stand-in for a real RAG/vector store so the sample
 * can demonstrate the governance + retrieval interplay without pulling in a
 * vector DB. Scoring is keyword overlap after stopword filtering; sufficient
 * to route a user question to the right canned snippet for demo traffic.
 *
 * <p>Real deployments swap this for a {@code RagIndex} SPI backed by an
 * embedding runtime + vector store. The governance-interesting property is
 * that retrieved snippets ride on {@code AiRequest.metadata()} — which the
 * RAG content-injection classifier
 * ({@code org.atmosphere.ai.governance.rag.RagContentInjectionPolicy})
 * can then scan before the snippets reach the LLM.</p>
 */
public final class FaqKnowledgeBase {

    /** Key written onto {@code AiRequest.metadata()} when a snippet is retrieved. */
    public static final String RAG_SNIPPET_METADATA_KEY = "rag.snippet";

    /** Key carrying the FAQ category of the matched snippet ({@code orders} / {@code billing} / ...). */
    public static final String RAG_CATEGORY_METADATA_KEY = "rag.category";

    /** Minimum overlap score for a snippet to be considered a match. Two shared keywords. */
    public static final int MIN_MATCH_SCORE = 2;

    private static final Set<String> STOPWORDS = Set.of(
            "a", "an", "and", "are", "as", "at", "be", "by", "can", "do", "does",
            "for", "from", "have", "how", "i", "if", "in", "is", "it", "me", "my",
            "of", "on", "or", "should", "so", "that", "the", "this", "to", "what",
            "when", "where", "which", "who", "why", "will", "with", "you", "your");

    /** One entry — category, canonical question, answer, precomputed keyword set. */
    public record Entry(String category, String question, String answer, Set<String> keywords) {
        public Entry {
            if (category == null || category.isBlank()) {
                throw new IllegalArgumentException("category must not be blank");
            }
            if (question == null || question.isBlank()) {
                throw new IllegalArgumentException("question must not be blank");
            }
            if (answer == null || answer.isBlank()) {
                throw new IllegalArgumentException("answer must not be blank");
            }
            if (keywords == null) {
                keywords = keywordsOf(question);
            }
        }
    }

    /** Scored retrieval result — the entry plus the overlap score that chose it. */
    public record Match(Entry entry, int score) { }

    private final List<Entry> entries;

    public FaqKnowledgeBase(List<Entry> entries) {
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("entries must not be null or empty");
        }
        this.entries = List.copyOf(entries);
    }

    /** The canonical Example Corp FAQ used by the sample. */
    public static FaqKnowledgeBase exampleCorp() {
        return new FaqKnowledgeBase(List.of(
                new Entry("orders",
                        "How do I check the status of my order?",
                        "Visit your account page at example.com/orders, enter your order "
                                + "ID, and the current shipping status and tracking "
                                + "number will appear. Orders placed before 3 pm ship "
                                + "the same business day.",
                        null),
                new Entry("orders",
                        "Can I cancel or modify an order after placing it?",
                        "Orders can be cancelled or modified within 30 minutes of "
                                + "placement via example.com/orders. After 30 minutes "
                                + "they enter our fulfillment queue and can no longer "
                                + "be changed — you'll need to request a return.",
                        null),
                new Entry("billing",
                        "What payment methods are accepted?",
                        "We accept Visa, Mastercard, American Express, PayPal, and "
                                + "Apple Pay. Corporate customers can apply for net-30 "
                                + "terms via billing@example.com.",
                        null),
                new Entry("billing",
                        "How do I update my billing address?",
                        "Go to Account → Billing → Edit address. The new address applies "
                                + "to all subsequent charges; existing subscriptions keep "
                                + "the address on file at the time of charge unless you "
                                + "update the specific subscription.",
                        null),
                new Entry("refunds",
                        "What is the refund policy?",
                        "Full refunds are available for 30 days after purchase. "
                                + "Unopened items get full refund; opened items get a "
                                + "90% refund minus a 10% restocking fee. Digital "
                                + "goods are non-refundable once activated.",
                        null),
                new Entry("refunds",
                        "How long does a refund take to appear on my card?",
                        "Refunds typically post within 3-5 business days of approval. "
                                + "Amex sometimes takes up to 10 business days. If it "
                                + "hasn't arrived after 10 days, reply to the refund "
                                + "confirmation email and we'll investigate.",
                        null),
                new Entry("shipping",
                        "How much does shipping cost?",
                        "Standard shipping within the US is $4.95 and arrives in "
                                + "3-5 business days. Expedited is $9.95 (2 days). "
                                + "International shipping is calculated at checkout based "
                                + "on destination and weight.",
                        null),
                new Entry("account",
                        "How do I reset my password?",
                        "Visit example.com/account/reset, enter the email on file, and "
                                + "we'll send a one-time link. The link expires after "
                                + "30 minutes. If you don't receive it, check spam or "
                                + "contact support@example.com.",
                        null)
        ));
    }

    /** Top match for {@code query}, or empty when nothing clears {@link #MIN_MATCH_SCORE}. */
    public java.util.Optional<Match> retrieve(String query) {
        if (query == null || query.isBlank()) {
            return java.util.Optional.empty();
        }
        var queryKeywords = keywordsOf(query);
        if (queryKeywords.isEmpty()) {
            return java.util.Optional.empty();
        }
        var scored = new ArrayList<Match>(entries.size());
        for (var entry : entries) {
            int score = overlap(queryKeywords, entry.keywords());
            if (score >= MIN_MATCH_SCORE) {
                scored.add(new Match(entry, score));
            }
        }
        return scored.stream().max(Comparator.comparingInt(Match::score));
    }

    /** All entries — for admin introspection / tests. */
    public List<Entry> entries() {
        return entries;
    }

    private static Set<String> keywordsOf(String text) {
        var words = new HashSet<String>();
        for (var token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token.length() > 2 && !STOPWORDS.contains(token)) {
                words.add(token);
            }
        }
        return words;
    }

    private static int overlap(Set<String> query, Set<String> entry) {
        int count = 0;
        for (var w : query) {
            if (entry.contains(w)) count++;
        }
        return count;
    }

    /** Exposed for interceptor / test building of custom KBs. */
    public static Entry entry(String category, String question, String answer, String... extraKeywords) {
        var kws = new HashSet<>(keywordsOf(question));
        kws.addAll(Arrays.asList(extraKeywords));
        return new Entry(category, question, answer, kws);
    }
}
