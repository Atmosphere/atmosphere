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
package org.atmosphere.ai.decision.typesafe;

import org.atmosphere.ai.AiConfidence;
import org.atmosphere.ai.TokenUsage;
import org.atmosphere.ai.decision.Answer;
import org.atmosphere.ai.decision.DecisionRequest;
import org.atmosphere.ai.decision.Question;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

/**
 * The JSON shapes of {@code POST /v1/systemone} and {@code GET /v1/models}, as
 * documented at {@code https://docs.typesafe.ai/api.md} and
 * {@code https://docs.typesafe.ai/models.md}.
 *
 * <p>Decoding is strict: a field the documentation marks required that is
 * missing or of the wrong JSON type is {@link Answer.Failed.Reason#UNPARSEABLE}; a value
 * outside what the question allows (a choice that is not an option, a
 * probability outside {@code [0, 1]}, a distribution that does not cover exactly
 * the options or levels, a choice that is not the most likely option, a score
 * that disagrees with its own distribution) is
 * {@link Answer.Failed.Reason#INVALID_ANSWER}. Nothing is guessed.</p>
 */
final class TypesafeWire {

    private static final Logger logger = LoggerFactory.getLogger(TypesafeWire.class);

    static final ObjectMapper MAPPER = JsonMapper.builder().build();

    /** Slack on "the probabilities sum to 1" — the documentation says "approximately". */
    static final double SUM_TOLERANCE = 0.02;

    /** Slack between the returned score and {@code Σ i·p(i)} of the returned levels. */
    static final double SCORE_TOLERANCE = 0.05;

    private TypesafeWire() {
    }

    /** The {@code POST /v1/systemone} body for {@code request} answered by {@code model}. */
    static byte[] encodeRequest(String model, DecisionRequest request) {
        var root = MAPPER.createObjectNode();
        root.put("state", request.state());
        root.put("model", model);
        var questions = root.putObject("questions");
        for (var entry : request.questions().entrySet()) {
            var node = questions.putObject(entry.getKey());
            encodeQuestion(node, entry.getValue());
        }
        return MAPPER.writeValueAsBytes(root);
    }

    private static void encodeQuestion(ObjectNode node, Question question) {
        switch (question) {
            case Question.Noul noul -> {
                node.put("type", "noul");
                node.put("instructions", noul.instructions());
                // Both criteria are optional, each on its own (NoulCriteria in
                // the SDK reference); an absent one is left out.
                if (noul.whenTrue() != null || noul.whenFalse() != null) {
                    var criteria = node.putObject("criteria");
                    if (noul.whenTrue() != null) {
                        criteria.put("true", noul.whenTrue());
                    }
                    if (noul.whenFalse() != null) {
                        criteria.put("false", noul.whenFalse());
                    }
                }
            }
            case Question.Choice choice -> {
                node.put("type", "choice");
                node.put("instructions", choice.instructions());
                var criteria = node.putObject("criteria");
                for (var option : choice.options().entrySet()) {
                    // "use null when an option needs no extra detail"
                    if (option.getValue().isBlank()) {
                        criteria.putNull(option.getKey());
                    } else {
                        criteria.put(option.getKey(), option.getValue());
                    }
                }
            }
            case Question.Score score -> {
                node.put("type", "score");
                node.put("instructions", score.instructions());
                var criteria = node.putArray("criteria");
                for (var level : score.levels()) {
                    criteria.add(level);
                }
            }
        }
    }

    /**
     * A decoded {@code 200} reply.
     *
     * @param model   the versioned model id that answered ({@code null} when the
     *                reply did not say)
     * @param answers one answer per requested id, in request order
     * @param usage   token usage, when the reply carried a well-formed one
     */
    record Reply(String model, Map<String, Answer> answers, Optional<TokenUsage> usage) {
    }

