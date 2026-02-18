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

import org.testng.annotations.Test;

import java.util.HashMap;

import static org.testng.Assert.*;

public class PathTemplateTest {

    @Test
    public void simpleVariable() {
        var t = new PathTemplate("/chat/{room}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/chat/general", vars));
        assertEquals(vars.get("room"), "general");
    }

    @Test
    public void multipleVariables() {
        var t = new PathTemplate("/api/{version}/{resource}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/api/v2/users", vars));
        assertEquals(vars.get("version"), "v2");
        assertEquals(vars.get("resource"), "users");
    }

    @Test
    public void customRegex() {
        var t = new PathTemplate("/item/{id:\\d+}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/item/42", vars));
        assertEquals(vars.get("id"), "42");

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
        assertEquals(vars.get("id"), "123");

        // dot is escaped, so 'v1X0' should not match
        assertFalse(t.match("/api/v1X0/123", vars));
    }

    @Test
    public void regexWithNestedGroups() {
        var t = new PathTemplate("/data/{key:(\\d{2})-(\\w+)}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/data/12-abc", vars));
        assertEquals(vars.get("key"), "12-abc");
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

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void nullTemplate() {
        new PathTemplate(null);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void emptyTemplate() {
        new PathTemplate("");
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void unclosedBrace() {
        new PathTemplate("/chat/{room");
    }

    @Test
    public void getTemplate() {
        var t = new PathTemplate("/chat/{room}");
        assertEquals(t.getTemplate(), "/chat/{room}");
    }

    @Test
    public void duplicateVariablesSameValue() {
        var t = new PathTemplate("/{a}/{a}");
        var vars = new HashMap<String, String>();

        assertTrue(t.match("/x/x", vars));
        assertEquals(vars.get("a"), "x");
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
