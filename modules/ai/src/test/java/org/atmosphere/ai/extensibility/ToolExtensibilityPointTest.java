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
package org.atmosphere.ai.extensibility;

import org.atmosphere.ai.identity.InMemoryCredentialStore;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolExtensibilityPointTest {

    @Test
    void toolIndexRanksByTokenOverlap() {
        var index = new ToolIndex();
        index.register(new ToolDescriptor("github.createPr",
                "Create a GitHub pull request", List.of("git", "github", "pr")));
        index.register(new ToolDescriptor("github.createIssue",
                "Create a GitHub issue request", List.of("git", "github", "issue")));
        index.register(new ToolDescriptor("gmail.sendMail",
                "Send an email via Gmail", List.of("email", "gmail")));
        index.register(new ToolDescriptor("fs.readFile",
                "Read a file from the local filesystem", List.of("file", "fs")));

        var hits = index.search("create a pull request", 3);

        // Two tools share 'create' and 'request' tokens with the query;
        // github.createPr additionally matches 'pull', so it outranks
        // github.createIssue. gmail.sendMail and fs.readFile share no
        // tokens with the query and do not appear.
        assertEquals(2, hits.size());
        assertEquals("github.createPr", hits.get(0).id(),
                "github.createPr dominates because its description shares "
                        + "three query tokens vs. createIssue's two");
        assertEquals("github.createIssue", hits.get(1).id());
    }

    @Test
    void toolIndexReturnsEmptyWhenQueryShareNothing() {
        var index = new ToolIndex();
        index.register(new ToolDescriptor("github.createPr",
                "Create a GitHub pull request", List.of("git")));
        var hits = index.search("cook dinner", 5);
        assertTrue(hits.isEmpty(),
                "a query that shares no tokens with any descriptor returns no results");
    }

    @Test
    void toolIndexBlankQueryFallsBackToAlphabeticalOrder() {
        var index = new ToolIndex();
        index.register(new ToolDescriptor("gamma", "g", List.of()));
        index.register(new ToolDescriptor("alpha", "a", List.of()));
        index.register(new ToolDescriptor("beta", "b", List.of()));

        var hits = index.search("", 2);
        assertEquals(List.of("alpha", "beta"),
                hits.stream().map(ToolDescriptor::id).toList());
    }

    @Test
    void toolIndexLimitHonored() {
        var index = new ToolIndex();
        for (var i = 0; i < 50; i++) {
            index.register(new ToolDescriptor("t" + i,
                    "generic tool for thing " + i, List.of("thing")));
        }
        assertEquals(50, index.size());
        assertEquals(5, index.search("thing", 5).size());
    }

    @Test
    void dynamicSelectorRespectsDefaultLimit() {
        var index = new ToolIndex();
        for (var i = 0; i < 20; i++) {
            index.register(new ToolDescriptor("t" + i, "shared description", List.of("term")));
        }
        var selector = new DynamicToolSelector(index, 7);
        assertEquals(7, selector.select("term").size());
        assertEquals(3, selector.select("term", 3).size(),
                "per-call limit overrides the default");
    }

    @Test
    void dynamicSelectorRejectsInvalidConstruction() {
        var index = new ToolIndex();
        assertThrows(IllegalArgumentException.class, () -> new DynamicToolSelector(index, 0));
        assertThrows(IllegalArgumentException.class, () -> new DynamicToolSelector(index, -1));
    }

    @Test
    void toolDescriptorRejectsBlankId() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor("", "desc", List.of()));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolDescriptor(null, "desc", List.of()));
    }

    @Test
    void credentialStoreBackedTrustProviderResolves() {
        var store = new InMemoryCredentialStore();
        store.put("pierre", "mcp:github", "gho_token123");
        McpTrustProvider provider = new McpTrustProvider.CredentialStoreBacked(store);

        assertEquals(Optional.of("gho_token123"), provider.resolve("pierre", "github"));
        assertEquals(Optional.empty(), provider.resolve("pierre", "gmail"));
        assertEquals("credential-store-backed", provider.name());
    }

    @Test
    void trustProviderCustomKeyPrefix() {
        var store = new InMemoryCredentialStore();
        store.put("pierre", "custom/github", "gho_token123");
        McpTrustProvider provider = new McpTrustProvider.CredentialStoreBacked(store, "custom/");
        assertEquals(Optional.of("gho_token123"), provider.resolve("pierre", "github"));
    }

    @Test
    void toolExtensibilityPointCombinesBothSurfaces() {
        var index = new ToolIndex();
        index.register(new ToolDescriptor("github.createPr",
                "Create a GitHub pull request", List.of("git")));

        var store = new InMemoryCredentialStore();
        store.put("pierre", "mcp:github", "gho_token");

        var extensibility = new ToolExtensibilityPoint(
                index,
                new DynamicToolSelector(index, 5),
                new McpTrustProvider.CredentialStoreBacked(store));

        var tools = extensibility.toolsFor("create a pr", 3);
        assertEquals(1, tools.size());
        assertEquals("github.createPr", tools.get(0).id());

        assertEquals(Optional.of("gho_token"),
                extensibility.mcpCredential("pierre", "github"));
    }

    @Test
    void toolExtensibilityPointRejectsNullCollaborators() {
        var index = new ToolIndex();
        var selector = new DynamicToolSelector(index, 5);
        var provider = new McpTrustProvider.CredentialStoreBacked(new InMemoryCredentialStore());

        assertThrows(NullPointerException.class,
                () -> new ToolExtensibilityPoint(null, selector, provider));
        assertThrows(NullPointerException.class,
                () -> new ToolExtensibilityPoint(index, null, provider));
        assertThrows(NullPointerException.class,
                () -> new ToolExtensibilityPoint(index, selector, null));
    }
}
