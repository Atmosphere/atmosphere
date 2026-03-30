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
package org.atmosphere.ai.test;

import org.atmosphere.ai.AiEvent;
import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.StreamingSession;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LLM-as-judge for evaluating AI agent responses. Uses a configurable
 * {@link AgentRuntime} (typically a cheap/fast model) to score responses
 * produced by the agent under test (typically an expensive model).
 *
 * <pre>{@code
 * var judge = new LlmJudge(cheapRuntime, "gpt-4o-mini");
 * var assertions = AiAssertions.assertThat(response).withJudge(judge);
 * assertions.meetsIntent("Recommends whether to bring an umbrella");
 * }</pre>
 */
public class LlmJudge {

    private final AgentRuntime judgeRuntime;
    private final String judgeModel;
    private final EvalStrategy strategy;

    /**
     * Create a judge with a specific runtime, model, and evaluation strategy.
     */
    public LlmJudge(AgentRuntime judgeRuntime, String judgeModel, EvalStrategy strategy) {
        this.judgeRuntime = judgeRuntime;
        this.judgeModel = judgeModel;
        this.strategy = strategy;
    }

    /**
     * Create a judge with the default evaluation strategy.
     */
    public LlmJudge(AgentRuntime judgeRuntime, String judgeModel) {
        this(judgeRuntime, judgeModel, EvalStrategy.defaultStrategy());
    }

    /**
     * Judge whether the response meets the stated intent.
     *
     * @param userMessage   the original user prompt
     * @param agentResponse the agent's full response text
     * @param intent        what the response should accomplish
     * @return true if the judge determines the response meets the intent
     */
    public boolean meetsIntent(String userMessage, String agentResponse, String intent) {
        var prompt = strategy.buildIntentPrompt(userMessage, agentResponse, intent);
        var judgeResponse = executeJudge(prompt);
        return strategy.parseVerdict(judgeResponse);
    }

    /**
     * Judge whether the response is grounded in tool outputs.
     *
     * @param agentResponse the agent's full response text
     * @param toolOutputs   concatenated tool outputs
     * @return true if the judge determines the response is grounded
     */
    public boolean isGrounded(String agentResponse, String toolOutputs) {
        var prompt = strategy.buildGroundingPrompt(agentResponse, toolOutputs);
        var judgeResponse = executeJudge(prompt);
        return strategy.parseVerdict(judgeResponse);
    }

    /**
     * Score the response quality on multiple dimensions.
     *
     * @param userMessage   the original user prompt
     * @param agentResponse the agent's full response text
     * @return quality scores (relevance, coherence, safety)
     */
    public EvalStrategy.QualityScores scoreQuality(String userMessage, String agentResponse) {
        var prompt = strategy.buildQualityPrompt(userMessage, agentResponse);
        var judgeResponse = executeJudge(prompt);
        return strategy.parseQualityScores(judgeResponse);
    }

    private String executeJudge(String prompt) {
        var context = new AgentExecutionContext(
                prompt, "You are an evaluation judge. Respond with JSON only.",
                judgeModel, null, null, null, null,
                List.of(), null, null, List.of(), Map.of(), List.of(), null);

        var session = new JudgeCapturingSession();
        try {
            judgeRuntime.execute(context, session);
            session.awaitCompletion();
        } catch (Exception e) {
            throw new AssertionError("LLM judge execution failed: " + e.getMessage(), e);
        }

        var response = session.fullText();
        if (response.isBlank()) {
            throw new AssertionError("LLM judge returned empty response");
        }
        return response;
    }

    /**
     * Minimal session capturing text only (no events, no metadata).
     */
    private static class JudgeCapturingSession implements StreamingSession {
        private final StringBuilder text = new StringBuilder();
        private final CountDownLatch done = new CountDownLatch(1);

        @Override public String sessionId() { return "judge"; }
        @Override public void send(String chunk) { text.append(chunk); }
        @Override public void sendMetadata(String key, Object value) { }
        @Override public void progress(String message) { }
        @Override public void complete() { done.countDown(); }
        @Override public void complete(String summary) {
            if (summary != null) { text.setLength(0); text.append(summary); }
            done.countDown();
        }
        @Override public void error(Throwable t) { done.countDown(); }
        @Override public boolean isClosed() { return done.getCount() == 0; }
        @Override public void emit(AiEvent event) {
            if (event instanceof AiEvent.TextDelta d) { text.append(d.text()); }
            else if (event instanceof AiEvent.Complete) { done.countDown(); }
            else if (event instanceof AiEvent.Error) { done.countDown(); }
        }

        void awaitCompletion() {
            try { done.await(30, TimeUnit.SECONDS); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }

        String fullText() { return text.toString(); }
    }
}
