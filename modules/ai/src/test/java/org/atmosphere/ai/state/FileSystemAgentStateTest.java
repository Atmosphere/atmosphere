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
package org.atmosphere.ai.state;

import org.atmosphere.ai.llm.ChatMessage;
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

class FileSystemAgentStateTest {

    @Test
    void appendsAndReadsConversation(@TempDir Path root) {
        var state = new FileSystemAgentState(root);

        state.appendConversation("agent1", "sess1", ChatMessage.user("hi"));
        state.appendConversation("agent1", "sess1", ChatMessage.assistant("hello"));

        var history = state.getConversation("agent1", "sess1");
        assertEquals(2, history.size());
        assertEquals("user", history.get(0).role());
        assertEquals("hi", history.get(0).content());
        assertEquals("assistant", history.get(1).role());
        assertEquals("hello", history.get(1).content());
    }

    @Test
    void survivesRestart(@TempDir Path root) {
        var original = new FileSystemAgentState(root);
        original.appendConversation("agent1", "sess1", ChatMessage.user("persist me"));

        // "Restart" — construct a new instance over the same directory
        var restarted = new FileSystemAgentState(root);
        var history = restarted.getConversation("agent1", "sess1");

        assertEquals(1, history.size());
        assertEquals("persist me", history.get(0).content());
    }

    @Test
    void clearConversationRemovesFile(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        state.appendConversation("agent1", "sess1", ChatMessage.user("hi"));
        state.clearConversation("agent1", "sess1");

        assertTrue(state.getConversation("agent1", "sess1").isEmpty());
    }

    @Test
    void addFactReturnsEntryWithId(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var entry = state.addFact("u1", "a1", "ChefFamille prefers bun");

        assertFalse(entry.id().isBlank());
        assertEquals("ChefFamille prefers bun", entry.content());
    }

    @Test
    void factsPersistAcrossInstances(@TempDir Path root) {
        var s1 = new FileSystemAgentState(root);
        s1.addFact("u1", "a1", "fact one");
        s1.addFact("u1", "a1", "fact two");

        var s2 = new FileSystemAgentState(root);
        var facts = s2.getFacts("u1", "a1");

        assertEquals(2, facts.size());
        assertEquals("fact one", facts.get(0).content());
        assertEquals("fact two", facts.get(1).content());
    }

    @Test
    void removeFactDeletesMatchingEntry(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var first = state.addFact("u1", "a1", "keep me");
        var toRemove = state.addFact("u1", "a1", "delete me");

        state.removeFact("u1", "a1", toRemove.id());

        var remaining = state.getFacts("u1", "a1");
        assertEquals(1, remaining.size());
        assertEquals(first.id(), remaining.get(0).id());
    }

    @Test
    void dailyNotesScopedByDate(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        state.addDailyNote("u1", "a1", "today's note");

        var today = state.getDailyNotes("u1", "a1", LocalDate.now());
        var yesterday = state.getDailyNotes("u1", "a1", LocalDate.now().minusDays(1));

        assertEquals(1, today.size());
        assertEquals("today's note", today.get(0).content());
        assertTrue(yesterday.isEmpty());
    }

    @Test
    void workingMemoryIsSessionScoped(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        state.setWorkingMemory("sess1", "key", "value1");
        state.setWorkingMemory("sess2", "key", "value2");

        assertEquals("value1", state.getWorkingMemory("sess1", "key").orElseThrow());
        assertEquals("value2", state.getWorkingMemory("sess2", "key").orElseThrow());

        state.clearWorkingMemory("sess1");

        assertTrue(state.getWorkingMemory("sess1", "key").isEmpty());
        assertEquals("value2", state.getWorkingMemory("sess2", "key").orElseThrow());
    }

    @Test
    void rulesAssembleFromWorkspaceFiles(@TempDir Path root) throws Exception {
        Files.writeString(root.resolve("IDENTITY.md"), "I am Pierre", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("SOUL.md"), "calm, direct", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("USER.md"), "call them ChefFamille", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("AGENTS.md"), "be helpful", StandardCharsets.UTF_8);

        var state = new FileSystemAgentState(root);
        var rules = state.getRules("u1", "a1");

        assertEquals("I am Pierre", rules.identity());
        assertEquals("calm, direct", rules.persona());
        assertEquals("call them ChefFamille", rules.userProfile());
        assertEquals("be helpful", rules.operatingRules());
        assertTrue(rules.systemPrompt().contains("## Identity"));
        assertTrue(rules.systemPrompt().contains("I am Pierre"));
        assertTrue(rules.systemPrompt().contains("## Persona"));
        assertTrue(rules.systemPrompt().contains("calm, direct"));
    }

