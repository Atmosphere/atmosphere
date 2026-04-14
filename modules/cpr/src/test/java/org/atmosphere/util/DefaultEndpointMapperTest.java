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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class DefaultEndpointMapperTest {

    private DefaultEndpointMapper<String> mapper;

    @BeforeEach
    void setUp() {
        mapper = new DefaultEndpointMapper<>();
    }

    @Test
    void exactPathMatch() {
        var handlers = Map.of("/chat", "chatHandler");
        assertEquals("chatHandler", mapper.map("/chat", handlers));
    }

    @Test
    void nullPathDefaultsToRoot() {
        var handlers = Map.of("/", "rootHandler");
        assertEquals("rootHandler", mapper.map((String) null, handlers));
    }

    @Test
    void emptyPathDefaultsToRoot() {
        var handlers = Map.of("/", "rootHandler");
        assertEquals("rootHandler", mapper.map("", handlers));
    }

    @Test
    void noMatchReturnsNull() {
        var handlers = Map.of("/chat", "chatHandler");
        assertNull(mapper.map("/nonexistent", handlers));
    }

    @Test
    void pathWithAllSuffix() {
        var handlers = Map.of("/chat/all", "allHandler");
        assertEquals("allHandler", mapper.map("/chat", handlers));
    }

    @Test
    void pathWithTrailingSlashAndAll() {
        var handlers = Map.of("/chat/all", "allHandler");
        assertEquals("allHandler", mapper.map("/chat/", handlers));
    }

    @Test
    void wildcardMatch() {
        var handlers = Map.of("/chat*", "wildcardHandler");
        assertEquals("wildcardHandler", mapper.map("/chat", handlers));
    }

    @Test
    void parentPathFallback() {
        var handlers = Map.of("/api", "apiHandler");
        assertEquals("apiHandler", mapper.map("/api/users/123", handlers));
    }

    @Test
    void deepNestedPathFallback() {
        var handlers = Map.of("/a", "topHandler");
        assertEquals("topHandler", mapper.map("/a/b/c/d", handlers));
    }

    @Test
    void multipleHandlersExactWins() {
        var handlers = new HashMap<String, String>();
        handlers.put("/chat", "exactHandler");
        handlers.put("/chat/all", "allHandler");
        assertEquals("exactHandler", mapper.map("/chat", handlers));
    }

    @Test
    void rootPathMatchesRoot() {
        var handlers = Map.of("/", "rootHandler");
        assertEquals("rootHandler", mapper.map("/", handlers));
    }

    @Test
    void pathTemplateMatch() {
        var handlers = Map.of("/users/{id}", "userHandler");
        assertEquals("userHandler", mapper.map("/users/42", handlers));
    }

    @Test
    void emptyHandlersReturnsNull() {
        assertNull(mapper.map("/chat", Map.of()));
    }
}
