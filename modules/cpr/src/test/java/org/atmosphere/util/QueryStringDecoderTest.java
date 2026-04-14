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

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QueryStringDecoderTest {

    // ── Path extraction ──

    @Test
    void pathFromSimpleUri() {
        var decoder = new QueryStringDecoder("/hello?key=value");
        assertEquals("/hello", decoder.getPath());
    }

    @Test
    void pathFromUriWithoutQueryString() {
        var decoder = new QueryStringDecoder("/just/path");
        assertEquals("/just/path", decoder.getPath());
    }

    @Test
    void pathFromRootUri() {
        var decoder = new QueryStringDecoder("/?q=1");
        assertEquals("/", decoder.getPath());
    }

    @Test
    void emptyPathWhenHasPathFalse() {
        var decoder = new QueryStringDecoder("key=value", false);
        assertEquals("", decoder.getPath());
    }

    // ── Parameter parsing ──

    @Test
    void singleParameter() {
        var decoder = new QueryStringDecoder("/path?name=world");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("world"), params.get("name"));
    }

    @Test
    void multipleParameters() {
        var decoder = new QueryStringDecoder("/path?a=1&b=2&c=3");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("1"), params.get("a"));
        assertEquals(List.of("2"), params.get("b"));
        assertEquals(List.of("3"), params.get("c"));
    }

    @Test
    void multipleValuesForSameKey() {
        var decoder = new QueryStringDecoder("/path?tag=java&tag=kotlin");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("java", "kotlin"), params.get("tag"));
    }

    @Test
    void parameterWithoutValue() {
        var decoder = new QueryStringDecoder("/path?flag");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of(""), params.get("flag"));
    }

    @Test
    void parameterWithEmptyValue() {
        var decoder = new QueryStringDecoder("/path?key=");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of(""), params.get("key"));
    }

    @Test
    void emptyQueryStringReturnsEmptyMap() {
        var decoder = new QueryStringDecoder("/path");
        assertTrue(decoder.getParameters().isEmpty());
    }

    @Test
    void formDataWithoutPath() {
        var decoder = new QueryStringDecoder("user=alice&pass=secret", false);
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("alice"), params.get("user"));
        assertEquals(List.of("secret"), params.get("pass"));
    }

    // ── URL decoding ──

    @Test
    void percentEncodedSpaces() {
        var decoder = new QueryStringDecoder("/search?q=hello%20world");
        assertEquals(List.of("hello world"), decoder.getParameters().get("q"));
    }

    @Test
    void plusDecodesAsSpace() {
        var decoder = new QueryStringDecoder("/search?q=hello+world");
        assertEquals(List.of("hello world"), decoder.getParameters().get("q"));
    }

    @Test
    void percentEncodedSpecialChars() {
        var decoder = new QueryStringDecoder("/path?val=%26%3D%3F");
        assertEquals(List.of("&=?"), decoder.getParameters().get("val"));
    }

    @Test
    void doublePercentDecodesAsPercent() {
        assertEquals("%", QueryStringDecoder.decodeComponent("%%"));
    }

    @Test
    void decodeComponentNullReturnsEmpty() {
        assertEquals("", QueryStringDecoder.decodeComponent(null));
    }

    @Test
    void decodeComponentNoEncodingReturnsOriginal() {
        String input = "plain-text";
        // Should return same reference when no decoding needed
        assertEquals(input, QueryStringDecoder.decodeComponent(input));
    }

    @Test
    void decodeComponentUtf8Multibyte() {
        // é = UTF-8 bytes C3 A9
        assertEquals("\u00E9", QueryStringDecoder.decodeComponent("%C3%A9"));
    }

    @Test
    void decodeComponentCaseInsensitiveHex() {
        assertEquals(" ", QueryStringDecoder.decodeComponent("%2f".replace("2f", "20")));
        assertEquals("/", QueryStringDecoder.decodeComponent("%2F"));
        assertEquals("/", QueryStringDecoder.decodeComponent("%2f"));
    }

    // ── Semicolons normalized to ampersands ──

    @Test
    void semicolonAsParameterSeparator() {
        var decoder = new QueryStringDecoder("/path?a=1;b=2");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("1"), params.get("a"));
        assertEquals(List.of("2"), params.get("b"));
    }

    // ── HashDOS protection ──

    @Test
    void maxParamsLimitsParameterCount() {
        var decoder = new QueryStringDecoder("/path?a=1&b=2&c=3&d=4&e=5",
                StandardCharsets.UTF_8, true, 3);
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(3, params.values().stream().mapToInt(List::size).sum());
    }

    @Test
    void maxParamsZeroOrNegativeThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> new QueryStringDecoder("/path", StandardCharsets.UTF_8, true, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new QueryStringDecoder("/path", StandardCharsets.UTF_8, true, -1));
    }

    // ── Error handling ──

    @Test
    void nullUriStringThrows() {
        assertThrows(NullPointerException.class,
                () -> new QueryStringDecoder((String) null));
    }

    @Test
    void nullCharsetThrows() {
        assertThrows(NullPointerException.class,
                () -> new QueryStringDecoder("/path", (java.nio.charset.Charset) null));
    }

    @Test
    void malformedPercentAtEndThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryStringDecoder.decodeComponent("abc%"));
    }

    @Test
    void malformedPartialPercentThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryStringDecoder.decodeComponent("abc%2"));
    }

    @Test
    void invalidHexCharsThrows() {
        assertThrows(IllegalArgumentException.class,
                () -> QueryStringDecoder.decodeComponent("abc%GG"));
    }

    // ── URI constructor ──

    @Test
    void javaUriConstructor() {
        var uri = URI.create("http://localhost:8080/api/test?key=val&x=1");
        var decoder = new QueryStringDecoder(uri);
        assertEquals("/api/test", decoder.getPath());
        assertEquals(List.of("val"), decoder.getParameters().get("key"));
        assertEquals(List.of("1"), decoder.getParameters().get("x"));
    }

    @Test
    void javaUriConstructorNullThrows() {
        assertThrows(NullPointerException.class,
                () -> new QueryStringDecoder((URI) null));
    }

    @Test
    void javaUriConstructorWithCustomCharset() {
        var uri = URI.create("http://example.com/path?q=hello%20world");
        var decoder = new QueryStringDecoder(uri, StandardCharsets.UTF_8);
        assertEquals(List.of("hello world"), decoder.getParameters().get("q"));
    }

    @Test
    void javaUriConstructorSemicolonNormalized() {
        var uri = URI.create("http://localhost/path?a=1;b=2");
        var decoder = new QueryStringDecoder(uri);
        assertEquals(List.of("1"), decoder.getParameters().get("a"));
        assertEquals(List.of("2"), decoder.getParameters().get("b"));
    }

    // ── Lazy evaluation / caching ──

    @Test
    void getPathCalledTwiceReturnsSameValue() {
        var decoder = new QueryStringDecoder("/test?a=1");
        String path1 = decoder.getPath();
        String path2 = decoder.getPath();
        assertEquals(path1, path2);
        assertEquals("/test", path1);
    }

    @Test
    void getParametersCalledTwiceReturnsSameMap() {
        var decoder = new QueryStringDecoder("/test?a=1");
        Map<String, List<String>> params1 = decoder.getParameters();
        Map<String, List<String>> params2 = decoder.getParameters();
        assertEquals(params1, params2);
    }

    // ── Edge cases ──

    @Test
    void emptyFormDataReturnsEmptyMap() {
        var decoder = new QueryStringDecoder("", false);
        assertTrue(decoder.getParameters().isEmpty());
    }

    @Test
    void consecutiveAmpersandsSkipEmpty() {
        var decoder = new QueryStringDecoder("/path?a=1&&b=2");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("1"), params.get("a"));
        assertEquals(List.of("2"), params.get("b"));
    }

    @Test
    void trailingAmpersand() {
        var decoder = new QueryStringDecoder("/path?a=1&");
        Map<String, List<String>> params = decoder.getParameters();
        assertEquals(List.of("1"), params.get("a"));
    }

    @Test
    void queryStringWithOnlyQuestionMark() {
        var decoder = new QueryStringDecoder("/path?");
        assertTrue(decoder.getParameters().isEmpty());
    }
}
