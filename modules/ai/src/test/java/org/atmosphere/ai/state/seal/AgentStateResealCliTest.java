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
package org.atmosphere.ai.state.seal;

import org.atmosphere.ai.llm.ChatMessage;
import org.atmosphere.ai.state.FileSystemAgentState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link AgentStateReseal} CLI: exit codes and the end-to-end operator flow —
 * a tampered (deliberately edited) workspace fails closed until the CLI
 * blesses the edit, using the same managed key as the running control.
 */
class AgentStateResealCliTest {

    @Test
    void usageErrorsExitWithTwo() {
        assertEquals(2, AgentStateReseal.run(new String[0]));
        assertEquals(2, AgentStateReseal.run(new String[] {"root", "--key-file"}));
        assertEquals(2, AgentStateReseal.run(new String[] {"root", "extra", "args"}));
    }

    @Test
    void missingWorkspaceExitsWithOne(@TempDir Path tmp) {
        assertEquals(1, AgentStateReseal.run(
                new String[] {tmp.resolve("does-not-exist").toString()}));
    }

    @Test
    void resealBlessesAHandEditUsingTheManagedKey(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var state = new FileSystemAgentState(ws,
                AgentStateSealer.forWorkspace(ws, null, false));
        state.appendConversation("agent-1", "s-1", new ChatMessage("user", "hello"));

        var session = ws.resolve("agents/agent-1/sessions/s-1.jsonl");
        Files.writeString(session, "{\"role\":\"user\",\"content\":\"edited\"}\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        assertThrows(AgentStateSealException.class,
                () -> state.getConversation("agent-1", "s-1"));

        assertEquals(0, AgentStateReseal.run(new String[] {ws.toString()}),
                "the reseal CLI must succeed against the managed key location");
        assertEquals(2, state.getConversation("agent-1", "s-1").size(),
                "after the reseal the blessed edit must load again");
    }

    @Test
    void explicitKeyFileArgumentIsUsed(@TempDir Path tmp) throws IOException {
        var ws = Files.createDirectories(tmp.resolve("ws"));
        var sealer = AgentStateSealer.forWorkspace(ws, null, false);
        var state = new FileSystemAgentState(ws, sealer);
        state.addFact("alice", "agent-1", "original");

        var memory = ws.resolve("users/alice/agents/agent-1/MEMORY.md");
        Files.writeString(memory, "hand edit\n", StandardCharsets.UTF_8,
                StandardOpenOption.APPEND);
        assertThrows(AgentStateSealException.class, () -> state.getFacts("alice", "agent-1"));

        var keyFile = tmp.resolve("ws.seal").resolve(AgentStateSealer.KEY_FILE_NAME);
        assertEquals(0, AgentStateReseal.run(new String[] {
                ws.toString(), "--key-file", keyFile.toString()}));
        assertEquals(1, state.getFacts("alice", "agent-1").size());
    }
}
