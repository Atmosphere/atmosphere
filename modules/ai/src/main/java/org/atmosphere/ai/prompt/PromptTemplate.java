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
package org.atmosphere.ai.prompt;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Minimal {@code {{variable}}} substitution for registry-managed prompts. No
 * external templating dependency: variables are simple named placeholders
 * ({@code [A-Za-z0-9._-]+}, optional surrounding whitespace inside the braces)
 * replaced verbatim — no expressions, loops, or escaping syntax.
 *
 * <p>Variable values are layered: config defaults
 * ({@code atmosphere.ai.prompt.var.<name>} system properties) first, overridden
 * by the caller-supplied per-request map. Any placeholder left unresolved after
 * layering fails <em>closed</em> with an {@link IllegalStateException} naming
 * the missing variables — a half-templated prompt is never shipped to the
 * model (Correctness Invariant #6: fail closed by default).</p>
 */
public final class PromptTemplate {

    /** System-property prefix supplying config-default template variables. */
    public static final String VAR_PROPERTY_PREFIX = "atmosphere.ai.prompt.var.";

    private static final Pattern PLACEHOLDER =
            Pattern.compile("\\{\\{\\s*([A-Za-z0-9._-]+)\\s*}}");

    private PromptTemplate() {
    }

    /**
     * Renders the template, substituting every {@code {{variable}}} placeholder
     * from the layered variable map (config defaults overridden by
     * {@code variables}).
     *
     * @param template  the prompt text containing zero or more placeholders
     * @param variables per-request variables; may be empty, wins over config defaults
     * @return the fully substituted text
     * @throws IllegalStateException if any placeholder has no value after
     *         layering (fail closed — the incomplete prompt never leaves this method)
     */
    public static String render(String template, Map<String, String> variables) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        var resolved = new LinkedHashMap<>(configDefaults());
        resolved.putAll(variables);

        var missing = new LinkedHashSet<String>();
        var matcher = PLACEHOLDER.matcher(template);
        var out = new StringBuilder();
        while (matcher.find()) {
            var variable = matcher.group(1);
            var value = resolved.get(variable);
            if (value == null) {
                missing.add(variable);
                continue;
            }
            matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(value));
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "Unresolved prompt template variable(s) " + missing
                            + ": supply per-request values or set "
                            + VAR_PROPERTY_PREFIX + "<name> system properties. "
                            + "Refusing to ship a half-templated prompt to the model.");
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Reads the config-default variables from {@code atmosphere.ai.prompt.var.*}
     * system properties.
     *
     * @return variable name to default value, insertion order unspecified
     */
    public static Map<String, String> configDefaults() {
        var defaults = new LinkedHashMap<String, String>();
        for (var key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(VAR_PROPERTY_PREFIX) && key.length() > VAR_PROPERTY_PREFIX.length()) {
                defaults.put(key.substring(VAR_PROPERTY_PREFIX.length()), System.getProperty(key));
            }
        }
        return defaults;
    }
}
