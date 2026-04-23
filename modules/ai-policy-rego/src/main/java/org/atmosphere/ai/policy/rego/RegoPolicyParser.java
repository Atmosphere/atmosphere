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
package org.atmosphere.ai.policy.rego;

import org.atmosphere.ai.governance.GovernancePolicy;
import org.atmosphere.ai.governance.PolicyParser;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.regex.Pattern;

/**
 * {@link PolicyParser} that wraps a Rego policy module in a
 * {@link RegoPolicy} adapter so the Atmosphere governance pipeline can
 * consult it. Format identifier {@code "rego"}.
 *
 * <h2>Conventions</h2>
 * The parser produces one {@link RegoPolicy} per {@code .rego} module:
 * <ul>
 *   <li>{@code name} is extracted from the Rego {@code package} declaration
 *       (e.g. {@code package atmosphere.governance.support} →
 *       {@code "atmosphere.governance.support"}).</li>
 *   <li>{@code source} is the caller-supplied source URI (prefix
 *       {@code rego:} so admin introspection can distinguish from
 *       {@code yaml:} sources).</li>
 *   <li>{@code query} defaults to {@code data.<package>.allow}. Operators
 *       using a different query name install the evaluator manually and
 *       construct {@link RegoPolicy} directly.</li>
 * </ul>
 *
 * <p>Bound to {@link OpaSubprocessEvaluator} at default construction.
 * Operators with {@code opa} not on PATH or using an embedded Rego
 * runtime (e.g. a pure-Java fork) inject their own evaluator via
 * {@link #RegoPolicyParser(RegoEvaluator)}.</p>
 */
public final class RegoPolicyParser implements PolicyParser {

    private static final Pattern PACKAGE_DECL = Pattern.compile(
            "(?m)^\\s*package\\s+([A-Za-z_][A-Za-z0-9_.]*)\\s*$");

    private final RegoEvaluator evaluator;

    /** Default — shells out to {@code opa} on PATH. */
    public RegoPolicyParser() {
        this(new OpaSubprocessEvaluator());
    }

    public RegoPolicyParser(RegoEvaluator evaluator) {
        if (evaluator == null) {
            throw new IllegalArgumentException("evaluator must not be null");
        }
        this.evaluator = evaluator;
    }

    @Override
    public String format() {
        return "rego";
    }

    @Override
    public List<GovernancePolicy> parse(String source, InputStream in) throws IOException {
        if (in == null) {
            return List.of();
        }
        var rego = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        var matcher = PACKAGE_DECL.matcher(rego);
        var packageName = matcher.find() ? matcher.group(1) : "atmosphere.governance.rego";
        var name = packageName;
        var query = "data." + packageName + ".allow";
        return List.of(new RegoPolicy(name,
                source == null ? "rego:<unknown>" : source,
                "1.0",
                rego,
                query,
                evaluator));
    }
}
