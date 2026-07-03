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
package org.atmosphere.samples.springboot.codingagent;

import org.atmosphere.agent.annotation.Agent;
import org.atmosphere.ai.StreamingSession;
import org.atmosphere.ai.annotation.Prompt;
import org.atmosphere.ai.sandbox.Sandbox;
import org.atmosphere.ai.sandbox.SandboxTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Coding agent that clones a repository into a sandbox and reads files,
 * previewing repository contents. Exercises the {@code Sandbox} primitive
 * end-to-end without requiring an LLM key — the sample uses a
 * deterministic read strategy so it runs on CI.
 *
 * <h2>Sandbox provisioning</h2>
 *
 * {@code @SandboxTool} on the {@code @Prompt} method drives provisioning:
 * before the method runs, the framework creates an {@code alpine:3.20}
 * sandbox from the Docker backend (default limits — 1 CPU · 512 MB · 5 min —
 * with network egress enabled for the clone), injects it as the
 * {@link Sandbox} parameter, and closes it when the method returns. When
 * Docker is unavailable the invocation fails fast with a descriptive error —
 * there is no in-JVM fallback.
 *
 * <h2>AgentResumeHandle</h2>
 *
 * The clone + read flow is long-running. The {@code RunRegistry} reattach
 * flow — which lets a disconnecting client reattach via {@code runId} — is
 * not wired in this sample.
 */
@Agent(
        name = "coding-agent",
        skillFile = "skill:coding-agent",
        description = "Clones a repo into a sandbox and reads files.")
public class CodingAgent {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgent.class);

    // Clone + apk need network, so network = true (NetworkPolicy.FULL). A
    // production coding agent would narrow this to GIT_ONLY once the
    // sandbox runtime enforces the labeled allowlist.
    @Prompt
    @SandboxTool(image = "alpine:3.20", network = true)
    public void onPrompt(String message, StreamingSession session, Sandbox sandbox) {
        try {
            runSandboxFlow(message, session, sandbox);
        } finally {
            if (!session.isClosed()) {
                session.complete();
            }
        }
    }

    // session.send() pushes a text chunk to the client; session.stream() would
    // dispatch the argument to the LLM as a fresh user turn instead, which is
    // not what this sample wants — it is driving the sandbox directly.
    //
    // The sandbox parameter is provisioned by the framework from @SandboxTool
    // and closed by the framework after onPrompt returns — this method must
    // never close it (Ownership, Correctness Invariant #1).
    private void runSandboxFlow(String message, StreamingSession session, Sandbox sandbox) {
        // Parse the user intent. Keyword-based — an LLM-driven version
        // would map natural language to the tool calls below.
        var lower = message.toLowerCase();
        var repo = extractRepoUrl(lower);
        if (repo == null) {
            session.send(
                    "Give me a repo URL to work with, for example:\n"
                            + "  `clone https://github.com/example/repo.git and read README.md`");
            return;
        }

        try {
            // Install git + clone as two separate exec calls with argv-form
            // commands — NEVER concatenate `repo` into an `sh -c` string, or
            // a repo URL containing shell metacharacters turns the sandbox
            // into a shell-injection primitive. `extractRepoUrl` already
            // allowlists the URL shape; argv separation is the belt to its
            // suspenders (Correctness Invariant #4 — Boundary Safety).
            session.progress("Installing git...");
            var installGit = sandbox.exec(
                    List.of("apk", "add", "--no-cache", "git"),
                    Duration.ofMinutes(1));
            if (!installGit.succeeded()) {
                session.send("Failed to install git in sandbox:\n" + installGit.stderr());
                return;
            }

            session.progress("Cloning " + repo + "...");
            var clone = sandbox.exec(
                    List.of("git", "clone", "--depth", "1", repo, "/workspace/repo"),
                    Duration.ofMinutes(2));
            if (!clone.succeeded()) {
                session.send("Clone failed:\n" + clone.stderr());
                return;
            }

            session.progress("Reading README...");
            // Case-insensitive probe: GitHub treats README/readme/Readme as
            // equivalent for rendering, but the sandbox filesystem is
            // case-sensitive. sindresorhus/awesome and many other popular
            // repos ship `readme.md` lowercase.
            String readme = null;
            for (var name : List.of(
                    "README.md", "readme.md", "Readme.md",
                    "README", "readme", "Readme")) {
                readme = tryReadFile(sandbox, Path.of("/workspace/repo/" + name));
                if (readme != null) {
                    break;
                }
            }
            if (readme == null) {
                session.send("Cloned " + repo + " but no README found at the root.");
                return;
            }

            session.send("README preview from " + repo + " (first 800 chars):\n\n"
                    + readme.substring(0, Math.min(800, readme.length())));
        } catch (RuntimeException e) {
            logger.warn("coding-agent run failed: {}", e.getMessage(), e);
            session.send("Sandbox operation failed: " + e.getMessage());
        }
    }

    /**
     * Strict GitHub-URL allowlist. Returns the normalized URL or {@code null}
     * if the candidate contains anything outside the
     * {@code https://github.com/<user>/<repo>(.git)?} shape — shell
     * metacharacters, path traversal, unexpected hosts are all rejected.
     * Defense-in-depth alongside argv-form {@code sandbox.exec} — even though
     * the command path no longer interpolates the URL into a shell string,
     * we still refuse malformed input at the boundary.
     */
    private static String extractRepoUrl(String message) {
        var pattern = java.util.regex.Pattern.compile(
                "https://github\\.com/[A-Za-z0-9._-]+/[A-Za-z0-9._-]+(?:\\.git)?");
        for (var token : message.split("\\s+")) {
            var matcher = pattern.matcher(token);
            if (matcher.matches()) {
                return token.endsWith(".git") ? token : token + ".git";
            }
        }
        return null;
    }

    private static String tryReadFile(Sandbox sandbox, Path path) {
        try {
            return sandbox.readFile(path);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
