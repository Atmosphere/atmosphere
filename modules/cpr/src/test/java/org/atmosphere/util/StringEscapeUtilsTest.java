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

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StringEscapeUtilsTest {

    // ── escapeJava ──

    @Test
    void escapeJavaNullReturnsNull() throws Exception {
        assertNull(StringEscapeUtils.escapeJava(null));
    }

    @Test
    void escapeJavaEmptyString() throws Exception {
        assertEquals("", StringEscapeUtils.escapeJava(""));
    }

    @Test
    void escapeJavaPlainText() throws Exception {
        assertEquals("hello world", StringEscapeUtils.escapeJava("hello world"));
    }

    @Test
    void escapeJavaDoubleQuotes() throws Exception {
        assertEquals("He said \\\"hi\\\"", StringEscapeUtils.escapeJava("He said \"hi\""));
    }

    @Test
    void escapeJavaBackslash() throws Exception {
        assertEquals("path\\\\to\\\\file", StringEscapeUtils.escapeJava("path\\to\\file"));
    }

    @Test
    void escapeJavaControlChars() throws Exception {
        assertEquals("a\\tb\\nc\\r", StringEscapeUtils.escapeJava("a\tb\nc\r"));
    }

    @Test
    void escapeJavaFormFeedAndBackspace() throws Exception {
        assertEquals("\\f\\b", StringEscapeUtils.escapeJava("\f\b"));
    }

    @Test
    void escapeJavaDoesNotEscapeSingleQuote() throws Exception {
        assertEquals("it's fine", StringEscapeUtils.escapeJava("it's fine"));
    }

    @Test
    void escapeJavaDoesNotEscapeForwardSlash() throws Exception {
        assertEquals("a/b/c", StringEscapeUtils.escapeJava("a/b/c"));
    }

    @Test
    void escapeJavaLowControlChars() throws Exception {
        // Control char 0x01 (below 0x10) → \u0001
        String result = StringEscapeUtils.escapeJava("\u0001");
        assertEquals("\\u0001", result);
    }

    @Test
    void escapeJavaControlCharAbove0x0f() throws Exception {
        // Control char 0x1A (above 0x0f, below 0x20) → \u001A
        String result = StringEscapeUtils.escapeJava("\u001A");
        assertEquals("\\u001A", result);
    }

    @Test
    void escapeJavaUnicodeAbove0x7f() throws Exception {
        // é = \u00E9 (above 0x7f, below 0xff)
        String result = StringEscapeUtils.escapeJava("\u00E9");
        assertEquals("\\u00E9", result);
    }

    @Test
    void escapeJavaUnicodeAbove0xff() throws Exception {
        // Ω = \u03A9 (above 0xff, below 0xfff)
        String result = StringEscapeUtils.escapeJava("\u03A9");
        assertEquals("\\u03A9", result);
    }

    @Test
    void escapeJavaUnicodeAbove0xfff() throws Exception {
        // 中 = \u4E2D (above 0xfff)
        String result = StringEscapeUtils.escapeJava("\u4E2D");
        assertEquals("\\u4E2D", result);
    }

    // ── escapeJavaScript ──

    @Test
    void escapeJavaScriptEscapesSingleQuote() throws Exception {
        assertEquals("it\\'s escaped", StringEscapeUtils.escapeJavaScript("it's escaped"));
    }

    @Test
    void escapeJavaScriptEscapesForwardSlash() throws Exception {
        assertEquals("a\\/b", StringEscapeUtils.escapeJavaScript("a/b"));
    }

    @Test
    void escapeJavaScriptNullReturnsNull() throws Exception {
        assertNull(StringEscapeUtils.escapeJavaScript(null));
    }

    // ── escapeJava to Writer ──

    @Test
    void escapeJavaToWriterNullWriterThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> StringEscapeUtils.escapeJava(null, "test"));
    }

    @Test
    void escapeJavaToWriterNullStringNoOp() throws IOException {
        var writer = new StringWriter();
        StringEscapeUtils.escapeJava(writer, null);
        assertEquals("", writer.toString());
    }

    @Test
    void escapeJavaToWriterWritesEscaped() throws IOException {
        var writer = new StringWriter();
        StringEscapeUtils.escapeJava(writer, "tab\there");
        assertEquals("tab\\there", writer.toString());
    }

    // ── unescapeJava ──

    @Test
    void unescapeJavaNullReturnsNull() throws Exception {
        assertNull(StringEscapeUtils.unescapeJava(null));
    }

    @Test
    void unescapeJavaEmptyString() throws Exception {
        assertEquals("", StringEscapeUtils.unescapeJava(""));
    }

    @Test
    void unescapeJavaPlainText() throws Exception {
        assertEquals("hello", StringEscapeUtils.unescapeJava("hello"));
    }

    @Test
    void unescapeJavaEscapedNewline() throws Exception {
        assertEquals("line1\nline2", StringEscapeUtils.unescapeJava("line1\\nline2"));
    }

    @Test
    void unescapeJavaEscapedTab() throws Exception {
        assertEquals("a\tb", StringEscapeUtils.unescapeJava("a\\tb"));
    }

    @Test
    void unescapeJavaEscapedCarriageReturn() throws Exception {
        assertEquals("a\rb", StringEscapeUtils.unescapeJava("a\\rb"));
    }

    @Test
    void unescapeJavaEscapedFormFeed() throws Exception {
        assertEquals("a\fb", StringEscapeUtils.unescapeJava("a\\fb"));
    }

    @Test
    void unescapeJavaEscapedBackspace() throws Exception {
        assertEquals("a\bb", StringEscapeUtils.unescapeJava("a\\bb"));
    }

    @Test
    void unescapeJavaEscapedBackslash() throws Exception {
        assertEquals("a\\b", StringEscapeUtils.unescapeJava("a\\\\b"));
    }

    @Test
    void unescapeJavaEscapedSingleQuote() throws Exception {
        assertEquals("it's", StringEscapeUtils.unescapeJava("it\\'s"));
    }

    @Test
    void unescapeJavaEscapedDoubleQuote() throws Exception {
        assertEquals("say \"hi\"", StringEscapeUtils.unescapeJava("say \\\"hi\\\""));
    }

    @Test
    void unescapeJavaUnicodeSequence() throws Exception {
        assertEquals("A", StringEscapeUtils.unescapeJava("\\u0041"));
    }

    @Test
    void unescapeJavaUnicodeNonAscii() throws Exception {
        assertEquals("\u00E9", StringEscapeUtils.unescapeJava("\\u00E9"));
    }

    @Test
    void unescapeJavaTrailingBackslash() throws Exception {
        // Edge case: trailing backslash at end of string
        assertEquals("abc\\", StringEscapeUtils.unescapeJava("abc\\"));
    }

    @Test
    void unescapeJavaUnknownEscapePassthrough() throws Exception {
        // Unknown escape like \z should pass through the z
        assertEquals("z", StringEscapeUtils.unescapeJava("\\z"));
    }

    @Test
    void unescapeJavaToWriterNullWriterThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> StringEscapeUtils.unescapeJava(null, "test"));
    }

    @Test
    void unescapeJavaToWriterNullStringNoOp() throws Exception {
        var writer = new StringWriter();
        StringEscapeUtils.unescapeJava(writer, null);
        assertEquals("", writer.toString());
    }

    // ── unescapeJavaScript delegates to unescapeJava ──

    @Test
    void unescapeJavaScriptDelegatesToJava() throws Exception {
        assertEquals("a\tb", StringEscapeUtils.unescapeJavaScript("a\\tb"));
    }

    @Test
    void unescapeJavaScriptToWriterDelegatesToJava() throws Exception {
        var writer = new StringWriter();
        StringEscapeUtils.unescapeJavaScript(writer, "a\\nb");
        assertEquals("a\nb", writer.toString());
    }

    // ── Roundtrip tests ──

    @Test
    void roundtripJavaEscapeUnescape() throws Exception {
        var original = "He said \"hello\"\nand\tthen\\left";
        var escaped = StringEscapeUtils.escapeJava(original);
        var unescaped = StringEscapeUtils.unescapeJava(escaped);
        assertEquals(original, unescaped);
    }

    @Test
    void roundtripJavaScriptEscapeUnescape() throws Exception {
        var original = "it's a \"test\" with / slash";
        var escaped = StringEscapeUtils.escapeJavaScript(original);
        var unescaped = StringEscapeUtils.unescapeJavaScript(escaped);
        assertEquals(original, unescaped);
    }

    @Test
    void roundtripUnicodeChars() throws Exception {
        var original = "\u4E2D\u00E9\u03A9";
        var escaped = StringEscapeUtils.escapeJava(original);
        var unescaped = StringEscapeUtils.unescapeJava(escaped);
        assertEquals(original, unescaped);
    }
}
