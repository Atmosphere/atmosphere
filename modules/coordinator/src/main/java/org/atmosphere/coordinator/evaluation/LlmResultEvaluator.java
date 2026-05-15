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
package org.atmosphere.coordinator.evaluation;

import org.atmosphere.ai.AgentExecutionContext;
import org.atmosphere.ai.AgentRuntime;
import org.atmosphere.ai.AgentRuntimeResolver;
import org.atmosphere.ai.AiConfig;
import org.atmosphere.ai.InMemoryConversationMemory;
import org.atmosphere.ai.PromptLoader;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * {@link ResultEvaluator} that uses the active {@link AgentRuntime} (LangChain4j,
 * Spring AI, ADK, Embabel, Koog, or Built-in) to judge agent response quality.
 *
 * <p>Sends a structured judge prompt to whatever LLM runtime is on the classpath.
 * Parses a 0-10 score from the response and normalizes to 0.0-1.0. No hardcoded
 * rules — the LLM evaluates helpfulness, accuracy, and completeness.</p>
 *
 * <p>Requires an LLM API key to be configured. Falls back to pass-through
 * (score 1.0) when no runtime is available or the API call fails.</p>
 */
public final class LlmResultEvaluator implements ResultEvaluator {

    private static final Logger logger = LoggerFactory.getLogger(LlmResultEvaluator.class);
    private static final Pattern SCORE_PATTERN = Pattern.compile("(?:score|rating)[\":\\s]*(\\d+(?:\\.\\d+)?)", Pattern.CASE_INSENSITIVE);
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\s*(\\d+(?:\\.\\d+)?)\\s*(?:/\\s*10)?\\s*$", Pattern.MULTILINE);

    private static final String DEFAULT_SKILL_NAME = "llm-judge";
    /** Minimum history for the runtime: system prompt + user message. */
    private static final int JUDGE_HISTORY_SIZE = 2;

    private static final String FALLBACK_PROMPT = "You are an AI quality judge. "
            + "Score the response 0-10. Respond with JSON: {\"score\": N, \"reason\": \"explanation\"}";

    private volatile AgentRuntime runtime;
    private volatile String systemPrompt;
    private final Duration timeout;
    private final String skillName;

    /** Default: 15-second timeout for judge calls, skill name "llm-judge". */
    public LlmResultEvaluator() {
        this(Duration.ofSeconds(15));
    }

    public LlmResultEvaluator(Duration timeout) {
        this(timeout, DEFAULT_SKILL_NAME);
    }

    public LlmResultEvaluator(Duration timeout, String skillName) {
        this.timeout = timeout;
        this.skillName = skillName;
    }

    private String systemPrompt() {
        if (systemPrompt != null) {
            return systemPrompt;
        }
        var loaded = PromptLoader.loadSkill(skillName);
        systemPrompt = loaded != null ? loaded : FALLBACK_PROMPT;
        return systemPrompt;
    }

    @Override
    public String name() {
        return skillName;
    }

    @Override
    public Evaluation evaluate(AgentResult result, AgentCall originalCall) {
        if (!result.success()) {
            return Evaluation.fail(0.0, "Agent call failed: " + result.text());
        }

        var rt = resolveRuntime();
        if (rt == null) {
            logger.debug("No AgentRuntime available, skipping LLM evaluation");
            return Evaluation.pass(1.0, "No LLM runtime available, skipped",
                    Map.of("skipped", true));
        }

        var judgePrompt = buildJudgePrompt(result, originalCall);
        var settings = AiConfig.get();
        var model = settings != null ? settings.model() : null;

        try {
            var response = callLlm(rt, judgePrompt);
            return parseScore(response, result, originalCall, judgePrompt, model);
        } catch (Exception e) {
            logger.debug("LLM evaluation failed for agent '{}': {}",
                    result.agentName(), e.getMessage());
            return Evaluation.pass(1.0, "LLM judge error: " + e.getMessage(),
                    Map.of("error", e.getMessage(),
                            "judgePrompt", judgePrompt,
                            "agent", result.agentName(),
                            "skill", originalCall.skill(),
                            "model", nullToEmpty(model)));
        }
    }

    static String buildJudgePrompt(AgentResult result, AgentCall originalCall) {
        return String.format(
                "Evaluate this agent response.\n\nSkill: %s\nArguments: %s\n\nResponse:\n%s",
                originalCall.skill(),
                originalCall.args(),
                truncate(result.text(), 2000));
    }

