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
package org.atmosphere.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A lightweight URI path template that supports {@code {varName}} and
 * {@code {varName:regex}} syntax.  Replaces the Jersey-derived
 * {@code UriTemplate / UriPattern / UriTemplateParser / UriComponent} classes.
 *
 * <p>Example usage:
 * <pre>{@code
 *     var t = new PathTemplate("/chat/{room}");
 *     var vars = new HashMap<String, String>();
 *     if (t.match("/chat/general", vars)) {
 *         // vars == {"room" -> "general"}
 *     }
 * }</pre>
 *
 * @author Jeanfrancois Arcand
 */
public final class PathTemplate {

    private static final Set<Character> RESERVED = Set.of('.', '?', '(', ')');
    private static final String DEFAULT_VAR_REGEX = "[^/]+?";

    private final String template;
    private final Pattern pattern;
    private final List<String> variableNames;
    private final int[] groupIndexes;

    /**
     * Parse a URI template string into a compiled pattern.
     *
     * @param template the template (e.g. {@code /chat/{room}})
     * @throws IllegalArgumentException if template is null, empty, or has invalid syntax
     */
    public PathTemplate(String template) {
        if (template == null || template.isEmpty()) {
            throw new IllegalArgumentException("Template must not be null or empty");
        }
        this.template = template;
        this.variableNames = new ArrayList<>();

        var regex = new StringBuilder();
        var groupCounts = new ArrayList<Integer>();
        int i = 0;

        while (i < template.length()) {
            char c = template.charAt(i);
            if (c == '{') {
                int close = findClosingBrace(template, i);
                String expr = template.substring(i + 1, close).strip();
                int colon = expr.indexOf(':');

                String name;
                String varRegex;
                if (colon >= 0) {
                    name = expr.substring(0, colon).strip();
                    varRegex = expr.substring(colon + 1).strip();
                } else {
                    name = expr;
                    varRegex = DEFAULT_VAR_REGEX;
                }

                if (name.isEmpty()) {
                    throw new IllegalArgumentException("Empty variable name at position " + i);
                }

                variableNames.add(name);
                regex.append('(').append(varRegex).append(')');

                // Count groups in this variable's regex to handle nested groups
                int gc = Pattern.compile(varRegex).matcher("").groupCount() + 1;
                groupCounts.add(gc);

                i = close + 1;
            } else {
                if (RESERVED.contains(c)) {
                    regex.append('\\');
                }
                regex.append(c);
                i++;
            }
        }

        this.pattern = Pattern.compile(regex.toString());
        this.groupIndexes = buildGroupIndexes(groupCounts);
    }

    /**
     * Match a URI against this template.
     *
     * @param uri   the URI to match
     * @param vars  map to populate with variable name → value pairs (cleared first)
     * @return true if the URI matches, false otherwise
     */
    public boolean match(CharSequence uri, Map<String, String> vars) {
        if (uri == null || vars == null) {
            return false;
        }

        Matcher m = pattern.matcher(uri);
        if (!m.matches()) {
            return false;
        }

        vars.clear();
        for (int i = 0; i < variableNames.size(); i++) {
            String name = variableNames.get(i);
            int groupIdx = groupIndexes[i];
            String value = m.group(groupIdx);

            // If the same variable appears twice, values must agree
            String prev = vars.get(name);
            if (prev != null && !prev.equals(value)) {
                return false;
            }
            vars.put(name, value);
        }
        return true;
    }

    /**
     * @return the original template string
     */
    public String getTemplate() {
        return template;
    }

    @Override
    public String toString() {
        return template;
    }

    private static int findClosingBrace(String s, int openPos) {
        int depth = 1;
        for (int i = openPos + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        throw new IllegalArgumentException(
                "Unclosed '{' at position " + openPos + " in template: " + s);
    }

    private static int[] buildGroupIndexes(List<Integer> groupCounts) {
        int[] indexes = new int[groupCounts.size()];
        int current = 1;
        for (int i = 0; i < groupCounts.size(); i++) {
            indexes[i] = current;
            current += groupCounts.get(i);
        }
        return indexes;
    }
}
