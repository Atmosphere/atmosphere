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

import org.atmosphere.ai.llm.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * {@link SummarizingCompaction} whose summary comes from a one-shot LLM
 * completion on the runtime of its application ({@link AiEnvironment#runtimes()}) —
 * the same completion pattern
 * {@link org.atmosphere.ai.memory.LongTermMemoryInterceptor}'s extraction
 * strategies use. Selected via the
 * {@code org.atmosphere.ai.compaction=summarizing} init-param (see
 * {@link CompactionConfig}).
 *
 * <p>Fail-open by design: on any error — no runtime configured, model
 * failure, timeout, blank output — the compaction falls back to
 * {@link SummarizingCompaction#summarize(List) the local concat-and-truncate
 * summary} so history compaction never blocks or breaks a conversation. The
 * failure is logged at DEBUG only.</p>
 */
public class LlmSummarizingCompaction extends SummarizingCompaction {

    private static final Logger logger = LoggerFactory.getLogger(LlmSummarizingCompaction.class);

    private static final long SUMMARY_TIMEOUT_SECONDS = 30;

    private static final String SUMMARY_PROMPT = """
            Summarize the following conversation concisely, preserving key facts,
            decisions, names, and open questions. Respond with the summary text only.

            Conversation:
            %s""";

    /** The messages kept verbatim when no recent window is configured. */
    static final int DEFAULT_RECENT_WINDOW = 6;

    private final Supplier<AgentRuntime> runtimeSupplier;

    /**
     * A compaction that summarizes on the runtime of {@link AiEnvironment#current()}
     * when it summarizes, keeping {@value #DEFAULT_RECENT_WINDOW} messages verbatim.
     */
    public LlmSummarizingCompaction() {
        this(DEFAULT_RECENT_WINDOW);
    }

    /**
     * A compaction that summarizes on the runtime of {@link AiEnvironment#current()}
     * when it summarizes: the application bound to the calling thread, else the
     * only one running, else the process default. {@link CompactionConfig}
     * gives an application's memory its own application's runtime instead.
     *
     * @param recentWindowSize the messages kept verbatim
     */
    public LlmSummarizingCompaction(int recentWindowSize) {
        this(recentWindowSize, () -> AiEnvironment.current().runtimes().resolve());
    }

    /**
     * Inject the runtime source: {@link CompactionConfig} passes the runtime
     * of the memory's application, and tests pin the fail-open and success
     * paths without mutating any application's runtimes.
     */
    LlmSummarizingCompaction(int recentWindowSize, Supplier<AgentRuntime> runtimeSupplier) {
        super(recentWindowSize);
        this.runtimeSupplier = runtimeSupplier;
    }

    @Override
    protected String summarize(List<ChatMessage> oldMessages) {
        try {
            var summary = llmSummarize(oldMessages);
            if (summary != null && !summary.isBlank()) {
                return summary;
            }
            logger.debug("LLM summarization returned no text — falling back to local summary");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.debug("LLM summarization interrupted — falling back to local summary");
        } catch (Exception e) {
            logger.debug("LLM summarization failed — falling back to local summary: {}",
                    e.toString());
        }
        return super.summarize(oldMessages);
    }

    private String llmSummarize(List<ChatMessage> oldMessages) throws InterruptedException {
        var conversation = new StringBuilder();
        for (var msg : oldMessages) {
            conversation.append(msg.role()).append(": ").append(msg.content()).append('\n');
        }

        var runtime = runtimeSupplier.get();
        // Summarization has no tool list so HITL gating is a no-op here; use the
        // 15-arg form with a null ApprovalStrategy explicitly to stay off the
        // deprecated 14-arg shim (mirrors OnSessionCloseStrategy.extractFacts).
        var context = new AgentExecutionContext(
                SUMMARY_PROMPT.formatted(conversation),
                "You are a conversation summarizer. Respond with the summary only.",
                null, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null,
                (org.atmosphere.ai.approval.ApprovalStrategy) null);

        var text = new StringBuilder();
        var failed = new AtomicBoolean(false);
        var latch = new CountDownLatch(1);

        runtime.execute(context, new StreamingSession() {
            @Override public String sessionId() { return "compaction-summary"; }
            @Override public void send(String chunk) { text.append(chunk); }
            @Override public void sendMetadata(String key, Object value) { }
            @Override public void progress(String message) { }
            @Override public void complete() { latch.countDown(); }
            @Override public void complete(String summary) {
                if (summary != null) { text.setLength(0); text.append(summary); }
                latch.countDown();
            }
            @Override public void error(Throwable t) {
                failed.set(true);
                latch.countDown();
            }
            @Override public boolean isClosed() { return latch.getCount() == 0; }
        });

        if (!latch.await(SUMMARY_TIMEOUT_SECONDS, TimeUnit.SECONDS) || failed.get()) {
            return null;
        }
        return text.toString();
    }
}
