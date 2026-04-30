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

import org.atmosphere.ai.annotation.AiTool;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reflective derivation of (toolName → required-capability-set) maps
 * from {@link AiTool}-annotated methods carrying
 * {@link RequiresCapability}. The verifier consumes this map; the policy
 * carries the inverse — the set of capabilities granted to the agent.
 *
 * <p>Like {@link SinkScanner}, this keeps the policy declaration
 * co-located with the protected code: renaming a tool or its capability
 * requirement updates the manifest the moment the application boots.</p>
 */
public final class CapabilityScanner {

    private CapabilityScanner() {
        // static utility
    }

    /**
     * Scan {@code toolClasses} and return the merged
     * {@code toolName -> required-capabilities} map. Tools without
     * {@link RequiresCapability} contribute an empty set — they require
     * no capability and so always pass the
     * {@link org.atmosphere.verifier.checks.CapabilityVerifier}.
     */
    public static Map<String, Set<String>> scan(Class<?>... toolClasses) {
        var out = new LinkedHashMap<String, Set<String>>();
        for (Class<?> c : toolClasses) {
            if (c == null) {
                continue;
            }
            for (Method m : c.getMethods()) {
                AiTool tool = m.getAnnotation(AiTool.class);
                if (tool == null) {
                    continue;
                }
                RequiresCapability rc = m.getAnnotation(RequiresCapability.class);
                Set<String> caps = rc == null
                        ? Set.of()
                        : Set.of(rc.value());
                // Last writer wins on duplicate tool names — by
                // convention the registry already rejects duplicates,
                // but the scanner stays defensive.
                out.put(tool.name(), caps);
            }
        }
        return Map.copyOf(out);
    }
}
