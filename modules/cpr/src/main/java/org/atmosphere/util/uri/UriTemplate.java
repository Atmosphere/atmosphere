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

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;


/**
 * A URI template.
 *
 * @author Paul.Sandoz@Sun.Com
 */
public class UriTemplate {
    /**
     * Order the templates according according to JAX-RS.
     * <p>
     * Sort the set of matching resource classes using the number of 
     * characters in the regular expression not resulting from template 
     * variables as the primary key, the number of matching groups
     * as a secondary key, and the number of explicit regular expression
     * declarations as the tertiary key.
     */
    static public final Comparator<UriTemplate> COMPARATOR = new Comparator<UriTemplate>() {
        public int compare(UriTemplate o1, UriTemplate o2) {
            if (o1 == null && o2 == null)
                return 0;
            if (o1 == null)
                return 1;
            if (o2 == null)
                return -1;
            
            if (o1 == EMPTY && o2 == EMPTY)
                return 0;
            if (o1 == EMPTY)
                return 1;
            if (o2 == EMPTY)
                return -1;

            // Compare the number of explicit characters
            // Note that it is important that o2 is compared against o1
            // so that a regular expression with say 10 explicit characters
            // is less than a regular expression with say 5 explicit characters.
            int i = o2.getNumberOfExplicitCharacters() - o1.getNumberOfExplicitCharacters();
            if (i != 0) return i;

            // If the number of explicit characters is equal
            // compare the number of template variables
            // Note that it is important that o2 is compared against o1
            // so that a regular expression with say 10 template variables
            // is less than a regular expression with say 5 template variables.
            i = o2.getNumberOfTemplateVariables() - o1.getNumberOfTemplateVariables();
            if (i != 0) return i;

            // If the number of template variables is equal
            // compare the number of explicit regexes
            i = o2.getNumberOfExplicitRegexes() - o1.getNumberOfExplicitRegexes();
            if (i != 0) return i;
            
            // If the number of explicit characters and template variables
            // are equal then comapre the regexes
            // The order does not matter as long as templates with different
            // explicit characters are distinguishable
            return o2.pattern.getRegex().compareTo(o1.pattern.getRegex());
        }
    };
    
    /**
     * The regular expression for matching URI templates and names.
     */
    private static final Pattern TEMPLATE_NAMES_PATTERN = Pattern.compile("\\{(\\w[-\\w\\.]*)\\}");
    
    /**
     * The empty URI template that matches the null or empty URI path
     */
    public static final UriTemplate EMPTY = new UriTemplate();
    
    /**
     * The URI template.
     */
    private final String template;
        
    /**
     * The normalized URI template. Any explicit regex are removed to leave
     * the template variables.
     */
    private final String normalizedTemplate;

    /**
     * The pattern generated from the template
     */
    private final UriPattern pattern;
    
    /**
     * True if the URI template ends in a '/' character.
     */
    private final boolean endsWithSlash;
    
    /**
     * The template variables in the URI template.
     */
    private final List<String> templateVariables;

    /**
     * The number of explicit regular expressions declared for template
     * variables.
     */
    private final int numOfExplicitRegexes;

    /**
     * The number of characters in the regular expression not resulting
     * from conversion of template variables.
     */
    private final int numOfCharacters;

    /**
     * Constructor for NULL template
     */
    private UriTemplate() {
        this.template = this.normalizedTemplate = "";
        this.pattern = UriPattern.EMPTY;
        this.endsWithSlash = false;
        this.templateVariables = Collections.emptyList();
        this.numOfExplicitRegexes = this.numOfCharacters = 0;
    }
    
    /**
     * Construct a new URI template.
     * <p>
     * The template will be parsed to extract template variables.
     * <p>
     * A specific regular expression will be generated from the template
     * to match URIs according to the template and map template variables to
     * template values.
     * <p>
     * @param template the template.
     * @throws java.util.regex.PatternSyntaxException if the specified
     *         regular expression could not be generated
     * @throws IllegalArgumentException if the template is null or
     *         an empty string.
     */
    public UriTemplate(String template) throws
            PatternSyntaxException, IllegalArgumentException {
        this(new UriTemplateParser(template));
    }

