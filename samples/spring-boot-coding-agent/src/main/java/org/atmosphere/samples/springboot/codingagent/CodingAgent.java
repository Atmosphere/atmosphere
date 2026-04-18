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
import org.atmosphere.ai.sandbox.NetworkPolicy;
import org.atmosphere.ai.sandbox.Sandbox;
import org.atmosphere.ai.sandbox.SandboxLimits;
import org.atmosphere.ai.sandbox.SandboxProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.ServiceLoader;

/**
 * Coding agent that clones a repository into a sandbox, reads files, and
 * proposes a patch. Exercises the {@code Sandbox} primitive end-to-end
 * without requiring an LLM key — the sample uses a deterministic patch
 * strategy so it runs on CI.
 *
 * <h2>Sandbox discovery</h2>
 *
 * The agent picks the first available {@code SandboxProvider} in priority
 * order: Docker if a daemon is running, in-process otherwise. In production
 * you would pin the Docker provider and fail hard when it is unavailable
 * (per v0.6 plan open question #2); the sample is permissive on purpose so
 * it demonstrates the shape on any developer machine.
 *
 * <h2>AgentResumeHandle</h2>
 *
 * The clone + patch flow is long-running. In production the run would
 * register with the {@code RunRegistry} and a disconnecting client would
 * reattach via {@code runId}. Wiring lands in Phase 1.5.
 */
@Agent(
        name = "coding-agent",
        skillFile = "skill:coding-agent",
        description = "Clones a repo into a sandbox, reads files, and proposes patches.")
public class CodingAgent {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgent.class);

    @Prompt
    public void onPrompt(String message, StreamingSession session) {
        try {
            runSandboxFlow(message, session);
        } finally {
            if (!session.isClosed()) {
                session.complete();
            }
        }
    }

    // session.send() pushes a text chunk to the client; session.stream() would
    // dispatch the argument to the LLM as a fresh user turn instead, which is
    // not what this sample wants — it is driving the sandbox directly.
    private void runSandboxFlow(String message, StreamingSession session) {
        var provider = resolveProvider();
        if (provider == null) {
            session.send(
                    "No sandbox provider is available. Install Docker or include the "
                            + "in-process provider to run this sample.");
            return;
        }

        session.progress("Provisioning " + provider.name() + " sandbox...");

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

        // Clone + apk need network; override the default NONE policy. A
        // production coding agent would narrow this to GIT_ONLY once the
        // sandbox runtime enforces the labeled allowlist.
        var limits = new SandboxLimits(
                SandboxLimits.DEFAULT.cpuFraction(),
                SandboxLimits.DEFAULT.memoryBytes(),
                SandboxLimits.DEFAULT.wallTime(),
                NetworkPolicy.FULL);
        try (Sandbox sandbox = provider.create("alpine:3.20",
                limits,
                Map.of("owner", "coding-agent-sample", "repo", repo))) {

            // Install git + clone as two separate exec calls with argv-form
            // commands — NEVER concatenate `repo` into an `sh -c` string, or
            // a repo URL containing shell metacharacters turns the sandbox
            // into a shell-injection primitive. `extractRepoUrl` already
            // allowlists the URL shape; argv separation is the belt to its
            // suspenders (Correctness Invariant #4 — Boundary Safety).
            session.progress("Installing git...");
            var aptget = sandbox.exec(
                    List.of("apk", "add", "--no-cache", "git"),
                    Duration.ofMinutes(1));
            if (!aptget.succeeded()) {
                session.send("Failed to install git in sandbox:\n" + aptget.stderr());
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
            var readme = tryReadFile(sandbox, Path.of("/workspace/repo/README.md"));
            if (readme == null) {
                readme = tryReadFile(sandbox, Path.of("/workspace/repo/README"));
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

    private static SandboxProvider resolveProvider() {
        for (var provider : ServiceLoader.load(SandboxProvider.class)) {
            if (provider.isAvailable()) {
                return provider;
            }
        }
        return null;
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
