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
package org.atmosphere.a2a.runtime;

import org.atmosphere.a2a.annotation.AgentSkill;
import org.atmosphere.a2a.annotation.AgentSkillHandler;
import org.atmosphere.a2a.annotation.AgentSkillParam;
import org.atmosphere.a2a.registry.A2aRegistry;
import org.atmosphere.a2a.types.Artifact;
import org.atmosphere.ai.tape.InMemoryTapeStore;
import org.atmosphere.ai.tape.TapeQuery;
import org.atmosphere.ai.tape.TapeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The input of a raw skill run, as admission screens it and the tape records it,
 * comes from the call's named arguments. The coordinator's transports also put
 * one argument's value in the message text, whichever came first in the caller's
 * map: the startup-team's {@code web_search} child, called with
 * {@code Map.of("query", ..., "num_results", "3")}, was taped as
 * {@code user: 3}, and admission screened {@code "3"} instead of the query.
 */
class A2aSkillInputTextTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TaskManager taskManager;

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    static class ResearchAgent {
        @AgentSkill(id = "web_search", name = "Web search", description = "Search the web")
        @AgentSkillHandler
        public void search(TaskContext task, @AgentSkillParam(name = "query") String query,
                           @AgentSkillParam(name = "num_results") String numResults) {
            task.addArtifact(Artifact.text(numResults + " results for " + query));
            task.complete("done");
        }
    }

    @Test
    void aRunWithSeveralArgumentsIsTapedWithAllOfThemNotTheMessagesOne() {
        var input = tapedInput("""
                {"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{
                  "messageId":"m1","role":"user","parts":[{"text":"3"}],
                  "metadata":{"skillId":"web_search","%s":"ceo-run-1"}},
                  "arguments":{"num_results":"3","query":"gardening market"}}}
                """.formatted(TapeSupport.PARENT_RUN_METADATA_KEY));
        assertTrue(input.contains("gardening market"), "the query is part of the taped input: " + input);
        assertTrue(input.contains("num_results"), "every argument is named: " + input);
    }

    @Test
    void theInputIsTheArgumentsOrTheMessageText() throws Exception {
        taskManager = new TaskManager();
        var registry = new A2aRegistry();
        var handler = new A2aProtocolHandler(registry, taskManager,
                registry.buildAgentCard("research", "Research", "1.0", "/a2a"));
        assertEquals("gardening market", handler.skillInputText(MAPPER.readTree("""
                {"message":{"parts":[{"text":"other"}]},"arguments":{"query":"gardening market"}}""")),
                "a single argument is its value");
        assertEquals("{\"query\":\"q\",\"num_results\":\"3\"}", handler.skillInputText(MAPPER.readTree("""
                {"message":{"parts":[{"text":"3"}]},"arguments":{"query":"q","num_results":"3"}}""")),
                "several arguments are all of them");
        assertEquals("plain text", handler.skillInputText(MAPPER.readTree("""
                {"message":{"parts":[{"text":"plain text"}]}}""")),
                "a call with no arguments is its message text");
        assertEquals("plain text", handler.skillInputText(MAPPER.readTree("""
                {"message":{"parts":[{"text":"plain text"}]},"arguments":{}}""")),
                "empty arguments fall back to the message text");
    }

    private String tapedInput(String request) {
        var store = new InMemoryTapeStore();
        var recorder = TapeSupport.install(store);
        try {
            var registry = new A2aRegistry();
            registry.scan(new ResearchAgent());
            taskManager = new TaskManager();
            var card = registry.buildAgentCard("research", "Research", "1.0", "/a2a");
            new A2aProtocolHandler(registry, taskManager, card).handleMessage(request);
        } finally {
            // Drains the writer before the store is read.
            TapeSupport.uninstall(recorder);
        }
        var runs = store.listRuns(new TapeQuery(null, null, 10));
        assertEquals(1, runs.size(), "one child run: " + runs);
        var steps = store.readSteps(runs.getFirst().runId(), 0, 10);
        assertFalse(steps.isEmpty(), "the run has steps");
        var input = steps.stream().filter(s -> "input".equals(s.kind())).findFirst().orElseThrow();
        return input.payload();
    }
}
