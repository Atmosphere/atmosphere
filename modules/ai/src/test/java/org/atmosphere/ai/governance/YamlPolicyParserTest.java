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
package org.atmosphere.ai.governance;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ServiceLoader;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlPolicyParserTest {

    @Test
    void parsesAllBuiltInPolicyTypes() throws IOException {
        var yaml = """
                version: "1.0"
                policies:
                  - name: redact-pii
                    type: pii-redaction
                    version: "1.0"
                    config:
                      mode: redact
                  - name: budget-cap
                    type: cost-ceiling
                    config:
                      budget-usd: 42.50
                  - name: drift-guard
                    type: output-length-zscore
                    config:
                      window-size: 20
                      z-threshold: 2.5
                      min-samples: 5
                """;
        var parser = new YamlPolicyParser();
        var policies = parser.parse("yaml:test", asStream(yaml));

        assertEquals(3, policies.size());
        assertEquals("redact-pii", policies.get(0).name());
        assertEquals("budget-cap", policies.get(1).name());
        assertEquals("drift-guard", policies.get(2).name());
        assertEquals("yaml:test", policies.get(0).source());
    }

    @Test
    void inheritsDocumentVersionWhenEntryOmitsIt() throws IOException {
        var yaml = """
                version: "2026-04-21"
                policies:
                  - name: p
                    type: pii-redaction
                """;
        var policies = new YamlPolicyParser().parse("yaml:test", asStream(yaml));
        assertEquals("2026-04-21", policies.get(0).version());
    }

    @Test
    void entryVersionBeatsDocumentVersion() throws IOException {
        var yaml = """
                version: "1.0"
                policies:
                  - name: p
                    type: pii-redaction
                    version: "2.0"
                """;
        var policies = new YamlPolicyParser().parse("yaml:test", asStream(yaml));
        assertEquals("2.0", policies.get(0).version());
    }

    @Test
    void emptyDocumentYieldsEmptyList() throws IOException {
        var policies = new YamlPolicyParser().parse("yaml:empty", asStream(""));
        assertEquals(0, policies.size());
    }

    @Test
    void missingPoliciesKeyYieldsEmptyList() throws IOException {
        var yaml = """
                version: "1.0"
                """;
        var policies = new YamlPolicyParser().parse("yaml:test", asStream(yaml));
        assertEquals(0, policies.size());
    }

    @Test
    void invalidYamlRaisesIOException() {
        var badYaml = "policies: [ { name: x, type: pii-redaction ";  // unterminated
        var parser = new YamlPolicyParser();
        var error = assertThrows(IOException.class, () -> parser.parse("yaml:bad", asStream(badYaml)));
        assertTrue(error.getMessage().contains("yaml:bad"));
    }

    @Test
    void unknownTypeRaisesIOExceptionWithPolicyIndex() {
        var yaml = """
                policies:
                  - name: ok
                    type: pii-redaction
                  - name: broken
                    type: nonsense
                """;
        var parser = new YamlPolicyParser();
        var error = assertThrows(IOException.class, () -> parser.parse("yaml:test", asStream(yaml)));
        assertTrue(error.getMessage().contains("policies[1]"));
        assertTrue(error.getMessage().contains("'broken'"));
    }

    @Test
    void nonMappingRootRaisesIOException() {
        var yaml = "- one\n- two\n";
        var parser = new YamlPolicyParser();
        assertThrows(IOException.class, () -> parser.parse("yaml:test", asStream(yaml)));
    }

    @Test
    void rejectsDuplicateKeys() {
        var yaml = """
                policies:
                  - name: dup
                    type: pii-redaction
                    type: cost-ceiling
                """;
        var parser = new YamlPolicyParser();
        assertThrows(IOException.class, () -> parser.parse("yaml:test", asStream(yaml)));
    }

    @Test
    void formatIsYaml() {
        assertEquals("yaml", new YamlPolicyParser().format());
    }

    @Test
    void rejectsNullInputStream() {
        var parser = new YamlPolicyParser();
        assertThrows(IllegalArgumentException.class, () -> parser.parse("yaml:test", null));
    }

    @Test
    void serviceLoaderFindsDefaultParser() {
        var loader = ServiceLoader.load(PolicyParser.class);
        PolicyParser yamlParser = null;
        for (var p : loader) {
            if ("yaml".equals(p.format())) {
                yamlParser = p;
                break;
            }
        }
        assertNotNull(yamlParser, "YamlPolicyParser should be discovered via ServiceLoader");
    }

    private static ByteArrayInputStream asStream(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }
}
