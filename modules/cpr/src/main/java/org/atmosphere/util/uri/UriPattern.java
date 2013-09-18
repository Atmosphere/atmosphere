/*
 * Copyright 2013 Jeanfrancois Arcand
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
/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2010-2013 Oracle and/or its affiliates. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License.  You can
 * obtain a copy of the License at
 * http://glassfish.java.net/public/CDDL+GPL_1_1.html
 * or packager/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at packager/legal/LICENSE.txt.
 *
 * GPL Classpath Exception:
 * Oracle designates this particular file as subject to the "Classpath"
 * exception as provided by Oracle in the GPL Version 2 section of the License
 * file that accompanied this code.
 *
 * Modifications:
 * If applicable, add the following below the License Header, with the fields
 * enclosed by brackets [] replaced by your own identifying information:
 * "Portions Copyright [year] [name of copyright owner]"
 *
 * Contributor(s):
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */

package org.atmosphere.util.uri;

import java.util.List;
import java.util.Map;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A URI pattern for matching a URI against a regular expression
 * and returning capturing group values for any capturing groups present in
 * the expression.
 *
 * @author Paul.Sandoz@Sun.Com
 */
public class UriPattern {
    /**
     * The empty URI pattern that matches the null or empty URI path
     */
    public static final UriPattern EMPTY = new UriPattern();

    /**
     * The regular expression for matching URIs
     * and obtaining capturing group values.
     */
    private final String regex;

    /**
     * The compiled regular expression of {@link #regex}
     */
    private final Pattern regexPattern;

    private final int[] groupIndexes;

    /**
     *  Construct an empty pattern.
     */
    protected UriPattern() {
        this.regex = "";
        this.regexPattern = null;
        this.groupIndexes = null;
    }

    /**
     * Construct a new URI pattern.
     *
     * @param regex the regular expression. If the expression is null or an
     *        empty string then the pattern will only match a null or empty
     *        URI path.
     * @throws java.util.regex.PatternSyntaxException if the
     *         regular expression could not be compiled
     */
    public UriPattern(String regex) {
        this(regex, UriTemplateParser.EMPTY_INT_ARRAY);
    }

    /**
     * Construct a new URI pattern.
     *
     * @param regex the regular expression. If the expression is null or an
     *        empty string then the pattern will only match a null or empty
     *        URI path.
     * @param groupIndexes the array of group indexes to capturing groups.
     * @throws java.util.regex.PatternSyntaxException if the
     *         regular expression could not be compiled
     */
    public UriPattern(String regex, int[] groupIndexes) {
        this(compile(regex), groupIndexes);
    }

    private static Pattern compile(String regex) {
        return (regex == null || regex.length() == 0) ? null : Pattern.compile(regex);
    }

    /**
     * Construct a new URI pattern.
     *
     * @param regexPattern  the regular expression pattern
     * @throws IllegalArgumentException if the regexPattern is null.
     */
    public UriPattern(Pattern regexPattern) {
        this(regexPattern, UriTemplateParser.EMPTY_INT_ARRAY);
    }

    /**
     * Construct a new URI pattern.
     *
     * @param regexPattern the regular expression pattern
     * @param groupIndexes the array of group indexes to capturing groups.
     * @throws IllegalArgumentException if the regexPattern is null.
     */
    public UriPattern(Pattern regexPattern, int[] groupIndexes) {
        if (regexPattern == null)
            throw new IllegalArgumentException();

        this.regex = regexPattern.toString();
        this.regexPattern = regexPattern;
        this.groupIndexes = groupIndexes;
    }

    /**
     * Get the regular expression.
     *
     * @return the regular expression.
     */
    public final String getRegex() {
        return regex;
    }

    /**
     * Get the group indexes.
     *
     * @return the group indexes.
     */
    public final int[] getGroupIndexes() {
        return groupIndexes;
    }

    private static final class EmptyStringMatchResult implements MatchResult {
        public int start() {
            return 0;
        }

        public int start(int group) {
            if (group != 0)
                throw new IndexOutOfBoundsException();
            return start();
        }

        public int end() {
            return 0;
        }

        public int end(int group) {
            if (group != 0)
                throw new IndexOutOfBoundsException();
            return end();
        }

        public String group() {
            return "";
        }

        public String group(int group) {
            if (group != 0)
                throw new IndexOutOfBoundsException();
            return group();
        }

        public int groupCount() {
            return 0;
        }
    }

    private static final EmptyStringMatchResult EMPTY_STRING_MATCH_RESULT = new EmptyStringMatchResult();

    private final class GroupIndexMatchResult implements MatchResult {
        private final MatchResult r;

        GroupIndexMatchResult(MatchResult r) {
            this.r = r;
        }

        public int start() {
            return r.start();
        }

        public int start(int group) {
            if (group > groupCount())
                throw new IndexOutOfBoundsException();

            return (group > 0) ? r.start(groupIndexes[group - 1]) : r.start();
        }

