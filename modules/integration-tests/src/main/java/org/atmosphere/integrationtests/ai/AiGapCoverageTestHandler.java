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
package org.atmosphere.integrationtests.ai;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.AiConfidenceElicitation;
import org.atmosphere.ai.AiMetrics;
import org.atmosphere.ai.AiPipeline;
import org.atmosphere.ai.ContextProvider;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.StreamingSessions;
import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.tool.DefaultToolRegistry;
import org.atmosphere.ai.tool.ToolDefinition;
import org.atmosphere.coordinator.evaluation.SanityCheckEvaluator;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.RawMessage;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic E2E endpoint for AI gap-fix coverage.
 */
public class AiGapCoverageTestHandler implements AtmosphereHandler {

    @Override
    public void onRequest(AtmosphereResource resource) throws IOException {
        resource.suspend();

        var reader = resource.getRequest().getReader();
        var command = reader.readLine();
        if (command != null && !command.trim().isEmpty()) {
            Thread.ofVirtual().name("ai-gap-coverage-test").start(() -> handle(command.trim(), resource));
        }
    }

    private void handle(String command, AtmosphereResource resource) {
        var session = StreamingSessions.start(resource);
        try {
            switch (command) {
                case "rag" -> runRag(session);
                case "telemetry" -> runTelemetry(session);
                case "eval" -> runEval(session);
                default -> {
                    session.sendMetadata("gap.error", "unknown command: " + command);
                    session.complete();
                }
            }
        } catch (RuntimeException e) {
            session.error(e);
        }
    }

    private void runRag(StreamingSession session) {
        var pipeline = new AiPipeline(
                new RagEchoRuntime(),
                "Use retrieved context and cite sources.",
                "gap-rag-model",
                null,
                new DefaultToolRegistry(),
                List.of(),
                List.of(new GapContextProvider()),
                AiMetrics.NOOP);

        pipeline.execute("gap-rag-client", "  Atmosphere transports for tenant alpha  ", session);
    }

    private void runTelemetry(StreamingSession session) {
        var memory = new InMemoryConversationMemory(6);
        memory.addMessage("gap-telemetry-client", ChatMessage.user("previous user turn"));
        memory.addMessage("gap-telemetry-client", ChatMessage.assistant("previous assistant turn"));

        var toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(ToolDefinition.builder("lookup_order", "Lookup an order by identifier")
                .parameter("orderId", "The order identifier", "string")
                .returnType("object")
                .executor(args -> Map.of("status", "ok"))
                .build());

        var pipeline = new AiPipeline(
                new TelemetryEchoRuntime(),
                "System prompt for telemetry coverage.",
                "gap-telemetry-model",
                memory,
                toolRegistry,
                List.of(),
                List.of(),
                new MetadataMetrics(session));
        pipeline.setDefaultConfidenceElicitation(AiConfidenceElicitation.withField("confidence"));

        pipeline.execute("gap-telemetry-client", "trace this input assembly", session);
    }

    private void runEval(StreamingSession session) {
        var evaluator = new SanityCheckEvaluator(5, 0.3);
        var result = new AgentResult(
                "gap-agent",
                "answer",
                "Atmosphere streams agent responses with deterministic telemetry.",
                Map.of("traceId", "gap-trace-001"),
                Duration.ofMillis(12),
                true);
        var evaluation = evaluator.evaluate(
                result,
                new AgentCall("gap-agent", "answer", Map.of("topic", "telemetry")));

        session.sendMetadata("eval.name", evaluator.name());
        session.sendMetadata("eval.passed", evaluation.passed());
        session.sendMetadata("eval.score", evaluation.score());
        session.sendMetadata("eval.reason", evaluation.reason());
        session.sendMetadata("eval.wordCount", evaluation.metadata().get("wordCount"));
        session.send("evaluation complete");
        session.complete();
    }

    @Override
    public void onStateChange(AtmosphereResourceEvent event) throws IOException {
        if (event.isCancelled() || event.isResumedOnTimeout()
                || event.isClosedByClient() || event.isClosedByApplication()) {
            return;
        }
        var message = event.getMessage();
        if (message instanceof RawMessage raw && raw.message() instanceof String json) {
            event.getResource().getResponse().write(json);
            event.getResource().getResponse().flushBuffer();
        }
    }

    @Override
    public void destroy() {
    }

    private static final class GapContextProvider implements ContextProvider {

        @Override
        public String transformQuery(String originalQuery) {
            return "tenant:alpha " + ContextProvider.normalizeQuery(originalQuery).toLowerCase();
        }

        @Override
        public List<Document> retrieve(String query, int maxResults) {
            return List.of(
                    new Document(
                            "Atmosphere supports WebSocket and SSE fallback.",
                            "transport-guide.md",
                            0.91,
                            Map.of("tenant", "alpha",
                                    "source_document", "transport-guide.md",
                                    "chunk_index", "2",
                                    "chunk_count", "5",
                                    "chunk_start", "40",
                                    "chunk_end", "96")),
                    new Document(
                            "A beta-tenant document must not cross the boundary.",
                            "tenant-beta.md",
                            0.99,
                            Map.of("tenant", "beta")),
                    new Document(
                            "Low-score alpha noise should be filtered.",
                            "alpha-noise.md",
                            0.31,
                            Map.of("tenant", "alpha")));
        }

