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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public class HtmlEncoderTest {

    @Test
    public void testEncodeNull() {
        assertNull(HtmlEncoder.encode(null));
    }

    @Test
    public void testEncodeEmptyString() {
        assertEquals("", HtmlEncoder.encode(""));
    }

    @Test
    public void testEncodeNoSpecialCharacters() {
        assertEquals("hello world", HtmlEncoder.encode("hello world"));
    }

    @Test
    public void testEncodeAmpersand() {
        assertEquals("a &amp; b", HtmlEncoder.encode("a & b"));
    }

    @Test
    public void testEncodeLessThan() {
        assertEquals("a &lt; b", HtmlEncoder.encode("a < b"));
    }

    @Test
    public void testEncodeGreaterThan() {
        assertEquals("a &gt; b", HtmlEncoder.encode("a > b"));
    }

    @Test
    public void testEncodeDoubleQuote() {
        assertEquals("a &quot;b&quot;", HtmlEncoder.encode("a \"b\""));
    }

    @Test
    public void testEncodeSingleQuote() {
        assertEquals("a &#x27;b&#x27;", HtmlEncoder.encode("a 'b'"));
    }

    @Test
    public void testEncodeScriptTag() {
        assertEquals("&lt;script&gt;alert(&#x27;xss&#x27;)&lt;/script&gt;",
                HtmlEncoder.encode("<script>alert('xss')</script>"));
    }

    @Test
    public void testEncodeAllSpecialCharacters() {
        assertEquals("&amp;&lt;&gt;&quot;&#x27;", HtmlEncoder.encode("&<>\"'"));
    }

    @Test
    public void testEncodeJsonUnchangedWithoutHtmlChars() {
        String json = "{\"key\": \"value\", \"number\": 42}";
        assertEquals("{&quot;key&quot;: &quot;value&quot;, &quot;number&quot;: 42}", HtmlEncoder.encode(json));
    }
}
