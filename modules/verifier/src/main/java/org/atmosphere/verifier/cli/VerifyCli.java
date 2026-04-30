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
package org.atmosphere.verifier.cli;

import org.atmosphere.ai.tool.ToolRegistry;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.policy.Policy;
import org.atmosphere.verifier.policy.SecurityAutomaton;
import org.atmosphere.verifier.policy.TaintRule;
import org.atmosphere.verifier.prompt.WorkflowJsonParser;
import org.atmosphere.verifier.spi.PlanVerifier;
import org.atmosphere.verifier.spi.VerificationResult;
import org.atmosphere.verifier.spi.Violation;

import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Offline command-line frontend to the verifier chain. Reads a workflow
 * JSON and a policy JSON from disk (or stdin), runs every
 * ServiceLoader-discovered {@link PlanVerifier}, and prints either
 * {@code OK} or one violation per line. Exits 0 on green, 1 on any
 * violation, 2 on usage / IO error.
 *
 * <h3>Why a CLI?</h3>
 * <p>Two audiences:</p>
 * <ol>
 *   <li><b>Policy authors</b> who want to validate a plan offline
 *       without booting the runtime — e.g. when reviewing a captured
 *       LLM output during a security audit.</li>
 *   <li><b>CI pipelines</b> that maintain a corpus of "expected good"
 *       and "expected refused" plans and assert the verifier chain's
 *       behavior is stable across releases. Exit code 0/1 makes the
 *       command shell-friendly.</li>
 * </ol>
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * verify --policy email.policy.json --workflow attack.plan.json
 * verify --policy email.policy.json - <attack.plan.json
 * }</pre>
 *
 * <p>The minimal policy JSON shape mirrors {@link Policy}'s record
 * fields:</p>
 * <pre>{@code
 * {
 *   "name": "email",
 *   "allowedTools": ["fetch_emails", "send_email"],
 *   "taintRules": [
 *     { "name": "no-leak", "sourceTool": "fetch_emails",
 *       "sinkTool": "send_email", "sinkParam": "body" }
 *   ],
 *   "automata": []
 * }
 * }</pre>
 *
 * <p>Tool registry: the CLI does not have access to the application's
 * {@link ToolRegistry}; it constructs an in-memory shim that reports
 * <em>every tool name in the policy as registered</em> so the
 * {@link org.atmosphere.verifier.checks.AllowlistVerifier}'s
 * registry-existence half passes without false positives. (The CLI is
 * for inspecting policy-vs-plan agreement, not for catching tool
 * deployment drift — that's a runtime concern.)</p>
 */
public final class VerifyCli {

    private VerifyCli() {
        // CLI entry point only
    }

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    /**
     * Programmatic entry point — same behavior as {@link #main} but
     * lets tests inspect output without spawning a subprocess.
     *
     * @return 0 on green, 1 on any violation, 2 on usage / IO error.
     */
    public static int run(String[] args, PrintStream out, PrintStream err) {
        Path policyPath = null;
        Path workflowPath = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--policy", "-p" -> {
                    if (++i >= args.length) {
                        err.println("missing value for --policy");
                        return 2;
                    }
                    policyPath = Path.of(args[i]);
                }
                case "--workflow", "-w" -> {
                    if (++i >= args.length) {
                        err.println("missing value for --workflow");
                        return 2;
                    }
                    workflowPath = "-".equals(args[i]) ? null : Path.of(args[i]);
                }
                case "--help", "-h" -> {
                    out.println(usage());
                    return 0;
                }
                default -> {
                    err.println("unknown argument: " + args[i]);
                    err.println(usage());
                    return 2;
                }
            }
        }
        if (policyPath == null) {
            err.println("--policy is required");
            err.println(usage());
            return 2;
        }
        try {
            String workflowJson = workflowPath == null
                    ? readStdin()
                    : Files.readString(workflowPath);
            String policyJson = Files.readString(policyPath);

            Policy policy = PolicyJsonParser.parse(policyJson);
            Workflow workflow = new WorkflowJsonParser().parse(workflowJson);
            ToolRegistry registry = new InMemoryShimRegistry(policy.allowedTools());

            VerificationResult result = runChain(workflow, policy, registry);
            if (result.isOk()) {
                out.println("OK — verifier chain passed (0 violations)");
                return 0;
            }
            out.println("FAILED — " + result.violations().size() + " violation(s):");
            for (Violation v : result.violations()) {
                out.println("  [" + v.category() + "] " + v.message()
                        + (v.astPath() != null ? " (" + v.astPath() + ")" : ""));
            }
            return 1;
        } catch (RuntimeException ex) {
            err.println("error: " + ex.getMessage());
            return 2;
        } catch (Exception ex) {
            err.println("error: " + ex.getClass().getSimpleName() + ": " + ex.getMessage());
            return 2;
        }
    }

    private static VerificationResult runChain(Workflow workflow, Policy policy, ToolRegistry registry) {
        var verifiers = new ArrayList<PlanVerifier>();
        for (PlanVerifier v : ServiceLoader.load(PlanVerifier.class)) {
            verifiers.add(v);
        }
        verifiers.sort(Comparator.comparingInt(PlanVerifier::priority));
        VerificationResult acc = VerificationResult.ok();
        for (PlanVerifier v : verifiers) {
            acc = acc.merge(v.verify(workflow, policy, registry));
        }
        return acc;
    }

    private static String readStdin() throws java.io.IOException {
        return new String(System.in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    }

    static String usage() {
        return """
                Usage: verify --policy <path> [--workflow <path>|-]
                Options:
                  --policy, -p <path>     policy JSON file (required)
                  --workflow, -w <path>   workflow JSON file; pass '-' for stdin
                  --help, -h              this message

                Exit codes:
                  0  verifier chain passed (no violations)
                  1  at least one violation
                  2  usage error or IO failure
                """;
    }

    /**
     * Tool registry that reports every tool name in {@code knownTools}
     * as present, with a stub definition. Exists solely to satisfy
     * {@link org.atmosphere.verifier.checks.AllowlistVerifier}'s
     * registry-existence half during offline CLI runs — the rest of
     * the chain is unaffected.
     */
    private static final class InMemoryShimRegistry implements ToolRegistry {
        private final Set<String> known;

        InMemoryShimRegistry(Set<String> known) {
            this.known = known;
        }

        @Override public void register(org.atmosphere.ai.tool.ToolDefinition tool) {
            throw new UnsupportedOperationException("CLI shim — read-only");
        }
        @Override public void register(Object toolProvider) {
            throw new UnsupportedOperationException("CLI shim — read-only");
        }
        @Override public java.util.Optional<org.atmosphere.ai.tool.ToolDefinition> getTool(String name) {
            if (!known.contains(name)) {
                return java.util.Optional.empty();
            }
            // Build a minimal stub to satisfy AllowlistVerifier's
            // isPresent() probe. The stub is never executed.
            return java.util.Optional.of(
                    org.atmosphere.ai.tool.ToolDefinition.builder(name, "cli-shim")
                            .executor(args -> "")
                            .build());
        }
        @Override public java.util.Collection<org.atmosphere.ai.tool.ToolDefinition> getTools(java.util.Collection<String> names) {
            return names.stream()
                    .map(this::getTool)
                    .filter(java.util.Optional::isPresent)
                    .map(java.util.Optional::get)
                    .toList();
        }
        @Override public java.util.Collection<org.atmosphere.ai.tool.ToolDefinition> allTools() {
            return known.stream()
                    .map(n -> getTool(n).orElseThrow())
                    .toList();
        }
        @Override public boolean unregister(String name) {
            throw new UnsupportedOperationException("CLI shim — read-only");
        }
        @Override public org.atmosphere.ai.tool.ToolResult execute(String toolName,
                                                                   java.util.Map<String, Object> arguments) {
            throw new UnsupportedOperationException("CLI shim — never executes");
        }
    }

    /**
     * Minimal handwritten policy-JSON parser. Lives in the verifier
     * module so the CLI doesn't need to drag Jackson directly onto its
     * compile path; we use {@link WorkflowJsonParser}'s
     * {@code StructuredOutputParser} for shape-symmetric parsing.
     */
    static final class PolicyJsonParser {
        private PolicyJsonParser() { }

        static Policy parse(String json) {
            var parser = org.atmosphere.ai.StructuredOutputParser.resolve();
            PolicyRecord raw = parser.parse(json, PolicyRecord.class);
            if (raw == null || raw.name() == null) {
                throw new IllegalArgumentException("policy JSON missing 'name'");
            }
            Set<String> allowed = raw.allowedTools() == null
                    ? Set.of()
                    : Set.copyOf(raw.allowedTools());
            List<TaintRule> rules = new ArrayList<>();
            if (raw.taintRules() != null) {
                for (TaintRuleRecord t : raw.taintRules()) {
                    rules.add(new TaintRule(t.name(), t.sourceTool(), t.sinkTool(), t.sinkParam()));
                }
            }
            // Phase-5 minimum — automata declared in JSON are passed
            // through to the policy as-is. The CLI doesn't currently
            // expose a JSON shape for them; the field is reserved.
            List<SecurityAutomaton> automata = List.of();
            return new Policy(raw.name(), allowed, rules, automata);
        }

        /** Wire-format record matching the JSON shape. */
        public record PolicyRecord(String name,
                                   List<String> allowedTools,
                                   List<TaintRuleRecord> taintRules) {
        }

        public record TaintRuleRecord(String name,
                                      String sourceTool,
                                      String sinkTool,
                                      String sinkParam) {
        }
    }
}
