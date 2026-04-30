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
 * Declares the capabilities a tool requires to fire — the
 * <em>least-authority</em> principle expressed in code. Picked up by
 * {@link CapabilityScanner} alongside {@code @AiTool} so the policy
 * authoring path is purely additive: an existing tool annotated today
 * keeps working; a new tool that adds {@code @RequiresCapability("net")}
 * starts demanding the {@code "net"} grant the moment a policy is
 * loaded.
 *
 * <p>Example — a tool that talks to the network and writes to disk:</p>
 * <pre>{@code
 * @AiTool(name = "download_report", description = "Download a report")
 * @RequiresCapability({"net", "fs.write"})
 * public String downloadReport(@Param("url") String url) { ... }
 * }</pre>
 *
 * <p>Conventions: capability strings are short, dot-separated, all
 * lowercase. The verifier treats them as opaque tokens — there is no
 * inheritance hierarchy. {@code "net"} does not imply
 * {@code "net.outbound"}; either is granted explicitly or it is not
 * granted at all. This is intentional: capabilities flag
 * least-authority, and silent fan-out from a parent grant defeats that.</p>
 *
 * @see CapabilityScanner
 * @see org.atmosphere.verifier.checks.CapabilityVerifier
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequiresCapability {

    /**
     * Capability tokens this tool requires. Empty array means no
     * capabilities are needed — equivalent to omitting the annotation.
     */
    String[] value();
}
