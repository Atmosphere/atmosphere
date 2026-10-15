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

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.llm.ChatCompletionRequest;
import org.atmosphere.ai.llm.LlmClient;
import org.atmosphere.coordinator.fleet.AgentCall;
import org.atmosphere.coordinator.fleet.AgentResult;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An evaluator attached to one application judges with that application's
 * runtime, client and model while another application runs in the JVM: it no
 * longer reads the JVM-wide settings, which belong to no application then.
 */
class LlmResultEvaluatorApplicationTest {

    @Test
    void anAttachedEvaluatorJudgesWithItsApplicationsClientAndModel() {
        var first = AiEnvironment.create(key -> null);
        var second = AiEnvironment.create(key -> null);
        try {
            var firstClient = new JudgeClient();
            var secondClient = new JudgeClient();
            first.configure("remote", "judge-first", "key-first", "http://127.0.0.1:9/first/v1");
            first.installClient(firstClient);
            second.configure("remote", "judge-second", "key-second", "http://127.0.0.1:9/second/v1");
            second.installClient(secondClient);

            var evaluator = new LlmResultEvaluator(Duration.ofSeconds(10));
            evaluator.attach(second);
            var evaluation = evaluator.evaluate(
                    new AgentResult("research", "summarize", "answer", Map.of(), Duration.ZERO, true),
                    new AgentCall("research", "summarize", Map.of("topic", "Atmosphere")));

            assertFalse(evaluation.skipped(), "the judge answered: " + evaluation.reason());
            assertEquals("judge-second", evaluation.metadata().get("model"),
                    "the evaluator reports its application's model");
            assertEquals(List.of("judge-second"), secondClient.models,
                    "the judge question goes through the application's own client and model");
            assertTrue(firstClient.models.isEmpty(), "the other application's client is not used");
        } finally {
            second.close();
            first.close();
        }
    }

    /** Answers every judge question with a passing score and records the model it was asked for. */
    private static final class JudgeClient implements LlmClient {

        private final List<String> models = new CopyOnWriteArrayList<>();

        @Override
        public void streamChatCompletion(ChatCompletionRequest request, StreamingSession session) {
            models.add(request.model());
            session.send("{\"score\": 9, \"reason\": \"fine\"}");
            session.complete();
        }
    }
}