        @Override
        public List<Document> filter(String query, List<Document> documents) {
            return documents.stream()
                    .filter(document -> "alpha".equals(document.metadata().get("tenant")))
                    .filter(document -> document.score() >= 0.70)
                    .toList();
        }

        @Override
        public List<Document> rerank(String query, List<Document> documents) {
            return documents.stream()
                    .sorted(Comparator.comparingDouble(Document::score).reversed())
                    .toList();
        }

        @Override
        public List<Document> postProcess(String query, List<Document> documents) {
            var merged = new ArrayList<Document>();
            for (var document : documents) {
                var metadata = new LinkedHashMap<>(document.metadata());
                metadata.put("post_processed", "true");
                merged.add(new Document(document.content(), document.source(), document.score(), metadata));
            }
            return List.copyOf(merged);
        }
    }

    private static final class RagEchoRuntime implements AgentRuntime {

        @Override
        public String name() {
            return "gap-rag-runtime";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public Set<org.atmosphere.ai.AiCapability> capabilities() {
            return Set.of(org.atmosphere.ai.AiCapability.TEXT_STREAMING,
                    org.atmosphere.ai.AiCapability.SYSTEM_PROMPT);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            for (var provider : context.contextProviders()) {
                var transformed = provider.transformQuery(context.message());
                var normalized = ContextProvider.normalizeQuery(transformed);
                var shouldRetrieve = ContextProvider.shouldRetrieve(normalized);
                var retrieved = shouldRetrieve ? provider.retrieve(normalized, 5) : List.<ContextProvider.Document>of();
                var filtered = provider.filter(normalized, retrieved);
                var reranked = provider.rerank(normalized, filtered);
                var finalDocs = provider.postProcess(normalized, reranked);
                var citations = finalDocs.stream()
                        .map(ContextProvider::formatCitation)
                        .toList();

                session.sendMetadata("rag.transformedQuery", transformed);
                session.sendMetadata("rag.normalizedQuery", normalized);
                session.sendMetadata("rag.shouldRetrieve", shouldRetrieve);
                session.sendMetadata("rag.retrieved", retrieved.size());
                session.sendMetadata("rag.filtered", filtered.size());
                session.sendMetadata("rag.postProcessed", finalDocs.size());
                session.sendMetadata("rag.citations", citations);
                session.sendMetadata("rag.postProcessedFlag",
                        finalDocs.getFirst().metadata().get("post_processed"));
                session.send(finalDocs.getFirst().content());
            }
            session.complete();
        }
    }

    private static final class TelemetryEchoRuntime implements AgentRuntime {

        @Override
        public String name() {
            return "gap-telemetry-runtime";
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public int priority() {
            return 0;
        }

        @Override
        public void configure(AiConfig.LlmSettings settings) {
        }

        @Override
        public Set<org.atmosphere.ai.AiCapability> capabilities() {
            return Set.of(org.atmosphere.ai.AiCapability.TEXT_STREAMING,
                    org.atmosphere.ai.AiCapability.TOOL_CALLING,
                    org.atmosphere.ai.AiCapability.CONFIDENCE_SCORES);
        }

        @Override
        public void execute(AgentExecutionContext context, StreamingSession session) {
            var elicitation = AiConfidenceElicitation.from(context);
            session.sendMetadata("telemetry.tools", context.tools().size());
            session.sendMetadata("telemetry.history", context.history().size());
            session.sendMetadata("telemetry.confidenceCue",
                    elicitation != null && context.systemPrompt().contains(elicitation.effectiveCue()));
            session.send("telemetry ok {\"confidence\": 0.92}");
            session.complete();
        }
    }

    private static final class MetadataMetrics implements AiMetrics {

        private final StreamingSession session;

        private MetadataMetrics(StreamingSession session) {
            this.session = session;
        }

        @Override
        public void recordStreamingTextUsage(String model, int promptStreamingTexts, int completionStreamingTexts) {
        }

        @Override
        public void recordLatency(String model, Duration timeToFirstStreamingText, Duration totalDuration) {
        }

        @Override
        public void recordCost(String model, BigDecimal cost) {
        }

        @Override
        public void recordToolCall(String model, String toolName, Duration duration, boolean success) {
        }

        @Override
        public void recordError(String model, String errorType) {
            session.sendMetadata("telemetry.error", errorType);
        }

        @Override
        public void recordInputAssembly(String model, String stage,
                                        int approximateTokens, int approximateChars) {
            session.sendMetadata("input." + stage + ".chars", approximateChars);
            session.sendMetadata("input." + stage + ".tokens", approximateTokens);
        }
    }
}
