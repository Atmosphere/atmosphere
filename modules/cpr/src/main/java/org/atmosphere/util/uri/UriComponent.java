/*
 * Copyright 2008-2020 Async-IO.org
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
 * Copyright (c) 2010-2018 Oracle and/or its affiliates. All rights reserved.
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

import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;

/**
 * Utility class for validating, encoding and decoding components
 * of a URI.
 *
 * @author Paul.Sandoz@Sun.Com
 */
public class UriComponent {

    // TODO rewrite to use masks and not lookup tables
    /**
     * The URI component type.
     */
    public enum Type {

        /**
         * ALPHA / DIGIT / "-" / "." / "_" / "~" characters
         */
        UNRESERVED,
        /**
         * The URI scheme component type.
         */
        SCHEME,
        /**
         * The URI authority component type.
         */
        AUTHORITY,
        /**
         * The URI user info component type.
         */
        USER_INFO,
        /**
         * The URI host component type.
         */
        HOST,
        /**
         * The URI port component type.
         */
        PORT,
        /**
         * The URI path component type.
         */
        PATH,
        /**
         * The URI path component type that is a path segment.
         */
        PATH_SEGMENT,
        /**
         * The URI path component type that is a matrix parameter.
         */
        MATRIX_PARAM,
        /**
         * The URI query component type.
         */
        QUERY,
        /**
         * The URI query component type that is a query parameter.
         */
        QUERY_PARAM,
        /**
         * The URI fragment component type.
         */
        FRAGMENT,
    }

    private UriComponent() {
    }

    /**
     * Validates the legal characters of a percent-encoded string that 
     * represents a URI component type.
     *
     * @param s the encoded string.
     * @param t the URI compontent type identifying the legal characters.
     * @throws IllegalArgumentException if the encoded string contains illegal
     *         characters.
     */
    public static void validate(String s, Type t) {
        validate(s, t, false);
    }

    /**
     * Validates the legal characters of a percent-encoded string that 
     * represents a URI component type.
     *
     * @param s the encoded string.
     * @param t the URI compontent type identifying the legal characters.
     * @param template true if the encoded string contains URI template variables
     * @throws IllegalArgumentException if the encoded string contains illegal
     *         characters.
     */
    public static void validate(String s, Type t, boolean template) {
        int i = _valid(s, t, template);
        if (i > -1) // TODO localize
        {
            throw new IllegalArgumentException("The string '" + s +
                    "' for the URI component " + t +
                    " contains an invalid character, '" + s.charAt(i) + "', at index " + i);
        }
    }

    /**
     * Validates the legal characters of a percent-encoded string that 
     * represents a URI component type.
     *
     * @param s the encoded string.
     * @param t the URI compontent type identifying the legal characters.
     * @return true if the encoded string is valid, otherwise false.
     */
    public static boolean valid(String s, Type t) {
        return valid(s, t, false);
    }

    /**
     * Validates the legal characters of a percent-encoded string that 
     * represents a URI component type.
     *
     * @param s the encoded string.
     * @param t the URI compontent type identifying the legal characters.
     * @param template true if the encoded string contains URI template variables
     * @return true if the encoded string is valid, otherwise false.
     */
    public static boolean valid(String s, Type t, boolean template) {
        return _valid(s, t, template) == -1;
    }