        public int end() {
            return r.end();
        }

        public int end(int group) {
            if (group > groupCount())
                throw new IndexOutOfBoundsException();

            return (group > 0) ? r.end(groupIndexes[group - 1]) : r.end();
        }

        public String group() {
            return r.group();
        }

        public String group(int group) {
            if (group > groupCount())
                throw new IndexOutOfBoundsException();

            return (group > 0) ? r.group(groupIndexes[group - 1]) : r.group();
        }

        public int groupCount() {
            return groupIndexes.length - 1;
        }
    }

    /**
     * Match a URI against the pattern.
     *
     * @param uri the uri to match against the template.
     * @return the match result, otherwise null if no match occurs.
     */
    public final MatchResult match(CharSequence uri) {
        // Check for match against the empty pattern
        if (uri == null || uri.length() == 0)
            return (regexPattern == null) ? EMPTY_STRING_MATCH_RESULT : null;
        else if (regexPattern == null)
            return null;

        // Match the URI to the URI template regular expression
        Matcher m = regexPattern.matcher(uri);
        if (!m.matches())
            return null;

        return (groupIndexes.length > 0) ? new GroupIndexMatchResult(m) : m;
    }

    /**
     * Match a URI against the pattern.
     * <p>
     * If the URI matches against the pattern then the capturing group values
     * (if any) will be added to a list passed in as parameter.
     *
     * @param uri the uri to match against the template.
     * @param groupValues the list to add the values of a pattern's
     *        capturing groups if matching is successful. The values are added
     *        in the same order as the pattern's capturing groups. The list
     *        is cleared before values are added.
     * @return true if the URI matches the pattern, otherwise false.
     * @throws IllegalArgumentException if the uri or
     *         capturingGroupValues is null.
     */
    public final boolean match(CharSequence uri, List<String> groupValues) {
        if (groupValues == null)
            throw new IllegalArgumentException();

        // Check for match against the empty pattern
        if (uri == null || uri.length() == 0)
            return (regexPattern == null) ? true : false;
        else if (regexPattern == null)
            return false;

        // Match the URI to the URI template regular expression
        Matcher m = regexPattern.matcher(uri);
        if (!m.matches())
            return false;

        groupValues.clear();
        if (groupIndexes.length > 0) {
            for (int i = 0; i < groupIndexes.length - 1; i++) {
                groupValues.add(m.group(groupIndexes[i]));
            }
        } else {
            for (int i = 1; i <= m.groupCount(); i++) {
                groupValues.add(m.group(i));
            }
        }

        // TODO check for consistency of different capturing groups
        // that must have the same value

        return true;
    }

    /**
     * Match a URI against the pattern.
     * <p>
     * If the URI matches against the pattern then the capturing group values
     * (if any) will be added to a map passed in as parameter.
     *
     * @param uri the uri to match against the template.
     * @param groupNames the list names associated with a pattern's
     *        capturing groups. The names MUST be in the same order as the
     *        pattern's capturing groups and the size MUST be equal to or
     *        less than the number of capturing groups.
     * @param groupValues the map to add the values of a pattern's
     *        capturing groups if matching is successful. A values is put
     *        into the map using the group name associated with the
     *        capturing group. The map is cleared before values are added.
     * @return true if the URI matches the pattern, otherwise false.
     * @throws IllegalArgumentException if the uri or
     *         capturingGroupValues is null.
     */
    public final boolean match(CharSequence uri,
            List<String> groupNames, Map<String, String> groupValues) {
        if (groupValues == null)
            throw new IllegalArgumentException();

        // Check for match against the empty pattern
        if (uri == null || uri.length() == 0)
            return (regexPattern == null) ? true : false;
        else if (regexPattern == null)
            return false;

        // Match the URI to the URI template regular expression
        Matcher m = regexPattern.matcher(uri);
        if (!m.matches())
            return false;

        // Assign the matched group values to group names
        groupValues.clear();
        for (int i = 0; i < groupNames.size(); i++) {
            String name  = groupNames.get(i);
            String currentValue = m.group((groupIndexes.length > 0) ? groupIndexes[i] : i + 1);

            // Group names can have the same name occuring more than once, 
            // check that groups values are same.
            String previousValue = groupValues.get(name);
            if (previousValue != null && !previousValue.equals(currentValue))
                return false;

            groupValues.put(name, currentValue);
        }

        return true;
    }

    @Override
    public final int hashCode() {
        return regex.hashCode();
    }

    @Override
    public final boolean equals(Object obj) {
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        final UriPattern that = (UriPattern) obj;
        if (this.regex != that.regex &&
                (this.regex == null || !this.regex.equals(that.regex))) {
            return false;
        }
        return true;
    }

    @Override
    public final String toString() {
        return regex;
    }
}
