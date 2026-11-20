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
import org.atmosphere.ai.tape.TapeStatus;
import org.atmosphere.ai.tape.TapeSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A raw {@code @AgentSkill} run dispatched under a coordinator is taped with
 * the task's real terminal state. A skill that fails its task through
 * {@link TaskContext#fail} without throwing (the startup-team's
 * {@code web_search} child with no search key) used to be taped as
 * {@link TapeStatus#COMPLETED}, because only an exception turned the run's
 * status to an error (Correctness Invariant #5).
 */
class A2aSkillTapeStatusTest {

    private TaskManager taskManager;

    @AfterEach
    void tearDown() {
        if (taskManager != null) {
            taskManager.shutdown();
        }
    }

    static class SearchAgent {
        @AgentSkill(id = "web_search", name = "Web search", description = "Search the web")
        @AgentSkillHandler
        public void search(TaskContext task, @AgentSkillParam(name = "query") String query) {
            // No search key: fail the task, return normally.
            task.fail("no search API key configured");
        }

        @AgentSkill(id = "echo", name = "Echo", description = "Echo")
        @AgentSkillHandler
        public void echo(TaskContext task, @AgentSkillParam(name = "query") String query) {
            task.addArtifact(Artifact.text("echo: " + query));
            task.complete("done");
        }
    }

    @Test
    void aSkillThatFailsItsTaskWithoutThrowingIsTapedAsAnError() {
        assertEquals(TapeStatus.ERROR, tapedStatusOf("web_search"),
                "a FAILED task must not be taped as COMPLETED");
    }

    @Test
    void aSkillThatCompletesItsTaskIsTapedAsCompleted() {
        assertEquals(TapeStatus.COMPLETED, tapedStatusOf("echo"));
    }

    @Test
    void theTaskStateMapsOntoTheTapeStatus() {
        taskManager = new TaskManager();
        var failed = taskManager.createTask("c1");
        failed.fail("x");
        var cancelled = taskManager.createTask("c2");
        cancelled.cancel("stopped");
        var open = taskManager.createTask("c3");

        assertEquals(TapeStatus.ERROR, A2aProtocolHandler.tapeStatus(failed, true));
        assertEquals(TapeStatus.CANCELLED, A2aProtocolHandler.tapeStatus(cancelled, true));
        assertEquals(TapeStatus.COMPLETED, A2aProtocolHandler.tapeStatus(open, true),
                "a task the skill left open is taped from whether the handler threw");
        assertEquals(TapeStatus.ERROR, A2aProtocolHandler.tapeStatus(open, false));
    }

    private TapeStatus tapedStatusOf(String skillId) {
        var store = new InMemoryTapeStore();
        var recorder = TapeSupport.install(store);
        try {
            var registry = new A2aRegistry();
            registry.scan(new SearchAgent());
            taskManager = new TaskManager();
            var card = registry.buildAgentCard("test-agent", "Test", "1.0", "/a2a");
            var handler = new A2aProtocolHandler(registry, taskManager, card);

            handler.handleMessage("""
                    {"jsonrpc":"2.0","id":1,"method":"SendMessage","params":{"message":{
                      "messageId":"m1","role":"user","parts":[{"kind":"text","text":"gardening market"}],
                      "metadata":{"skillId":"%s","%s":"ceo-run-1"}}}}
                    """.formatted(skillId, TapeSupport.PARENT_RUN_METADATA_KEY));
        } finally {
            // Drains the writer before the store is read.
            TapeSupport.uninstall(recorder);
        }
        var runs = store.listRuns(new TapeQuery(null, null, 10));
        assertEquals(1, runs.size(), "the child dispatch is taped as one run: " + runs);
        assertEquals("ceo-run-1", runs.getFirst().parentRunId());
        return runs.getFirst().status();
    }
}
