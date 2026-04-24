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
package org.atmosphere.ai.governance;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

/**
 * Pluggable parser that translates a declarative policy artifact (YAML, Rego, Cedar, …)
 * into a list of {@link GovernancePolicy} instances.
 *
 * <p>Parsers are discovered via {@link java.util.ServiceLoader}. Applications plug in a
 * Rego or Cedar evaluator by providing the parser plus a {@link GovernancePolicy}
 * adapter that delegates evaluation to the external engine.</p>
 *
 * <p>This interface is the SPI only; it ships in the Phase A skeleton commit. A YAML
 * implementation backed by SnakeYAML is added in a follow-up commit and declares
 * SnakeYAML as an explicit {@code modules/ai} runtime dependency — SnakeYAML is NOT
 * transitively pulled in by Quarkus or bare-JVM / Jetty-embedded deployments, so the
 * dep has to be declared explicitly on the out-of-box path.</p>
 */
public interface PolicyParser {

    /**
     * Format identifier this parser handles. Conventional values: {@code "yaml"},
     * {@code "rego"}, {@code "cedar"}. The {@link PolicyRegistry} resolves parsers
     * by this string (case-insensitive) when loading policies by URI scheme.
     */
    String format();

    /**
     * Parse the given input stream into a list of policies. The caller owns the
     * stream and is responsible for closing it.
     *
     * @param source  opaque source identifier propagated onto
     *                {@link GovernancePolicy#source()} for every policy produced
     *                (e.g. {@code "yaml:/etc/atmosphere/policies.yaml"})
     * @param in      stream to read; caller closes
     * @return parsed policies, never {@code null} (may be empty for a valid but
     *         empty document)
     * @throws IOException on I/O or parse failure — the pipeline treats parse
     *                     failure as fail-closed at startup; the admin console
     *                     surfaces the exception so operators can repair the file
     */
    List<GovernancePolicy> parse(String source, InputStream in) throws IOException;
}