    private String callLlm(AgentRuntime rt, String message) {
        var settings = AiConfig.get();
        var model = settings != null ? settings.model() : null;
        var context = new AgentExecutionContext(
                message, systemPrompt(), model, skillName,
                "eval-" + System.nanoTime(), null, null,
                List.of(), null, new InMemoryConversationMemory(JUDGE_HISTORY_SIZE),
                List.of(), Map.of(), List.of(), null, null);
        return rt.generate(context, timeout);
    }

    static Evaluation parseScore(
            String response, AgentResult result, AgentCall originalCall, String judgePrompt, String model) {
        if (response == null || response.isBlank()) {
            return Evaluation.pass(1.0, "Empty judge response",
                    metadata(judgePrompt, "", null, "empty", result, originalCall, model));
        }

        var cleaned = stripFences(response);
        // Try JSON score pattern: {"score": 8, ...}
        var matcher = SCORE_PATTERN.matcher(cleaned);
        if (matcher.find()) {
            var raw = Double.parseDouble(matcher.group(1));
            var score = Math.min(1.0, Math.max(0.0, raw / 10.0));
            var reason = extractReason(cleaned);
            var metadata = metadata(judgePrompt, response, raw, "json", result, originalCall, model);
            return score >= 0.5
                    ? Evaluation.pass(score, reason, metadata)
                    : Evaluation.fail(score, reason, metadata);
        }

        // Try bare number: "8" or "8/10"
        var bareMatcher = BARE_NUMBER.matcher(cleaned);
        if (bareMatcher.find()) {
            var raw = Double.parseDouble(bareMatcher.group(1));
            var score = raw > 1.0 ? raw / 10.0 : raw;
            score = Math.min(1.0, Math.max(0.0, score));
            var metadata = metadata(judgePrompt, response, raw, "bare", result, originalCall, model);
            return score >= 0.5
                    ? Evaluation.pass(score, cleaned.trim(), metadata)
                    : Evaluation.fail(score, cleaned.trim(), metadata);
        }

        logger.debug("Could not parse score from LLM response: {}", truncate(cleaned, 200));
        return Evaluation.pass(0.7, "Unparseable judge response: " + truncate(cleaned, 100),
                metadata(judgePrompt, response, null, "fallback", result, originalCall, model));
    }

    static String stripFences(String response) {
        var cleaned = response.trim();
        if (cleaned.startsWith("```")) {
            cleaned = cleaned.replaceFirst("^```(?:json)?\\s*", "")
                    .replaceFirst("\\s*```$", "")
                    .trim();
        }
        return cleaned;
    }

    private static String extractReason(String response) {
        var reasonMatcher = Pattern.compile("\"reason\"\\s*:\\s*\"([^\"]+)\"").matcher(response);
        return reasonMatcher.find() ? reasonMatcher.group(1) : response.trim();
    }

    private static Map<String, Object> metadata(
            String judgePrompt,
            String judgeResponse,
            Double rawScore,
            String scoreSource,
            AgentResult result,
            AgentCall originalCall,
            String model) {
        var metadata = new java.util.HashMap<String, Object>();
        metadata.put("judgePrompt", judgePrompt);
        metadata.put("judgeResponse", judgeResponse);
        metadata.put("scoreSource", scoreSource);
        metadata.put("agent", result.agentName());
        metadata.put("skill", originalCall.skill());
        metadata.put("model", nullToEmpty(model));
        if (rawScore != null) {
            metadata.put("rawScore", rawScore);
        }
        return Map.copyOf(metadata);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private AgentRuntime resolveRuntime() {
        if (runtime != null) {
            return runtime;
        }
        try {
            var all = AgentRuntimeResolver.resolveAll();
            if (!all.isEmpty()) {
                var rt = all.getFirst();
                var settings = AiConfig.get();
                if (settings == null) {
                    settings = AiConfig.fromEnvironment();
                }
                if (settings != null) {
                    rt.configure(settings);
                }
                runtime = rt;
                logger.info("LlmResultEvaluator using runtime: {}", rt.name());
                return rt;
            }
        } catch (Exception e) {
            logger.debug("Failed to resolve AgentRuntime: {}", e.getMessage());
        }
        return null;
    }

    private static String truncate(String text, int maxLen) {
        return text != null && text.length() > maxLen
                ? text.substring(0, maxLen) + "..." : (text != null ? text : "");
    }

}