    /**
     * Construct a new URI template.
     * <p>
     * The template will be parsed to extract template variables.
     * <p>
     * A specific regular expression will be generated from the template
     * to match URIs according to the template and map template variables to
     * template values.
     * <p>
     * @param templateParser the parser to parse the template.
     * @throws java.util.regex.PatternSyntaxException if the specified
     *         regular expression could not be generated
     * @throws IllegalArgumentException if the template is null or
     *         an empty string.
     */
    protected UriTemplate(UriTemplateParser templateParser) throws 
            PatternSyntaxException, IllegalArgumentException {
        this.template = templateParser.getTemplate();
        
        this.normalizedTemplate = templateParser.getNormalizedTemplate();

        this.pattern = createUriPattern(templateParser);

        this.numOfExplicitRegexes = templateParser.getNumberOfExplicitRegexes();
        
        this.numOfCharacters = templateParser.getNumberOfLiteralCharacters();

        this.endsWithSlash = template.charAt(template.length() - 1) == '/';

        this.templateVariables = templateParser.getNames();

        templateParser.destroy();
    }

    public UriTemplate destroy() {
        templateVariables.clear();
        return this;
    }

    /**
     * Create the URI pattern from a URI template parser.
     *
     * @param templateParser the URI template parser.
     * @return the URI pattern.
     */
    protected UriPattern createUriPattern(UriTemplateParser templateParser) {
        return new UriPattern(templateParser.getPattern(), templateParser.getGroupIndexes()); 
    }
    
    /**
     * Get the URI template as a String.
     * @return the URI template.
     */
    public final String getTemplate() {
        return template;
    }

    /**
     * Get the URI pattern.
     * 
     * @return the URI pattern.
     */
    public final UriPattern getPattern() {
        return pattern;
    }
    
    /**
     * @return true if the template ends in a '/', otherwise false.
     */
    public final boolean endsWithSlash() {
        return endsWithSlash;
    }
    
    /**
     * Get the list of template variables for the template.
     * @return the list of template variables.
     */
    public final List<String> getTemplateVariables() {
        return templateVariables;
    }
    
    /**
     * Ascertain if a template variable is a member of this
     * template.
     * @param name name The template variable.
     * @return true if the template variable is a member of the template, otherwise
     * false.
     */
    public final boolean isTemplateVariablePresent(String name) {
        for (String s : templateVariables) {
            if (s.equals(name))
                return true;
        }
        
        return false;
    }

    /**
     * Get the number of explicit regexes declared in template variables.
     * 
     * @return the number of explicit regexes.
     */
    public final int getNumberOfExplicitRegexes() {
        return numOfExplicitRegexes;
    }

    /** 
     * Get the number of characters in the regular expression not resulting
     * from conversion of template variables.
     * 
     * @return the number of explicit characters
     */
    public final int getNumberOfExplicitCharacters() {
        return numOfCharacters;
    }

    /**
     * Get the number of template variables.
     * @return the number of template variables.
     */
    public final int getNumberOfTemplateVariables() {
        return templateVariables.size();
    }

    /**
     * Match a URI against the template.
     * <p>
     * If the URI matches against the pattern then the template variable to value 
     * map will be filled with template variables as keys and template values as 
     * values.
     * <p>
     * 
     * @param uri the uri to match against the template.
     * @param templateVariableToValue the map where to put template variables (as keys)
     *        and template values (as values). The map is cleared before any
     *        entries are put.
     * @return true if the URI matches the template, otherwise false.
     * @throws IllegalArgumentException if the uri or
     *         templateVariableToValue is null.
     */
    public final boolean match(CharSequence uri, Map<String, String> templateVariableToValue) throws
            IllegalArgumentException {
        if (templateVariableToValue == null) 
            throw new IllegalArgumentException();

        return pattern.match(uri, templateVariables, templateVariableToValue);
    }
    
