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
package org.atmosphere.verifier.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a tool parameter as a <em>forbidden sink</em> for data produced
 * by the listed source tools. Picked up by
 * {@link org.atmosphere.verifier.annotation.SinkScanner} to derive
 * {@link org.atmosphere.verifier.policy.TaintRule}s without forcing
 * authors to maintain a parallel YAML/JSON policy by hand.
 *
 * <p>Example — refuse to send anything {@code fetch_emails} returned as
 * the body of an outgoing email:</p>
 * <pre>{@code
 * @AiTool(name = "send_email", description = "Send an email")
 * public String sendEmail(
 *         @AiToolParam(name = "to") String to,
 *         @AiToolParam(name = "body")
 *         @Sink(forbidden = {"fetch_emails"}) String body) {
 *     ...
 * }
 * }</pre>
 *
 * <p>The annotation is a co-located declaration of the same security
 * property a {@link org.atmosphere.verifier.policy.TaintRule} expresses
 * imperatively. Authors can mix both freely — the
 * {@link org.atmosphere.verifier.checks.TaintVerifier} consumes the union.</p>
 *
 * <p>Why parameter-level rather than tool-level? Most real sinks are a
 * specific argument (the email body, the SQL query string, the URL),
 * not the tool as a whole. Pinning the annotation to the parameter
 * captures the intent precisely and survives parameter renames in a way
 * a string-keyed external policy doesn't.</p>
 *
 * @see SinkScanner
 * @see org.atmosphere.verifier.checks.TaintVerifier
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface Sink {

    /**
     * Names of source tools whose return values are forbidden from
     * reaching this parameter. Each entry generates one
     * {@link org.atmosphere.verifier.policy.TaintRule}.
     *
     * @return the forbidden source-tool names; non-empty.
     */
    String[] forbidden();

    /**
     * Optional human-readable rule name used in violation diagnostics.
     * Defaults to {@code "<sourceTool>-to-<sinkTool>.<sinkParam>"} when
     * blank.
     */
    String name() default "";
}
