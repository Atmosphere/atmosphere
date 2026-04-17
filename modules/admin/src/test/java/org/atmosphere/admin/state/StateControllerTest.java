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
package org.atmosphere.admin.state;

import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.state.FileSystemAgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StateControllerTest {

    @Test
    void listFactsReturnsStoredEntries(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);

        controller.addFact("pierre", "chef", "prefers bun over npm");
        controller.addFact("pierre", "chef", "trains at 7 AM");

        var facts = controller.listFacts("pierre", "chef");
        assertEquals(2, facts.size());
        assertEquals("prefers bun over npm", facts.get(0).get("content"));
        assertTrue(((String) facts.get(0).get("id")).length() > 0);
        assertTrue(((String) facts.get(0).get("createdAt")).length() > 0);
    }

    @Test
    void removeFactDeletesIt(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);

        controller.addFact("pierre", "chef", "keep me");
        var toRemove = controller.addFact("pierre", "chef", "remove me");
        controller.removeFact("pierre", "chef", (String) toRemove.get("id"));

        var facts = controller.listFacts("pierre", "chef");
        assertEquals(1, facts.size());
        assertEquals("keep me", facts.get(0).get("content"));
    }

    @Test
    void dailyNotesParseIsoDate(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);
        controller.addDailyNote("pierre", "chef", "ran intervals");

        var notes = controller.listDailyNotes("pierre", "chef", LocalDate.now().toString());
        assertEquals(1, notes.size());
        assertEquals("ran intervals", notes.get(0).get("content"));
    }

    @Test
    void conversationEndpointReturnsTranscript(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);
        state.appendConversation("pierre", "sess-1", ChatMessage.user("hi"));
        state.appendConversation("pierre", "sess-1", ChatMessage.assistant("hello"));

        var conv = controller.getConversation("pierre", "sess-1");
        assertEquals(2, conv.size());
        assertEquals("user", conv.get(0).get("role"));
        assertEquals("hi", conv.get(0).get("content"));
        assertEquals("assistant", conv.get(1).get("role"));
        assertFalse(conv.get(0).containsKey("toolCallId"));
    }

    @Test
    void clearConversationRemovesIt(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);
        state.appendConversation("pierre", "sess-1", ChatMessage.user("hi"));

        controller.clearConversation("pierre", "sess-1");
        assertTrue(controller.getConversation("pierre", "sess-1").isEmpty());
    }

    @Test
    void getRulesReturnsAssembledPrompt(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("IDENTITY.md"), "I am Pierre", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "be direct", StandardCharsets.UTF_8);

        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);

        var rules = controller.getRules("pierre", "chef");
        assertEquals("I am Pierre", rules.get("identity"));
        assertEquals("be direct", rules.get("operatingRules"));
        assertTrue(((String) rules.get("systemPrompt")).contains("## Identity"));
    }

    @Test
    void workspaceRootReturnsPath(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);

        var result = controller.getWorkspaceRoot("pierre");
        assertEquals(root.toAbsolutePath().normalize().toString(), result.get("workspaceRoot"));
    }

    @Test
    void blankArgumentsRejected(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var controller = new StateController(state);

        assertThrows(IllegalArgumentException.class, () -> controller.addFact("pierre", "", "x"));
        assertThrows(IllegalArgumentException.class, () -> controller.addFact("", "chef", "x"));
        assertThrows(IllegalArgumentException.class, () -> controller.addFact("pierre", "chef", ""));
        assertThrows(IllegalArgumentException.class, () -> controller.getConversation("pierre", ""));
    }
}
