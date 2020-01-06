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
package org.atmosphere.util;

import javax.servlet.http.Cookie;

import java.nio.CharBuffer;
import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.BitSet;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;

/**
 * This class is a cut & paste from Tomcat's Cookie creation.
 */
public class CookieUtil {
    private static final String OLD_COOKIE_PATTERN =
            "EEE, dd-MMM-yyyy HH:mm:ss z";
    private static final ThreadLocal<DateFormat> OLD_COOKIE_FORMAT =
            new ThreadLocal<DateFormat>() {
                @Override
                protected DateFormat initialValue() {
                    DateFormat df =
                            new SimpleDateFormat(OLD_COOKIE_PATTERN, Locale.US);
                    df.setTimeZone(TimeZone.getTimeZone("GMT"));
                    return df;
                }
            };
    private static final String tspecials = ",; ";
    private static final String ancientDate;

    private static final BitSet VALID_COOKIE_NAME_OCTETS = validCookieNameOctets();
    private static final BitSet VALID_COOKIE_VALUE_OCTETS = validCookieValueOctets();
    private static final BitSet VALID_COOKIE_ATTRIBUTE_VALUE_OCTETS = validCookieAttributeValueOctets();

    static {
        ancientDate = OLD_COOKIE_FORMAT.get().format(new Date(10000));
    }

    public static boolean isToken(String value) {
        if (value == null) return true;
        int len = value.length();

        for (int i = 0; i < len; i++) {
            char c = value.charAt(i);

            if (c < 0x20 || c >= 0x7f || tspecials.indexOf(c) != -1)
                return false;
        }
        return true;
    }

    private static String escapeDoubleQuotes(String s) {

        if (s == null || s.length() == 0 || s.indexOf('"') == -1) {
            return s;
        }

        StringBuffer b = new StringBuffer();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"')
                b.append('\\').append('"');
            else
                b.append(c);
        }