    @Test
    void rulesEmptyWhenNoFilesPresent(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var rules = state.getRules("u1", "a1");
        // When no workspace files exist, all components are empty strings but
        // the RuleSet itself is non-null with an empty system prompt.
        assertEquals("", rules.systemPrompt());
        assertEquals("", rules.identity());
    }

    @Test
    void pathTraversalRejected(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        assertThrows(IllegalArgumentException.class,
                () -> state.appendConversation("../escape", "sess1", ChatMessage.user("x")));
        assertThrows(IllegalArgumentException.class,
                () -> state.appendConversation("agent1", "../../escape", ChatMessage.user("x")));
        assertThrows(IllegalArgumentException.class,
                () -> state.appendConversation("agent1", "sess/with/slash", ChatMessage.user("x")));
    }

    @Test
    void workspaceRootReturnsConfiguredRoot(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        assertEquals(root.toAbsolutePath().normalize(),
                state.workspaceRoot("agent1").orElseThrow());
    }

    @Test
    void conversationPreservesSpecialCharacters(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var tricky = "line1\nline2 \"quoted\" \\backslash\t tab";
        state.appendConversation("agent1", "sess1", ChatMessage.assistant(tricky));

        var history = state.getConversation("agent1", "sess1");
        assertEquals(1, history.size());
        assertEquals(tricky, history.get(0).content());
    }

    @Test
    void factsAreIsolatedByUserAndAgent(@TempDir Path root) {
        var state = new FileSystemAgentState(root);

        state.addFact("alice", "pierre", "alice's fact");
        state.addFact("bob", "pierre", "bob's fact for pierre");
        state.addFact("alice", "sophia", "alice's fact for sophia");

        var aliceForPierre = state.getFacts("alice", "pierre");
        var bobForPierre = state.getFacts("bob", "pierre");
        var aliceForSophia = state.getFacts("alice", "sophia");
        var bobForSophia = state.getFacts("bob", "sophia");

        assertEquals(1, aliceForPierre.size());
        assertEquals("alice's fact", aliceForPierre.get(0).content());

        assertEquals(1, bobForPierre.size());
        assertEquals("bob's fact for pierre", bobForPierre.get(0).content());

        assertEquals(1, aliceForSophia.size());
        assertEquals("alice's fact for sophia", aliceForSophia.get(0).content());

        assertTrue(bobForSophia.isEmpty(),
                "unwritten (bob, sophia) scope must not see other scopes' facts");
    }

    @Test
    void dailyNotesAreIsolatedByUserAndAgent(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        state.addDailyNote("alice", "pierre", "alice+pierre note");
        state.addDailyNote("bob", "pierre", "bob+pierre note");

        var alice = state.getDailyNotes("alice", "pierre", LocalDate.now());
        var bob = state.getDailyNotes("bob", "pierre", LocalDate.now());

        assertEquals(1, alice.size());
        assertEquals("alice+pierre note", alice.get(0).content());
        assertEquals(1, bob.size());
        assertEquals("bob+pierre note", bob.get(0).content());
    }

    @Test
    void removeFactOnlyAffectsOwnerScope(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var aliceFact = state.addFact("alice", "pierre", "alice's private fact");
        var bobFact = state.addFact("bob", "pierre", "bob's private fact");

        state.removeFact("alice", "pierre", bobFact.id());

        assertEquals(1, state.getFacts("alice", "pierre").size(),
                "deletion must not cross into another user's scope");
        assertEquals(1, state.getFacts("bob", "pierre").size(),
                "bob's fact survives alice's remove attempt on bob's id");
        assertEquals(aliceFact.id(),
                state.getFacts("alice", "pierre").get(0).id());
    }

    @Test
    void autoMemoryEveryNTurnsWritesDailyNote(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        var strategy = AutoMemoryStrategy.everyNTurns(2);

        strategy.onTurn(state, "u1", "a1", "s1",
                ChatMessage.user("q1"), ChatMessage.assistant("r1"));
        assertTrue(state.getDailyNotes("u1", "a1", LocalDate.now()).isEmpty());

        strategy.onTurn(state, "u1", "a1", "s1",
                ChatMessage.user("q2"), ChatMessage.assistant("r2"));
        assertEquals(1, state.getDailyNotes("u1", "a1", LocalDate.now()).size());
    }

    @Test
    void autoMemorySessionEndWritesSummary(@TempDir Path root) {
        var state = new FileSystemAgentState(root);
        state.appendConversation("a1", "s1", ChatMessage.user("hi"));
        state.appendConversation("a1", "s1", ChatMessage.assistant("hello"));

        var strategy = AutoMemoryStrategy.sessionEnd();
        strategy.onSessionEnd(state, "u1", "a1", "s1");

        assertEquals(1, state.getDailyNotes("u1", "a1", LocalDate.now()).size());
    }
}