    /**
     * Match a URI against the template.
     * <p>
     * If the URI matches against the pattern then the template variable to value 
     * map will be filled with template variables as keys and template values as 
     * values.
     * <p>
     * 
     * @param uri the uri to match against the template.
     * @param groupValues the list to store the values of a pattern's 
     *        capturing groups is matching is successful. The values are stored 
     *        in the same order as the pattern's capturing groups.
     * @return true if the URI matches the template, otherwise false.
     * @throws IllegalArgumentException if the uri or
     *         templateVariableToValue is null.
     */
    public final boolean match(CharSequence uri, List<String> groupValues) throws
            IllegalArgumentException {
        if (groupValues == null) 
            throw new IllegalArgumentException();

        return pattern.match(uri, groupValues);
    }
        
    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template variable without a value will be substituted by the 
     * empty string.
     *
     * @param values the map of template variables to template values.
     * @return the URI.
     */
    public final String createURI(Map<String, String> values) {
        StringBuilder b = new StringBuilder();
        // Find all template variables
        Matcher m = TEMPLATE_NAMES_PATTERN.matcher(normalizedTemplate);
        int i = 0;
        while(m.find()) {
            b.append(normalizedTemplate, i, m.start());
            String tValue = values.get(m.group(1));
            if (tValue != null) b.append(tValue);
            i = m.end();
        }
        b.append(normalizedTemplate, i, normalizedTemplate.length());
        return b.toString();
    }
    
    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template varibale without a value will be substituted by the 
     * empty string.
     *
     * @param values the array of template values. The values will be 
     *        substituted in order of occurence of unique template variables.
     * @return the URI.
     */
    public final String createURI(String... values) {
        return createURI(values, 0, values.length);
    }
    
    /**
     * Create a URI by substituting any template variables
     * for corresponding template values.
     * <p>
     * A URI template variable without a value will be substituted by the 
     * empty string.
     *
     * @param values the array of template values. The values will be 
     *        substituted in order of occurence of unique template variables.
     * @param offset the offset into the array
     * @param length the length of the array
     * @return the URI.
     */
    public final String createURI(String[] values, int offset, int length) {
        Map<String, String> mapValues = new HashMap<String, String>();
        StringBuilder b = new StringBuilder();
        // Find all template variables
        Matcher m = TEMPLATE_NAMES_PATTERN.matcher(normalizedTemplate);
        int v = offset;
        length += offset;
        int i = 0;
        while(m.find()) {
            b.append(normalizedTemplate, i, m.start());
            String tVariable = m.group(1);
            // Check if a template variable has already occurred
            // If so use the value to ensure that two or more declarations of 
            // a template variable have the same value
            String tValue = mapValues.get(tVariable);
            if (tValue != null) {
                b.append(tValue);
            } else {
                if (v < length) {
                    tValue = values[v++];
                    if (tValue != null) {
                        mapValues.put(tVariable, tValue);
                        b.append(tValue);
                    }
                }
            }
            i = m.end();
        }
        b.append(normalizedTemplate, i, normalizedTemplate.length());
        return b.toString();
    }
    
    @Override
    public final String toString() {
        return pattern.toString();
    }
    
    /**
     * Hashcode is calculated from String of the regular expression 
     * generated from the template.
     * @return the hash code.
     */
    @Override
    public final int hashCode() {
        return pattern.hashCode();
    }

    /**
     * Equality is calculated from the String of the regular expression 
     * generated from the templates.
     * @param o the reference object with which to compare.
     * @return true if equals, otherwise false.
     */
    @Override
    public final boolean equals(Object o) {
        if (o instanceof UriTemplate) {
            UriTemplate that = (UriTemplate)o;
            return this.pattern.equals(that.pattern);
        } else {
            return false;
        }
    }
    
