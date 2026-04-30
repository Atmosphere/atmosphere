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
package org.atmosphere.verifier.prompt;

import org.atmosphere.ai.StructuredOutputParser;
import org.atmosphere.verifier.ast.SymRef;
import org.atmosphere.verifier.ast.ToolCallNode;
import org.atmosphere.verifier.ast.Workflow;
import org.atmosphere.verifier.ast.WorkflowStep;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Parses LLM-emitted JSON into a {@link Workflow} AST.
 *
 * <p>Wire format is intentionally flat (no JSON polymorphism, no Jackson
 * type-id discriminator) so the verifier module need not annotate its AST
 * records nor pull Jackson onto its compile path. The parser reads into
 * a small intermediate {@link PlanJsonRecord} via the
 * {@link StructuredOutputParser} resolved on the classpath (delegates to
 * {@code atmosphere-ai}'s Jackson-backed implementation by default), then
 * walks the result rebuilding the immutable AST and converting
 * {@code "@binding-name"} strings into {@link SymRef} instances.</p>
 *
 * <p>The {@code @}-prefix convention matches the Python reference
 * implementation of <em>"Guardians of the Agents"</em>. It is intentionally
 * obvious to a human reading the JSON what is a literal versus a
 * symbolic reference. A literal string that happens to start with
 * {@code @} can be escaped as {@code @@}.</p>
 */
public final class WorkflowJsonParser {

    static final String SYM_REF_PREFIX = "@";

    private final StructuredOutputParser delegate;

    public WorkflowJsonParser() {
        this(StructuredOutputParser.resolve());
    }

    public WorkflowJsonParser(StructuredOutputParser delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    /**
     * @return JSON-Schema-style schema instructions to embed in the system
     *         prompt so the LLM produces conformant output. Built directly
     *         from the wire-format record so the schema and the parser
     *         can never drift.
     */
    public String schemaInstructions() {
        return delegate.schemaInstructions(PlanJsonRecord.class);
    }

    /**
     * Parse the LLM's raw JSON output into a {@link Workflow}.
     *
     * @throws StructuredOutputParser.StructuredOutputException on malformed
     *         JSON or schema mismatch.
     */
    public Workflow parse(String json) {
        PlanJsonRecord raw = delegate.parse(json, PlanJsonRecord.class);
        return toWorkflow(raw);
    }

    private Workflow toWorkflow(PlanJsonRecord raw) {
        if (raw == null || raw.goal() == null) {
            throw new StructuredOutputParser.StructuredOutputException(
                    "Workflow JSON missing required 'goal' field");
        }
        List<WorkflowStep> steps = new ArrayList<>();
        if (raw.steps() != null) {
            for (var stepJson : raw.steps()) {
                steps.add(toStep(stepJson));
            }
        }
        return new Workflow(raw.goal(), steps);
    }

    private WorkflowStep toStep(StepJsonRecord step) {
        if (step == null) {
            throw new StructuredOutputParser.StructuredOutputException(
                    "Workflow JSON step is null");
        }
        if (step.toolName() == null || step.toolName().isBlank()) {
            throw new StructuredOutputParser.StructuredOutputException(
                    "Workflow step '" + step.label()
                            + "' missing required 'toolName' field");
        }
        Map<String, Object> args = new LinkedHashMap<>();
        if (step.arguments() != null) {
            for (var entry : step.arguments().entrySet()) {
                args.put(entry.getKey(), convertSymRefMarker(entry.getValue()));
            }
        }
        ToolCallNode call = new ToolCallNode(step.toolName(), args, step.resultBinding());
        return new WorkflowStep(step.label(), call);
    }

    /**
     * Convert {@code "@name"} string markers into {@link SymRef}. Literal
     * {@code "@@..."} strings are unescaped to {@code "@..."}. Non-string
     * values pass through untouched (Phase 5 widens this to recurse into
     * nested lists / maps).
     */
    private Object convertSymRefMarker(Object value) {
        if (value instanceof String s) {
            if (s.startsWith(SYM_REF_PREFIX + SYM_REF_PREFIX)) {
                return s.substring(1);
            }
            if (s.startsWith(SYM_REF_PREFIX) && s.length() > 1) {
                return new SymRef(s.substring(1));
            }
        }
        return value;
    }

    /**
     * Wire-format JSON record. Flat shape, one wire type per concrete
     * Phase 1 plan node — no polymorphism, no @type discriminator. Phase 5
     * extends with optional {@code conditional} / {@code loop} sibling
     * fields keyed off step kind.
     */
    public record PlanJsonRecord(String goal, List<StepJsonRecord> steps) {
    }

    /**
     * Wire-format step record.
     */
    public record StepJsonRecord(String label,
                                  String toolName,
                                  Map<String, Object> arguments,
                                  String resultBinding) {
    }
}