    private static int _valid(String s, Type t, boolean template) {
        boolean[] table = ENCODING_TABLES[t.ordinal()];

        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if ((c < 0x80 && c != '%' && !table[c]) || c >= 0x80) {
                if (!template || (c != '{' && c != '}')) {
                    return i;
                }
            }
        }
        return -1;
    }

    /**
     * Contextually encodes the characters of string that are either non-ASCII
     * characters or are ASCII characters that must be percent-encoded using the
     * UTF-8 encoding. Percent-encoded characters will be recognized and not
     * double encoded.
     *
     * @param s the string to be encoded.
     * @param t the URI compontent type identifying the ASCII characters that 
     *          must be percent-encoded.
     * @return the encoded string.
     */
    public static String contextualEncode(String s, Type t) {
        return _encode(s, t, false, true);
    }

    /**
     * Contextually encodes the characters of string that are either non-ASCII
     * characters or are ASCII characters that must be percent-encoded using the
     * UTF-8 encoding. Percent-encoded characters will be recognized and not
     * double encoded.
     *
     * @param s the string to be encoded.
     * @param t the URI compontent type identifying the ASCII characters that 
     *          must be percent-encoded.
     * @param template true if the encoded string contains URI template variables
     * @return the encoded string.
     */
    public static String contextualEncode(String s, Type t, boolean template) {
        return _encode(s, t, template, true);
    }

    /**
     * Encodes the characters of string that are either non-ASCII characters 
     * or are ASCII characters that must be percent-encoded using the 
     * UTF-8 encoding.
     *
     * @param s the string to be encoded.
     * @param t the URI compontent type identifying the ASCII characters that 
     *          must be percent-encoded.
     * @return the encoded string.
     */
    public static String encode(String s, Type t) {
        return _encode(s, t, false, false);
    }

    /**
     * Encodes the characters of string that are either non-ASCII characters 
     * or are ASCII characters that must be percent-encoded using the 
     * UTF-8 encoding.
     *
     * @param s the string to be encoded.
     * @param t the URI compontent type identifying the ASCII characters that 
     *          must be percent-encoded.
     * @param template true if the encoded string contains URI template variables
     * @return the encoded string.
     */
    public static String encode(String s, Type t, boolean template) {
        return _encode(s, t, template, false);
    }

    /**
     * Encodes a string with template parameters names present, specifically the
     * characters '{' and '}' will be percent-encoded.
     * 
     * @param s the string with zero or more template parameters names
     * @return the string with encoded template parameters names.
     */
    public static String encodeTemplateNames(String s) {
        int i = s.indexOf('{');
        if (i != -1)
            s = s.replace("{", "%7B");
        i = s.indexOf('}');
        if (i != -1)
            s = s.replace("}", "%7D");

        return s;
    }

    private static String _encode(String s, Type t, boolean template, boolean contextualEncode) {
        final boolean[] table = ENCODING_TABLES[t.ordinal()];

        StringBuilder sb = null;
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            if (c < 0x80 && table[c]) {
                if (sb != null) sb.append(c);
            } else {
                if (template && (c == '{' || c == '}')) {
                    if (sb != null) sb.append(c);
                    continue;
                } else if (contextualEncode) {
                    if (c == '%' && i + 2 < s.length()) {
                        if (isHexCharacter(s.charAt(i + 1)) &&
                                isHexCharacter(s.charAt(i + 2))) {
                            if (sb != null)
                                sb.append('%').append(s.charAt(i + 1)).append(s.charAt(i + 2));
                            i += 2;
                            continue;
                        }
                    }
                }

                if (sb == null) {
                    sb = new StringBuilder();
                    sb.append(s.substring(0, i));
                }

                if (c < 0x80) {
                    if (c == ' ' && (t == Type.QUERY_PARAM)) {
                        sb.append('+');
                    } else {
                        appendPercentEncodedOctet(sb, c);
                    }
                } else {
                    appendUTF8EncodedCharacter(sb, c);
                }
            }
        }

        return (sb == null) ? s : sb.toString();
    }
    private final static char[] HEX_DIGITS = {
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'A', 'B', 'C', 'D', 'E', 'F'
    };

    private static void appendPercentEncodedOctet(StringBuilder sb, int b) {
        sb.append('%');
        sb.append(HEX_DIGITS[b >> 4]);
        sb.append(HEX_DIGITS[b & 0x0F]);
    }

    private static void appendUTF8EncodedCharacter(StringBuilder sb, char c) {
        final ByteBuffer bb = UTF_8_CHARSET.encode("" + c);

        while (bb.hasRemaining()) {
            appendPercentEncodedOctet(sb, bb.get() & 0xFF);
        }
    }
    private static final String[] SCHEME = {"0-9", "A-Z", "a-z", "+", "-", "."};
    private static final String[] UNRESERVED = {"0-9", "A-Z", "a-z", "-", ".", "_", "~"};
    private static final String[] SUB_DELIMS = {"!", "$", "&", "'", "(", ")", "*", "+", ",", ";", "="};
    private static final boolean[][] ENCODING_TABLES = creatingEncodingTables();

    private static boolean[][] creatingEncodingTables() {
        boolean[][] tables = new boolean[Type.values().length][];

        List<String> l = new ArrayList<String>();
        l.addAll(Arrays.asList(SCHEME));
        tables[Type.SCHEME.ordinal()] = creatingEncodingTable(l);

        l.clear();

        l.addAll(Arrays.asList(UNRESERVED));
        tables[Type.UNRESERVED.ordinal()] = creatingEncodingTable(l);

        l.addAll(Arrays.asList(SUB_DELIMS));

        tables[Type.HOST.ordinal()] = creatingEncodingTable(l);

        tables[Type.PORT.ordinal()] = creatingEncodingTable(Arrays.asList("0-9"));

        l.add(":");

        tables[Type.USER_INFO.ordinal()] = creatingEncodingTable(l);

        l.add("@");

        tables[Type.AUTHORITY.ordinal()] = creatingEncodingTable(l);

        tables[Type.PATH_SEGMENT.ordinal()] = creatingEncodingTable(l);
        tables[Type.PATH_SEGMENT.ordinal()][';'] = false;

        tables[Type.MATRIX_PARAM.ordinal()] = tables[Type.PATH_SEGMENT.ordinal()].clone();
        tables[Type.MATRIX_PARAM.ordinal()]['='] = false;

        l.add("/");

        tables[Type.PATH.ordinal()] = creatingEncodingTable(l);

        l.add("?");

        tables[Type.QUERY.ordinal()] = creatingEncodingTable(l);

        tables[Type.FRAGMENT.ordinal()] = tables[Type.QUERY.ordinal()];

        tables[Type.QUERY_PARAM.ordinal()] = creatingEncodingTable(l);
        tables[Type.QUERY_PARAM.ordinal()]['='] = false;
        tables[Type.QUERY_PARAM.ordinal()]['+'] = false;
        tables[Type.QUERY_PARAM.ordinal()]['&'] = false;

        return tables;
    }

    private static boolean[] creatingEncodingTable(List<String> allowed) {
        boolean[] table = new boolean[0x80];
        for (String range : allowed) {
            if (range.length() == 1) {
                table[range.charAt(0)] = true;
            } else if (range.length() == 3 && range.charAt(1) == '-') {
                for (int i = range.charAt(0); i <= range.charAt(2); i++) {
                    table[i] = true;
                }
            }
        }

        return table;
    }
    private static final Charset UTF_8_CHARSET = Charset.forName("UTF-8");

    /**
     * Decodes characters of a string that are percent-encoded octets using 
     * UTF-8 decoding (if needed).
     * <p>
     * It is assumed that the string is valid according to an (unspecified) URI 
     * component type. If a sequence of contiguous percent-encoded octets is 
     * not a valid UTF-8 character then the octets are replaced with '\uFFFD'.
     * <p>
     * If the URI component is of type HOST then any "%" found between "[]" is 
     * left alone. It is an IPv6 literal with a scope_id.
     * <p>
     * If the URI component is of type QUERY_PARAM then any "+" is decoded as
     * as ' '.
     * <p>
     * @param s the string to be decoded.
     * @param t the URI component type, may be null.
     * @return the decoded string.
     * @throws IllegalArgumentException if a malformed percent-encoded octet is
     *         detected
     */
    public static String decode(String s, Type t) {
        if (s == null) {
            throw new IllegalArgumentException();
        }

        final int n = s.length();
        if (n == 0) {
            return s;
        }

        // If there are no percent-escaped octets
        if (s.indexOf('%') < 0) {
            // If there are no '+' characters for query param
            if (t == Type.QUERY_PARAM) {
                if (s.indexOf('+') < 0) {
                    return s;
                }
            } else {
                return s;
            }
        } else {
            // Malformed percent-escaped octet at the end
            if (n < 2) // TODO localize
            {
                throw new IllegalArgumentException("Malformed percent-encoded octet at index 1");
            }

            // Malformed percent-escaped octet at the end
            if (s.charAt(n - 2) == '%') // TODO localize
            {
                throw new IllegalArgumentException("Malformed percent-encoded octet at index " + (n - 2));
            }
        }

        if (t == null)
            return decode(s, n);
        
        switch (t) {
            case HOST :
                return decodeHost(s, n);
            case QUERY_PARAM :
                return decodeQueryParam(s, n);
            default :
                return decode(s, n);
        }
    }

    /**
     * Decode the query component of a URI.
     * 
     * @param u the URI.
     * @param decode true if the query parameters of the query component
     *        should be in decoded form.
     * @return the multivalued map of query parameters.
     */
    public static Map<String, String> decodeQuery(URI u, boolean decode) {
        return decodeQuery(u.getRawQuery(), decode);
    }

    /**
     * Decode the query component of a URI.
     * 
     * @param q the query component in encoded form.
     * @param decode true of the query parameters of the query component
     *        should be in decoded form.
     * @return the multivalued map of query parameters.
     */
    public static Map<String, String> decodeQuery(String q, boolean decode) {
        Map<String, String> queryParameters = new HashMap<String, String>();

        if (q == null || q.length() == 0) {
            return queryParameters;
        }

        int s = 0, e = 0;
        do {
            e = q.indexOf('&', s);

            if (e == -1) {
                decodeQueryParam(queryParameters, q.substring(s), decode);
            } else if (e > s) {
                decodeQueryParam(queryParameters, q.substring(s, e), decode);
            }
            s = e + 1;
        } while (s > 0 && s < q.length());

        return queryParameters;
    }

    private static void decodeQueryParam(Map<String, String> params,
            String param, boolean decode) {
        try {
            int equals = param.indexOf('=');
            if (equals > 0) {
                params.put(
                        URLDecoder.decode(param.substring(0, equals), "UTF-8"),
                        (decode) ? URLDecoder.decode(param.substring(equals + 1), "UTF-8") : param.substring(equals + 1));
            } else if (equals == 0) {
                // no key declared, ignore
            } else if (param.length() > 0) {
                params.put(
                        URLDecoder.decode(param, "UTF-8"),
                        "");
            }
        } catch (UnsupportedEncodingException ex) {
            // This should never occur
            throw new IllegalArgumentException(ex);
        }
    }

    private static final class PathSegmentImpl  {

        private static final PathSegmentImpl EMPTY_PATH_SEGMENT = new PathSegmentImpl("", false);
        private final String path;
        private final Map<String, String> matrixParameters;

        PathSegmentImpl(String path, boolean decode) {
            this(path, decode, new HashMap<String, String> ());
        }

        PathSegmentImpl(String path, boolean decode, Map<String, String> matrixParameters) {
            this.path = (decode) ? UriComponent.decode(path, UriComponent.Type.PATH_SEGMENT) : path;
            this.matrixParameters = matrixParameters;
        }

        public String getPath() {
            return path;
        }

        public Map<String, String> getMatrixParameters() {
            return matrixParameters;
        }
    }

    /**
     * Decode the path component of a URI as path segments.
     *
     * @param u the URI. If the path component is an absolute path component
     *        then the leading '/' is ignored and is not considered a delimiator
     *        of a path segment.
     * @param decode true if the path segments of the path component
     *        should be in decoded form.
     * @return the list of path segments.
     */
    public static List<PathSegmentImpl> decodePath(URI u, boolean decode) {
        String rawPath = u.getRawPath();
        if (rawPath != null && rawPath.length() > 0 && rawPath.charAt(0) == '/') {
            rawPath = rawPath.substring(1);
        }
        return decodePath(rawPath, decode);
    }

    /**
     * Decode the path component of a URI as path segments.
     * <p>
     * Any '/' character in the path is considered to be a deliminator
     * between two path segments. Thus if the path is '/' then the path segment
     * list will contain two empty path segments. If the path is "//" then
     * the path segment list will contain three empty path segments. If the path
     * is "/a/" the path segment list will consist of the following path
     * segments in order: "", "a" and "".
     *
     * @param path the path component in encoded form.
     * @param decode true if the path segments of the path component
     *        should be in decoded form.
     * @return the list of path segments.
     */
    public static List<PathSegmentImpl> decodePath(String path, boolean decode) {
        List<PathSegmentImpl> segments = new LinkedList<PathSegmentImpl>();

        if (path == null) {
            return segments;
        }

        int s = 0;
        int e = -1;
        do {
            s = e + 1;
            e = path.indexOf('/', s);

            if (e > s) {
                decodePathSegment(segments, path.substring(s, e), decode);
            } else if (e == s) {
                segments.add(PathSegmentImpl.EMPTY_PATH_SEGMENT);
            }
        } while (e != -1);
        if (s < path.length()) {
            decodePathSegment(segments, path.substring(s), decode);
        } else {
            segments.add(PathSegmentImpl.EMPTY_PATH_SEGMENT);
        }
        return segments;
    }

    public static void decodePathSegment(List<PathSegmentImpl> segments, String segment, boolean decode) {
        int colon = segment.indexOf(';');
        if (colon != -1) {
            segments.add(new PathSegmentImpl(
                    (colon == 0) ? "" : segment.substring(0, colon),
                    decode,
                    decodeMatrix(segment, decode)));
        } else {
            segments.add(new PathSegmentImpl(
                    segment,
                    decode));
        }
    }

    /**
     * Decode the matrix component of a URI path segment.
     *
     * @param pathSegment the path segment component in encoded form.
     * @param decode true if the matrix parameters of the path segment component
     *        should be in decoded form.
     * @return the multivalued map of matrix parameters.
     */
    public static Map<String, String> decodeMatrix(String pathSegment, boolean decode) {
        Map<String, String> matrixMap = new HashMap<String, String>();

        // Skip over path segment
        int s = pathSegment.indexOf(';') + 1;
        if (s == 0 || s == pathSegment.length()) {
            return matrixMap;
        }

        int e = 0;
        do {
            e = pathSegment.indexOf(';', s);

            if (e == -1) {
                decodeMatrixParam(matrixMap, pathSegment.substring(s), decode);
            } else if (e > s) {
                decodeMatrixParam(matrixMap, pathSegment.substring(s, e), decode);
            }
            s = e + 1;
        } while (s > 0 && s < pathSegment.length());

        return matrixMap;
    }

    private static void decodeMatrixParam(Map<String, String> params,
            String param, boolean decode) {
        int equals = param.indexOf('=');
        if (equals > 0) {
            params.put(
                    UriComponent.decode(param.substring(0, equals), UriComponent.Type.MATRIX_PARAM),
                    (decode) ? UriComponent.decode(param.substring(equals + 1), UriComponent.Type.MATRIX_PARAM) : param.substring(equals + 1));
        } else if (equals == 0) {
            // no key declared, ignore
        } else if (param.length() > 0) {
            params.put(
                    UriComponent.decode(param, UriComponent.Type.MATRIX_PARAM),
                    "");
        }
    }

    private static String decode(String s, int n) {
        final StringBuilder sb = new StringBuilder(n);
        ByteBuffer bb = null;

        for (int i = 0; i < n;) {
            final char c = s.charAt(i++);
            if (c != '%') {
                sb.append(c);
            } else {
                bb = decodePercentEncodedOctets(s, i, bb);
                i = decodeOctets(i, bb, sb);
            }
        }

        return sb.toString();
    }

    private static String decodeQueryParam(String s, int n) {
        final StringBuilder sb = new StringBuilder(n);
        ByteBuffer bb = null;

        for (int i = 0; i < n;) {
            final char c = s.charAt(i++);
            if (c != '%') {
                if (c != '+')
                    sb.append(c);
                else
                    sb.append(' ');
            } else {
                bb = decodePercentEncodedOctets(s, i, bb);
                i = decodeOctets(i, bb, sb);
            }
        }

        return sb.toString();
    }

    private static String decodeHost(String s, int n) {
        final StringBuilder sb = new StringBuilder(n);
        ByteBuffer bb = null;

        boolean betweenBrackets = false;
        for (int i = 0; i < n;) {
            final char c = s.charAt(i++);
            if (c == '[') {
                betweenBrackets = true;
            } else if (betweenBrackets && c == ']') {
                betweenBrackets = false;
            }

            if (c != '%' || betweenBrackets) {
                sb.append(c);
            } else {
                bb = decodePercentEncodedOctets(s, i, bb);
                i = decodeOctets(i, bb, sb);
            }
        }

        return sb.toString();
    }

    /**
     * Decode a contigious sequence of percent encoded octets.
     * <p>
     * Assumes the index, i, starts that the first hex digit of the first
     * percent-encoded octet.
     */
    private static ByteBuffer decodePercentEncodedOctets(String s, int i, ByteBuffer bb) {
        if (bb == null)
            bb = ByteBuffer.allocate(1);
        else
            ((Buffer)bb).clear();

        while (true) {
            // Decode the hex digits
            bb.put((byte) (decodeHex(s, i++) << 4 | decodeHex(s, i++)));

            // Finish if at the end of the string
            if (i == s.length()) {
                break;
            }

            // Finish if no more percent-encoded octets follow
            if (s.charAt(i++) != '%') {
                break;
            }

            // Check if the byte buffer needs to be increased in size
            if (((Buffer)bb).position() == bb.capacity()) {
                ((Buffer)bb).flip();
                // Create a new byte buffer with the maximum number of possible
                // octets, hence resize should only occur once
                ByteBuffer bb_new = ByteBuffer.allocate(s.length() / 3);
                bb_new.put(bb);
                bb = bb_new;
            }
        }

        ((Buffer)bb).flip();
        return bb;
    }

    /**
     * Decodes octets to characters using the UTF-8 decoding and appends
     * the characters to a StringBuffer.
     * @return the index to the next unchecked character in the string to decode
     */
    private static int decodeOctets(int i, ByteBuffer bb, StringBuilder sb) {
        // If there is only one octet and is an ASCII character
        if (((Buffer)bb).limit() == 1 && (bb.get(0) & 0xFF) < 0x80) {
            // Octet can be appended directly
            sb.append((char) bb.get(0));
            return i + 2;
        } else {
            // 
            CharBuffer cb = UTF_8_CHARSET.decode(bb);
            sb.append(cb.toString());
            return i + ((Buffer)bb).limit() * 3 - 1;
        }
    }

    private static int decodeHex(String s, int i) {
        final int v = decodeHex(s.charAt(i));
        if (v == -1) // TODO localize
        {
            throw new IllegalArgumentException("Malformed percent-encoded octet at index " + i +
                    ", invalid hexadecimal digit '" + s.charAt(i) + "'");
        }
        return v;
    }
    private static final int[] HEX_TABLE = createHexTable();

    private static int[] createHexTable() {
        int[] table = new int[0x80];
        Arrays.fill(table, -1);

        for (char c = '0'; c <= '9'; c++) {
            table[c] = c - '0';
        }
        for (char c = 'A'; c <= 'F'; c++) {
            table[c] = c - 'A' + 10;
        }
        for (char c = 'a'; c <= 'f'; c++) {
            table[c] = c - 'a' + 10;
        }
        return table;
    }

    private static int decodeHex(char c) {
        return (c < 128) ? HEX_TABLE[c] : -1;
    }

    private static boolean isHexCharacter(char c) {
        return c < 128 && HEX_TABLE[c] != -1;
    }
}