        return b.toString();
    }

    public static String toString(Cookie c) {
        StringBuffer buf = new StringBuffer();
        // Servlet implementation checks name
        buf.append(c.getName());
        buf.append("=");
        // Servlet implementation does not check anything else

        /*
         * The spec allows some latitude on when to send the version attribute
         * with a Set-Cookie header. To be nice to clients, we'll make sure the
         * version attribute is first. That means checking the various things
         * that can cause us to switch to a v1 cookie first.
         *
         * Note that by checking for tokens we will also throw an exception if a
         * control character is encountered.
         */
        // Start by using the version we were asked for
        int newVersion = c.getVersion();

        // Now build the cookie header
        // Value
        maybeQuote(buf, c.getValue());
        // Add version 1 specific information
        if (newVersion == 1) {
            // Version=1 ... required
            buf.append("; Version=1");

            // Comment=comment
            if (c.getComment() != null) {
                buf.append("; Comment=");
                maybeQuote(buf, c.getComment());
            }
        }

        // Add domain information, if present
        if (c.getDomain() != null) {
            buf.append("; Domain=");
            maybeQuote(buf, c.getDomain());
        }

        // Max-Age=secs ... or use old "Expires" format
        if (c.getMaxAge() >= 0) {
            if (newVersion > 0) {
                buf.append("; Max-Age=");
                buf.append(c.getMaxAge());
            }

            if (newVersion == 0) {
                // Wdy, DD-Mon-YY HH:MM:SS GMT ( Expires Netscape format )
                buf.append("; Expires=");
                // To expire immediately we need to set the time in past
                if (c.getMaxAge() == 0) {
                    buf.append(ancientDate);
                } else {
                    OLD_COOKIE_FORMAT.get().format(
                            new Date(System.currentTimeMillis() +
                                    c.getMaxAge() * 1000L),
                            buf, new FieldPosition(0));
                }
            }
        }

        // Path=path
        if (c.getPath() != null) {
            buf.append("; Path=");
            maybeQuote(buf, c.getPath());
        }

        // Secure
        if (c.getSecure()) {
            buf.append("; Secure");
        }

        // HttpOnly
        if (c.isHttpOnly()) {
            buf.append("; HttpOnly");
        }
        return buf.toString();
    }

    /**
     * Quotes values if required.
     *
     * @param buf
     * @param value
     */
    private static void maybeQuote(StringBuffer buf, String value) {
        if (value == null || value.length() == 0) {
            buf.append("\"\"");
        } else if (alreadyQuoted(value)) {
            buf.append('"');
            buf.append(escapeDoubleQuotes(value, 1, value.length() - 1));
            buf.append('"');
        } else {
            buf.append(value);
        }
    }

    public static boolean alreadyQuoted(String value) {
        if (value == null || value.length() < 2) {
            return false;
        }
        return (value.charAt(0) == '\"' && value.charAt(value.length() - 1) == '\"');
    }

    /**
     * Escapes any double quotes in the given string.
     *
     * @param s          the input string
     * @param beginIndex start index inclusive
     * @param endIndex   exclusive
     * @return The (possibly) escaped string
     */
    private static String escapeDoubleQuotes(String s, int beginIndex, int endIndex) {

        if (s == null || s.length() == 0 || s.indexOf('"') == -1) {
            return s;
        }

        StringBuffer b = new StringBuffer();
        for (int i = beginIndex; i < endIndex; i++) {
            char c = s.charAt(i);
            if (c == '\\') {
                b.append(c);
                //ignore the character after an escape, just append it
                if (++i >= endIndex) {
                    throw new IllegalArgumentException("Invalid escape character in cookie value.");
                }
                b.append(s.charAt(i));
            } else if (c == '"') {
                b.append('\\').append('"');
            } else {
                b.append(c);
            }
        }

        return b.toString();
    }

    ///// Server-Side Cookie Decoding code forked from io.netty/netty and modified /////
    
    /*
     * Copyright 2018 The Netty Project
     *
     * The Netty Project licenses this file to you under the Apache License,
     * version 2.0 (the "License"); you may not use this file except in compliance
     * with the License. You may obtain a copy of the License at:
     *
     *   http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
     * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
     * License for the specific language governing permissions and limitations
     * under the License.
     */
    // token = 1*<any CHAR except CTLs or separators>
    // separators = "(" | ")" | "<" | ">" | "@"
    // | "," | ";" | ":" | "\" | <">
    // | "/" | "[" | "]" | "?" | "="
    // | "{" | "}" | SP | HT
    private static BitSet validCookieNameOctets() {
        BitSet bits = new BitSet();
        for (int i = 32; i < 127; i++) {
            bits.set(i);
        }
        int[] separators = new int[]
                { '(', ')', '<', '>', '@', ',', ';', ':', '\\', '"', '/', '[', ']', '?', '=', '{', '}', ' ', '\t' };
        for (int separator : separators) {
            bits.set(separator, false);
        }
        return bits;
    }

    // cookie-octet = %x21 / %x23-2B / %x2D-3A / %x3C-5B / %x5D-7E
    // US-ASCII characters excluding CTLs, whitespace, DQUOTE, comma, semicolon, and backslash
    private static BitSet validCookieValueOctets() {
        BitSet bits = new BitSet();
        bits.set(0x21);
        for (int i = 0x23; i <= 0x2B; i++) {
            bits.set(i);
        }
        for (int i = 0x2D; i <= 0x3A; i++) {
            bits.set(i);
        }
        for (int i = 0x3C; i <= 0x5B; i++) {
            bits.set(i);
        }
        for (int i = 0x5D; i <= 0x7E; i++) {
            bits.set(i);
        }
        return bits;
    }

    // path-value        = <any CHAR except CTLs or ";">
    private static BitSet validCookieAttributeValueOctets() {
        BitSet bits = new BitSet();
        for (int i = 32; i < 127; i++) {
            bits.set(i);
        }
        bits.set(';', false);
        return bits;
    }

    static int firstInvalidCookieNameOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_NAME_OCTETS);
    }

    static int firstInvalidCookieValueOctet(CharSequence cs) {
        return firstInvalidOctet(cs, VALID_COOKIE_VALUE_OCTETS);
    }

    static int firstInvalidOctet(CharSequence cs, BitSet bits) {
        for (int i = 0; i < cs.length(); i++) {
            char c = cs.charAt(i);
            if (!bits.get(c)) {
                return i;
            }
        }
        return -1;
    }

    static CharSequence unwrapValue(CharSequence cs) {
        final int len = cs.length();
        if (len > 0 && cs.charAt(0) == '"') {
            if (len >= 2 && cs.charAt(len - 1) == '"') {
                // properly balanced
                return len == 2 ? "" : cs.subSequence(1, len - 1);
            } else {
                return null;
            }
        }
        return cs;
    }

    static String validateAttributeValue(String name, String value) {
        if (value == null) {
            return null;
        }
        value = value.trim();
        if (value.isEmpty()) {
            return null;
        }
        int i = firstInvalidOctet(value, VALID_COOKIE_ATTRIBUTE_VALUE_OCTETS);
        if (i != -1) {
            throw new IllegalArgumentException(name + " contains the prohibited characters: " + value.charAt(i));
        }
        return value;
    }

    /**
     * Parent of Client and Server side cookie decoders
     */
    static abstract class CookieDecoder {
        private final boolean strict;

        protected CookieDecoder(boolean strict) {
            this.strict = strict;
        }

        protected Cookie initCookie(String header, int nameBegin, int nameEnd, int valueBegin, int valueEnd) {
            if (nameBegin == -1 || nameBegin == nameEnd) {
                return null;
            }

            if (valueBegin == -1) {
                return null;
            }

            CharSequence wrappedValue = CharBuffer.wrap(header, valueBegin, valueEnd);
            CharSequence unwrappedValue = unwrapValue(wrappedValue);
            if (unwrappedValue == null) {
                return null;
            }

            final String name = header.substring(nameBegin, nameEnd);

            if (strict && firstInvalidCookieNameOctet(name) >= 0) {
                return null;
            }

            if (strict && firstInvalidCookieValueOctet(unwrappedValue) >= 0) {
                return null;
            }

            Cookie cookie = new Cookie(name, unwrappedValue.toString());
            return cookie;
        }
    }
    public static final class ServerCookieDecoder extends CookieDecoder {

        private static final String RFC2965_VERSION = "$Version";

        private static final String RFC2965_PATH = "$Path";

        private static final String RFC2965_DOMAIN = "$Domain";

        private static final String RFC2965_PORT = "$Port";

        /**
         * Strict encoder that validates that name and value chars are in the valid scope
         * defined in RFC6265
         */
        public static final ServerCookieDecoder STRICT = new ServerCookieDecoder(true);

        /**
         * Lax instance that doesn't validate name and value
         */
        public static final ServerCookieDecoder LAX = new ServerCookieDecoder(false);

        private ServerCookieDecoder(boolean strict) {
            super(strict);
        }

        /**
         * Decodes the specified Set-Cookie HTTP header value into a {@link Cookie}.
         *
         * @param header the cookie header
         * @return the decoded {@link Cookie}
         */
        public Set<Cookie> decode(String header) {
            Set<Cookie> cookies = new HashSet<Cookie>();
            decode(header, cookies);
            return cookies;
        }

        /**
         * Decodes the specified Set-Cookie HTTP header value into a {@link Cookie}.
         *
         * @param header the cookie header
         * @param cookies the cookies to be filled
         */
        public void decode(String header, Set<Cookie> cookies) {
            final int headerLen = header.length();

            if (headerLen == 0) {
                return;
            }

            int i = 0;

            boolean rfc2965Style = false;
            if (header.regionMatches(true, 0, RFC2965_VERSION, 0, RFC2965_VERSION.length())) {
                // RFC 2965 style cookie, move to after version value
                i = header.indexOf(';') + 1;
                rfc2965Style = true;
            }

            loop: for (;;) {

                // Skip spaces and separators.
                for (;;) {
                    if (i == headerLen) {
                        break loop;
                    }
                    char c = header.charAt(i);
                    if (c == '\t' || c == '\n' || c == 0x0b || c == '\f'
                            || c == '\r' || c == ' ' || c == ',' || c == ';') {
                        i++;
                        continue;
                    }
                    break;
                }

                int nameBegin = i;
                int nameEnd = i;
                int valueBegin = -1;
                int valueEnd = -1;

                if (i != headerLen) {
                    keyValLoop: for (;;) {

                        char curChar = header.charAt(i);
                        if (curChar == ';') {
                            // NAME; (no value till ';')
                            nameEnd = i;
                            valueBegin = valueEnd = -1;
                            break keyValLoop;

                        } else if (curChar == '=') {
                            // NAME=VALUE
                            nameEnd = i;
                            i++;
                            if (i == headerLen) {
                                // NAME= (empty value, i.e. nothing after '=')
                                valueBegin = valueEnd = 0;
                                break keyValLoop;
                            }

                            valueBegin = i;
                            // NAME=VALUE;
                            int semiPos = header.indexOf(';', i);
                            valueEnd = i = semiPos > 0 ? semiPos : headerLen;
                            break keyValLoop;
                        } else {
                            i++;
                        }

                        if (i == headerLen) {
                            // NAME (no value till the end of string)
                            nameEnd = headerLen;
                            valueBegin = valueEnd = -1;
                            break;
                        }
                    }
                }

                if (rfc2965Style && (header.regionMatches(nameBegin, RFC2965_PATH, 0, RFC2965_PATH.length()) ||
                        header.regionMatches(nameBegin, RFC2965_DOMAIN, 0, RFC2965_DOMAIN.length()) ||
                        header.regionMatches(nameBegin, RFC2965_PORT, 0, RFC2965_PORT.length()))) {

                    // skip obsolete RFC2965 fields
                    continue;
                }

                Cookie cookie = initCookie(header, nameBegin, nameEnd, valueBegin, valueEnd);
                if (cookie != null) {
                    cookies.add(cookie);
                }
            }
        }
        
    }}
