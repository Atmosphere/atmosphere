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
package org.atmosphere.verifier.policy;

import java.util.Objects;

/**
 * Declarative dataflow ban: data produced by {@code sourceTool} must not
 * flow into {@code sinkParam} of {@code sinkTool}.
 *
 * <p>The {@link org.atmosphere.verifier.checks.TaintVerifier} enforces this
 * rule statically: it refuses any plan that routes data from
 * {@code sourceTool}'s output — directly or transitively, at any argument
 * depth — into {@code sinkParam} of {@code sinkTool}.</p>
 *
 * @param name       short identifier for diagnostics; non-blank.
 * @param sourceTool name of the tool whose return value is tainted.
 * @param sinkTool   name of the tool whose parameter is the forbidden sink.
 * @param sinkParam  name of the parameter on {@code sinkTool} that data
 *                   from {@code sourceTool} must not reach.
 */
public record TaintRule(String name,
                        String sourceTool,
                        String sinkTool,
                        String sinkParam) {
    public TaintRule {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(sourceTool, "sourceTool");
        Objects.requireNonNull(sinkTool, "sinkTool");
        Objects.requireNonNull(sinkParam, "sinkParam");
        if (name.isBlank() || sourceTool.isBlank()
                || sinkTool.isBlank() || sinkParam.isBlank()) {
            throw new IllegalArgumentException(
                    "TaintRule fields must not be blank");
        }
    }
}
