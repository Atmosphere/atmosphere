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
package org.atmosphere.util;

import javax.servlet.http.Cookie;
import java.text.DateFormat;
import java.text.FieldPosition;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
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

}