    /**
     * Construct a URI from the component parts each of which may contain 
     * template variables.
     * <p>
     * A template values is an Object instance MUST support the toString() 
     * method to convert the template value to a String instance.
     *
     * @param scheme the URI scheme component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the template variable to value map
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURI(final String scheme, 
            final String userInfo, final String host, final String port, 
            final String path, final String query, final String fragment,
            final Map<String, ? extends Object> values, final boolean encode) {
        return createURI(scheme, null, userInfo, host, port, path, query, fragment,
                values, encode);
    }
    
    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * A template values is an Object instance MUST support the toString()
     * method to convert the template value to a String instance.
     *
     * @param scheme the URI scheme component
     * @param authority the URI authority component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the template variable to value map
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURI(
            final String scheme, String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Map<String, ? extends Object> values, final boolean encode) {
        Map<String, String> stringValues = new HashMap<String, String>();
        for (Map.Entry<String, ? extends Object> e : values.entrySet()) {
            if (e.getValue() != null)
                stringValues.put(e.getKey(), e.getValue().toString());
        }

        return createURIWithStringValues(scheme, authority,
                userInfo, host, port, path, query, fragment,
                stringValues, encode);
    }

    /**
     * Construct a URI from the component parts each of which may contain 
     * template variables.
     * <p>
     * A template value is an Object instance that MUST support the toString() 
     * method to convert the template value to a String instance.
     *
     * @param scheme the URI scheme component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the template variable to value map
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURIWithStringValues(final String scheme, 
            final String userInfo, final String host, final String port, 
            final String path, final String query, final String fragment,
            final Map<String, ? extends Object> values, final boolean encode) {
        return createURIWithStringValues(scheme, null,
                userInfo, host, port, path, query, fragment,
                values, encode);
    }
    
    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * A template value is an Object instance that MUST support the toString()
     * method to convert the template value to a String instance.
     *
     * @param scheme the URI scheme component
     * @param authority the URI authority info component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the template variable to value map
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURIWithStringValues(
            final String scheme, final String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Map<String, ? extends Object> values, final boolean encode) {

        StringBuilder sb = new StringBuilder();

        if (scheme != null)
            createURIComponent(UriComponent.Type.SCHEME, scheme, values, false, sb).
                    append(':');

        if (userInfo != null || host != null || port != null) {
            sb.append("//");

            if (userInfo != null && userInfo.length() > 0)
                createURIComponent(UriComponent.Type.USER_INFO, userInfo, values, encode, sb).
                    append('@');

            if (host != null) {
                // TODO check IPv6 address
                createURIComponent(UriComponent.Type.HOST, host, values, encode, sb);
            }

            if (port != null && port.length() > 0) {
                sb.append(':');
                createURIComponent(UriComponent.Type.PORT, port, values, false, sb);
            }
        } else if (authority != null) {
            sb.append("//");
            
            createURIComponent(UriComponent.Type.AUTHORITY, authority, values, encode, sb);
        }

        if (path != null)
            createURIComponent(UriComponent.Type.PATH, path, values, encode, sb);

        if (query != null && query.length() > 0) {
            sb.append('?');
            createURIComponent(UriComponent.Type.QUERY_PARAM, query, values, encode, sb);
        }

        if (fragment != null && fragment.length() > 0) {
            sb.append('#');
            createURIComponent(UriComponent.Type.FRAGMENT, fragment, values, encode, sb);
        }
        return sb.toString();
    }

    private static StringBuilder createURIComponent(final UriComponent.Type t, 
            String template,
            final Map<String, ? extends Object> values, 
            final boolean encode, 
            final StringBuilder b) {
        if (template.indexOf('{') == -1) {
            b.append(template);
            return b;
        }
        
        // Find all template variables
        template = new UriTemplateParser(template).getNormalizedTemplate();
        final Matcher m = TEMPLATE_NAMES_PATTERN.matcher(template);

        int i = 0;
        while(m.find()) {
            b.append(template, i, m.start());
            Object tValue = values.get(m.group(1));
            if (tValue != null) {
                if (encode)
                    tValue = UriComponent.encode(tValue.toString(), t);
                else
                    tValue =UriComponent.contextualEncode(tValue.toString(), t);
                b.append(tValue);
            } else {
                throw templateVariableHasNoValue(m.group(1));
            }
            i = m.end();
        }
        b.append(template, i, template.length());
        return b;
    }
    
    /**
     * Construct a URI from the component parts each of which may contain 
     * template variables.
     * <p>
     * The template values are an array of Object and each Object instance
     * MUST support the toString() method to convert the template value to
     * a String instance.
     * 
     * @param scheme the URI scheme component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the array of template values
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURI(final String scheme, 
            final String userInfo, final String host, final String port, 
            final String path, final String query, final String fragment,
            final Object[] values, final boolean encode) {
        return createURI(scheme, null,
                userInfo, host, port, path, query, fragment,
                values, encode);
    }
    
    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     * <p>
     * The template values are an array of Object and each Object instance
     * MUST support the toString() method to convert the template value to
     * a String instance.
     *
     * @param scheme the URI scheme component
     * @param authority the URI authority component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the array of template values
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURI(
            final String scheme, String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final Object[] values, final boolean encode) {

        String[] stringValues = new String[values.length];
        for (int i = 0; i < values.length; i++) {
            if (values[i] != null)
                stringValues[i] = values[i].toString();
        }

        return createURIWithStringValues(
                scheme, authority,
                userInfo, host, port, path, query, fragment,
                stringValues, encode);
    }

    /**
     * Construct a URI from the component parts each of which may contain 
     * template variables.
     *
     * @param scheme the URI scheme component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the array of template values
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURIWithStringValues(final String scheme, 
            final String userInfo, final String host, final String port, 
            final String path, final String query, final String fragment,
            final String[] values, final boolean encode) {
        return createURIWithStringValues(
                scheme, null,
                userInfo, host, port, path, query, fragment,
                values, encode);
    }
    
    /**
     * Construct a URI from the component parts each of which may contain
     * template variables.
     *
     * @param scheme the URI scheme component
     * @param authority the URI authority component
     * @param userInfo the URI user info component
     * @param host the URI host component
     * @param port the URI port component
     * @param path the URI path component
     * @param query the URI query componnet
     * @param fragment the URI fragment component
     * @param values the array of template values
     * @param encode if true encode a template value according to the correspond
     *        component type of the associated template variable, otherwise
     *        contextually encode the template value
     * @return a URI
     */
    public final static String createURIWithStringValues(
            final String scheme, final String authority,
            final String userInfo, final String host, final String port,
            final String path, final String query, final String fragment,
            final String[] values, final boolean encode) {

        final Map<String, String> mapValues = new HashMap<String, String>();
        final StringBuilder sb = new StringBuilder();
        int offset = 0;

        if (scheme != null) {
            offset = createURIComponent(UriComponent.Type.SCHEME, scheme, values,
                    offset, false, mapValues, sb);
            sb.append(':');
        }

        if (userInfo != null || host != null || port != null) {
            sb.append("//");

            if (userInfo != null && userInfo.length() > 0) {
                offset = createURIComponent(UriComponent.Type.USER_INFO, userInfo, values,
                        offset, encode, mapValues, sb);
                sb.append('@');
            }

            if (host != null) {
                // TODO check IPv6 address
                offset = createURIComponent(UriComponent.Type.HOST, host, values,
                        offset, encode, mapValues, sb);
            }

            if (port != null && port.length() > 0) {
                sb.append(':');
                offset = createURIComponent(UriComponent.Type.PORT, port, values,
                        offset, false, mapValues, sb);
            }
        } else if (authority != null) {
            sb.append("//");
            
            offset = createURIComponent(UriComponent.Type.AUTHORITY, authority, values,
                    offset, encode, mapValues, sb);
        }

        if (path != null)
            offset = createURIComponent(UriComponent.Type.PATH, path, values,
                    offset, encode, mapValues, sb);

        if (query != null && query.length() > 0) {
            sb.append('?');
            offset = createURIComponent(UriComponent.Type.QUERY_PARAM, query, values,
                    offset, encode, mapValues, sb);
        }

        if (fragment != null && fragment.length() > 0) {
            sb.append('#');
            offset = createURIComponent(UriComponent.Type.FRAGMENT, fragment, values,
                    offset, encode, mapValues, sb);
        }
        return sb.toString();
    }

