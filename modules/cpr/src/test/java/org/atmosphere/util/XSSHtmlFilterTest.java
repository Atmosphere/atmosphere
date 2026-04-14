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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class XSSHtmlFilterTest {

    private XSSHtmlFilter filter;

    @BeforeEach
    void setUp() {
        filter = new XSSHtmlFilter();
    }

    // ── HTML entity escaping ──

    @Test
    void escapesLessThan() {
        var result = filter.filter("b1", "<script>", "<script>");
        assertEquals("&lt;script&gt;", result.message());
    }

    @Test
    void escapesGreaterThan() {
        var result = filter.filter("b1", "a>b", "a>b");
        assertEquals("a&gt;b", result.message());
    }

    @Test
    void escapesAmpersand() {
        var result = filter.filter("b1", "a&b", "a&b");
        assertEquals("a&amp;b", result.message());
    }

    @Test
    void escapesDoubleQuote() {
        var result = filter.filter("b1", "say \"hi\"", "say \"hi\"");
        assertEquals("say \\\"hi\\\"", result.message());
    }

    @Test
    void escapesSingleQuote() {
        var result = filter.filter("b1", "it's", "it's");
        assertEquals("it\\'s", result.message());
    }

    @Test
    void escapesBackslash() {
        var result = filter.filter("b1", "a\\b", "a\\b");
        assertEquals("a\\\\b", result.message());
    }

    // ── Control character escaping ──

    @Test
    void newlineBecomesBreak() {
        var result = filter.filter("b1", "line1\nline2", "line1\nline2");
        assertEquals("line1<br />line2", result.message());
    }

    @Test
    void carriageReturnIsDropped() {
        var result = filter.filter("b1", "a\rb", "a\rb");
        assertEquals("ab", result.message());
    }

    @Test
    void tabEscaped() {
        var result = filter.filter("b1", "a\tb", "a\tb");
        assertEquals("a\\tb", result.message());
    }

    @Test
    void backspaceEscaped() {
        var result = filter.filter("b1", "a\bb", "a\bb");
        assertEquals("a\\bb", result.message());
    }

    @Test
    void formFeedEscaped() {
        var result = filter.filter("b1", "a\fb", "a\fb");
        assertEquals("a\\fb", result.message());
    }

    // ── Passthrough ──

    @Test
    void plainTextPassesThrough() {
        var result = filter.filter("b1", "hello world", "hello world");
        assertEquals("hello world", result.message());
    }

    @Test
    void emptyStringPassesThrough() {
        var result = filter.filter("b1", "", "");
        assertEquals("", result.message());
    }

    // ── Non-string input ──

    @Test
    void nonStringObjectPassesThroughUnchanged() {
        var obj = Integer.valueOf(42);
        var result = filter.filter("b1", obj, obj);
        assertSame(obj, result.message());
    }

    // ── Combined XSS attack patterns ──

    @Test
    void xssScriptInjection() {
        String attack = "<script>alert('xss')</script>";
        var result = filter.filter("b1", attack, attack);
        String escaped = (String) result.message();
        // Must not contain raw < or > or unescaped quotes
        assertEquals("&lt;script&gt;alert(\\'xss\\')&lt;/script&gt;", escaped);
    }

    @Test
    void xssImgOnError() {
        String attack = "<img onerror=\"alert(1)\">";
        var result = filter.filter("b1", attack, attack);
        String escaped = (String) result.message();
        assertEquals("&lt;img onerror=\\\"alert(1)\\\"&gt;", escaped);
    }

    @Test
    void xssEventHandler() {
        String attack = "<div onmouseover=\"steal()\">";
        var result = filter.filter("b1", attack, attack);
        String escaped = (String) result.message();
        assertEquals("&lt;div onmouseover=\\\"steal()\\\"&gt;", escaped);
    }

    @Test
    void mixedSpecialChars() {
        String input = "Tom & Jerry's <adventure>\n\\end";
        var result = filter.filter("b1", input, input);
        assertEquals("Tom &amp; Jerry\\'s &lt;adventure&gt;<br />\\\\end", result.message());
    }
}
