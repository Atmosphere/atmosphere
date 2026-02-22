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

import java.util.HashMap;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class PathTemplateTest {

    @Test
    public void simpleVariable() {
        var t = new PathTemplate("/chat/{room}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/chat/general", vars));
        assertEquals("general", vars.get("room"));
    }

    @Test
    public void multipleVariables() {
        var t = new PathTemplate("/api/{version}/{resource}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/api/v2/users", vars));
        assertEquals("v2", vars.get("version"));
        assertEquals("users", vars.get("resource"));
    }

    @Test
    public void customRegex() {
        var t = new PathTemplate("/item/{id:\\d+}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/item/42", vars));
        assertEquals("42", vars.get("id"));

        assertFalse(t.match("/item/abc", vars));
    }

    @Test
    public void noMatch() {
        var t = new PathTemplate("/chat/{room}");
        var vars = new HashMap<String, String>();

        assertFalse(t.match("/other/general", vars));
        assertTrue(vars.isEmpty());
    }

    @Test
    public void literalPath() {
        var t = new PathTemplate("/exact/path");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/exact/path", vars));
        assertTrue(vars.isEmpty());

        assertFalse(t.match("/exact/other", vars));
    }

    @Test
    public void escapedDotInLiteral() {
        var t = new PathTemplate("/api/v1.0/{id}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/api/v1.0/123", vars));
        assertEquals("123", vars.get("id"));

        // dot is escaped, so 'v1X0' should not match
        assertFalse(t.match("/api/v1X0/123", vars));
    }

    @Test
    public void regexWithNestedGroups() {
        var t = new PathTemplate("/data/{key:(\\d{2})-(\\w+)}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/data/12-abc", vars));
        assertEquals("12-abc", vars.get("key"));
    }

    @Test
    public void rawRegexPatternFromMetaBroadcaster() {
        // MetaBroadcaster replaces * with this regex before constructing template
        String mappingRegex = "[/a-zA-Z0-9-&.*=@_;\\?]+";
        var t = new PathTemplate("/" + mappingRegex);
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/a", vars));
        assertTrue(t.match("/hello", vars));
        assertTrue(t.match("/a/b/c", vars));
    }

    @Test
    public void prefixedWildcard() {
        String mappingRegex = "[/a-zA-Z0-9-&.*=@_;\\?]+";
        var t = new PathTemplate("/a/" + mappingRegex);
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/a/chat1", vars));
        assertFalse(t.match("/b/chat1", vars));
    }

    @Test
    public void nullUri() {
        var t = new PathTemplate("/chat/{room}");
        assertFalse(t.match(null, new HashMap<>()));
    }

    @Test
    public void nullTemplate() {
            assertThrows(IllegalArgumentException.class, () -> {
            new PathTemplate(null);
            });
    }

    @Test
    public void emptyTemplate() {
            assertThrows(IllegalArgumentException.class, () -> {
            new PathTemplate("");
            });
    }

    @Test
    public void unclosedBrace() {
        assertThrows(IllegalArgumentException.class, () -> {
            new PathTemplate("/chat/{room");
        });
    }

    @Test
    public void getTemplate() {
        var t = new PathTemplate("/chat/{room}");
        assertEquals("/chat/{room}", t.getTemplate());
    }

    @Test
    public void duplicateVariablesSameValue() {
        var t = new PathTemplate("/{a}/{a}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/x/x", vars));
        assertEquals("x", vars.get("a"));
    }

    @Test
    public void duplicateVariablesDifferentValues() {
        var t = new PathTemplate("/{a}/{a}");
        var vars = new HashMap<String, String>();

        assertFalse(t.match("/x/y", vars));
    }

    @Test
    public void specialCharsInBroadcasterId() {
        var t = new PathTemplate("/a/@b");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/a/@b", vars));
    }

    @Test
    public void underscoreInPath() {
        var t = new PathTemplate("/a/_b");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/a/_b", vars));
    }
}