    /**
     * Decode a {@code 200} body. A body that is not a JSON object with an
     * {@code answers} object fails every question as UNPARSEABLE.
     */
    static Reply decodeReply(DecisionRequest request, byte[] body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            return failAll(request, Answer.Failed.Reason.UNPARSEABLE,
                    "response is not JSON: " + e.getOriginalMessage());
        }
        if (root == null || !root.isObject()) {
            return failAll(request, Answer.Failed.Reason.UNPARSEABLE, "response is not a JSON object");
        }
        var answers = root.get("answers");
        if (answers == null || !answers.isObject()) {
            return failAll(request, Answer.Failed.Reason.UNPARSEABLE, "response has no 'answers' object");
        }
        var model = root.get("model");
        var decoded = new LinkedHashMap<String, Answer>();
        for (var entry : request.questions().entrySet()) {
            var id = entry.getKey();
            decoded.put(id, decodeAnswer(id, entry.getValue(), answers.get(id)));
        }
        var modelId = model != null && model.isString() ? model.stringValue() : null;
        return new Reply(modelId, decoded, usage(root.get("usage"), modelId));
    }

    private static Reply failAll(DecisionRequest request, Answer.Failed.Reason reason, String detail) {
        var failed = new LinkedHashMap<String, Answer>();
        for (var id : request.questions().keySet()) {
            failed.put(id, new Answer.Failed(id, reason, detail));
        }
        return new Reply(null, failed, Optional.empty());
    }

    private static Optional<TokenUsage> usage(JsonNode usage, String model) {
        if (usage == null || !usage.isObject()) {
            return Optional.empty();
        }
        var input = usage.get("input_tokens");
        var output = usage.get("output_tokens");
        if (input == null || output == null || !input.canConvertToLong() || !output.canConvertToLong()
                || !input.isIntegralNumber() || !output.isIntegralNumber()
                || input.longValue() < 0 || output.longValue() < 0) {
            return Optional.empty();
        }
        var in = input.longValue();
        var out = output.longValue();
        return Optional.of(TokenUsage.of(in, out, in + out, model));
    }

    static Answer decodeAnswer(String id, Question question, JsonNode node) {
        if (node == null || node.isNull()) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE, "no answer for this question");
        }
        if (!node.isObject()) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE, "answer is not a JSON object");
        }
        var expected = switch (question) {
            case Question.Noul ignored -> "noul";
            case Question.Choice ignored -> "choice";
            case Question.Score ignored -> "score";
        };
        var type = node.get("type");
        if (type != null && !(type.isString() && expected.equals(type.stringValue()))) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "answer type " + type + " for a " + expected + " question");
        }
        return switch (question) {
            case Question.Noul ignored -> noul(id, node);
            case Question.Choice choice -> choice(id, choice, node);
            case Question.Score score -> score(id, score, node);
        };
    }

    /**
     * {@code {"type":"noul","noul":p}}: {@code p} is P(yes) and carries no
     * confidence, so the confidence is {@code |2p - 1|} — the normalised margin
     * of the two-value distribution — labelled
     * {@link AiConfidence.Source#PROVIDER_DISTRIBUTION}. The value is
     * {@code p >= 0.5}; at exactly {@code 0.5} the margin is {@code 0}.
     */
    private static Answer noul(String id, JsonNode node) {
        var field = node.get("noul");
        if (field == null || !field.isNumber()) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE, "noul answer has no numeric 'noul'");
        }
        var p = field.doubleValue();
        if (!isProbability(p)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, "noul " + p + " is outside [0, 1]");
        }
        return new Answer.Noul(id, p >= 0.5, OptionalDouble.of(p), confidence(Math.abs(2.0 * p - 1.0)));
    }

    private static Answer choice(String id, Question.Choice question, JsonNode node) {
        var choiceNode = node.get("choice");
        var probabilitiesNode = node.get("probabilities");
        var confidenceNode = node.get("confidence");
        if (choiceNode == null || !choiceNode.isString()
                || probabilitiesNode == null || !probabilitiesNode.isObject()
                || confidenceNode == null || !confidenceNode.isNumber()) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE,
                    "choice answer needs a string 'choice', an object 'probabilities' and a numeric 'confidence'");
        }
        var choice = choiceNode.stringValue();
        if (!question.options().containsKey(choice)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "choice '" + choice + "' is not one of the options");
        }
        var probabilities = new LinkedHashMap<String, Double>();
        for (var option : question.options().keySet()) {
            var p = probabilitiesNode.get(option);
            if (p == null || !p.isNumber()) {
                return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                        "probabilities has no number for option '" + option + "'");
            }
            probabilities.put(option, p.doubleValue());
        }
        if (probabilitiesNode.size() != question.options().size()) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "probabilities names " + probabilitiesNode.size() + " options, the question has "
                            + question.options().size());
        }
        var invalid = invalidDistribution(probabilities.values());
        if (invalid != null) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, invalid);
        }
        var chosen = probabilities.get(choice);
        for (var entry : probabilities.entrySet()) {
            if (entry.getValue() > chosen) {
                return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                        "choice '" + choice + "' (p=" + chosen + ") is not the most likely option; '"
                                + entry.getKey() + "' has p=" + entry.getValue());
            }
        }
        var confidence = confidenceNode.doubleValue();
        if (!isProbability(confidence)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "confidence " + confidence + " is outside [0, 1]");
        }
        return new Answer.Choice(id, choice, probabilities, confidence(confidence));
    }

    private static Answer score(String id, Question.Score question, JsonNode node) {
        var scoreNode = node.get("score");
        var probabilitiesNode = node.get("probabilities");
        var confidenceNode = node.get("confidence");
        if (scoreNode == null || !scoreNode.isNumber()
                || probabilitiesNode == null || !probabilitiesNode.isObject()
                || confidenceNode == null || !confidenceNode.isNumber()) {
            return new Answer.Failed(id, Answer.Failed.Reason.UNPARSEABLE,
                    "score answer needs a numeric 'score', an object 'probabilities' and a numeric 'confidence'");
        }
        var levels = question.levels().size();
        var probabilities = new LinkedHashMap<Integer, Double>();
        for (var level = 0; level < levels; level++) {
            var p = probabilitiesNode.get(Integer.toString(level));
            if (p == null || !p.isNumber()) {
                return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                        "probabilities has no number for level " + level);
            }
            probabilities.put(level, p.doubleValue());
        }
        if (probabilitiesNode.size() != levels) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "probabilities names " + probabilitiesNode.size() + " levels, the rubric has " + levels);
        }
        var invalid = invalidDistribution(probabilities.values());
        if (invalid != null) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER, invalid);
        }
        var score = scoreNode.doubleValue();
        if (!Double.isFinite(score) || score < 0.0 || score > levels - 1) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "score " + score + " is outside [0, " + (levels - 1) + "]");
        }
        var expected = 0.0;
        for (var entry : probabilities.entrySet()) {
            expected += entry.getKey() * entry.getValue();
        }
        if (Math.abs(expected - score) > SCORE_TOLERANCE) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "score " + score + " disagrees with its distribution (expected level " + expected + ")");
        }
        var confidence = confidenceNode.doubleValue();
        if (!isProbability(confidence)) {
            return new Answer.Failed(id, Answer.Failed.Reason.INVALID_ANSWER,
                    "confidence " + confidence + " is outside [0, 1]");
        }
        return new Answer.Score(id, score, probabilities, confidence(confidence));
    }

    private static String invalidDistribution(Iterable<Double> probabilities) {
        var sum = 0.0;
        for (var p : probabilities) {
            if (!isProbability(p)) {
                return "probability " + p + " is outside [0, 1]";
            }
            sum += p;
        }
        if (Math.abs(sum - 1.0) > SUM_TOLERANCE) {
            return "probabilities sum to " + sum + ", not 1";
        }
        return null;
    }

    private static boolean isProbability(double value) {
        return Double.isFinite(value) && value >= 0.0 && value <= 1.0;
    }

    private static AiConfidence confidence(double aggregate) {
        return new AiConfidence(OptionalDouble.of(aggregate), List.of(),
                AiConfidence.Source.PROVIDER_DISTRIBUTION);
    }

    /**
     * Whether a {@code GET /v1/models} body is the documented shape: an object
     * whose {@code models} is an array of objects each naming a {@code name}.
     */
    static boolean isModelList(byte[] body) {
        JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (JacksonException e) {
            logger.debug("GET /v1/models body is not JSON", e);
            return false;
        }
        if (root == null || !root.isObject()) {
            return false;
        }
        var models = root.get("models");
        if (models == null || !models.isArray()) {
            return false;
        }
        for (var model : models) {
            var name = model.get("name");
            if (name == null || !name.isString()) {
                return false;
            }
        }
        return true;
    }

    /**
     * A short message from an error body. Observed live (2026-10-01, 401 and
     * 403): {@code {"detail":{"error_type":..., "message":...}}}. A string
     * {@code detail} or an array of {@code {msg}} entries is read too; anything
     * else is the body itself, truncated.
     */
    static String errorMessage(byte[] body) {
        if (body == null || body.length == 0) {
            return "";
        }
        try {
            var root = MAPPER.readTree(body);
            var detail = root == null ? null : root.get("detail");
            if (detail != null) {
                if (detail.isString()) {
                    return truncate(detail.stringValue());
                }
                if (detail.isObject()) {
                    var type = detail.get("error_type");
                    var message = detail.get("message");
                    var text = (type != null && type.isString() ? type.stringValue() + ": " : "")
                            + (message != null && message.isString() ? message.stringValue() : "");
                    if (!text.isBlank()) {
                        return truncate(text);
                    }
                }
                if (detail.isArray() && !detail.isEmpty()) {
                    var first = detail.get(0);
                    var msg = first.get("msg");
                    if (msg != null && msg.isString()) {
                        return truncate(msg.stringValue());
                    }
                }
            }
        } catch (JacksonException e) {
            logger.trace("Error body is not JSON; reporting it as text", e);
        }
        return truncate(new String(body, StandardCharsets.UTF_8));
    }

    private static String truncate(String text) {
        var clean = text.replaceAll("[\\r\\n\\t]+", " ").strip();
        return clean.length() <= 300 ? clean : clean.substring(0, 300) + "…";
    }
}