    private static int createURIComponent(final UriComponent.Type t, 
            String template,
            final String[] values, final int offset,
            final boolean encode, 
            final Map<String, String> mapValues,
            final StringBuilder b) {
        if (template.indexOf('{') == -1) {
            b.append(template);
            return offset;
        }

        // Find all template variables
        template = new UriTemplateParser(template).getNormalizedTemplate();
        final Matcher m = TEMPLATE_NAMES_PATTERN.matcher(template);
        int v = offset;
        int i = 0;
        while(m.find()) {
            b.append(template, i, m.start());
            final String tVariable = m.group(1);
            // Check if a template variable has already occurred
            // If so use the value to ensure that two or more declarations of 
            // a template variable have the same value
            String tValue = mapValues.get(tVariable);
            if (tValue != null) {
                b.append(tValue);
            } else if (v < values.length) {
                tValue = values[v++];
                if (tValue != null) {
                    if (encode)
                        tValue = UriComponent.encode(tValue, t);
                    else
                        tValue = UriComponent.contextualEncode(tValue, t);
                    mapValues.put(tVariable, tValue);
                    b.append(tValue);
                } else {
                    throw templateVariableHasNoValue(tVariable);
                }
            } else {
                throw templateVariableHasNoValue(tVariable);
            }
            i = m.end();
        }
        b.append(template, i, template.length());
        return v;
    }

    private static IllegalArgumentException templateVariableHasNoValue(String tVariable) {
        return new IllegalArgumentException("The template variable, " +
                                tVariable + ", has no value");
    }
}
