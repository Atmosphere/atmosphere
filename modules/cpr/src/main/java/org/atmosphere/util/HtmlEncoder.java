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

/**
 * Utility class for encoding strings to prevent HTML injection (XSS).
 * Encodes the five critical HTML characters: {@code & < > " '}.
 */
public final class HtmlEncoder {

    private HtmlEncoder() {
    }

    /**
     * Encodes a string by replacing HTML special characters with their
     * corresponding HTML entities. This prevents cross-site scripting (XSS)
     * when the string is rendered in an HTML context.
     *
     * @param input the string to encode, may be {@code null}
     * @return the encoded string, or {@code null} if input was {@code null}
     */
    public static String encode(String input) {
        if (input == null) {
            return null;
        }
        var sb = new StringBuilder(input.length() + 16);
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            switch (c) {
                case '&' -> sb.append("&amp;");
                case '<' -> sb.append("&lt;");
                case '>' -> sb.append("&gt;");
                case '"' -> sb.append("&quot;");
                case '\'' -> sb.append("&#x27;");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
