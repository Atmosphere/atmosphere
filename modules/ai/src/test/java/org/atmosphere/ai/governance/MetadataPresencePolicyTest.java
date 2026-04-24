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

import org.atmosphere.ai.AiRequest;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MetadataPresencePolicyTest {

    private static PolicyContext reqWith(Map<String, Object> metadata) {
        return new PolicyContext(PolicyContext.Phase.PRE_ADMISSION,
                new AiRequest("msg", null, null, null, null, null, null, metadata, null),
                "");
    }

    @Test
    void admitsWhenAllKeysPresent() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id", "trace-id");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("tenant-id", "acme", "trace-id", "abc-123"))));
    }

    @Test
    void deniesWhenOneKeyMissing() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id", "trace-id");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("tenant-id", "acme"))));
        assertTrue(deny.reason().contains("trace-id"));
    }

    @Test
    void deniesWhenValueIsNull() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id");
        var metadata = new HashMap<String, Object>();
        metadata.put("tenant-id", null);
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(reqWith(metadata)));
    }

    @Test
    void deniesWhenValueIsBlankString() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id");
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("tenant-id", ""))));
        assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("tenant-id", "   "))));
    }

    @Test
    void deniesWhenMetadataMapIsAbsent() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id");
        assertInstanceOf(PolicyDecision.Deny.class, policy.evaluate(reqWith(null)));
    }

    @Test
    void postResponsePhaseAlwaysAdmits() {
        var policy = new MetadataPresencePolicy("attribution", "tenant-id");
        var post = new PolicyContext(PolicyContext.Phase.POST_RESPONSE,
                new AiRequest("msg", null, null, null, null, null, null, null, null),
                "response text");
        assertInstanceOf(PolicyDecision.Admit.class, policy.evaluate(post));
    }

    @Test
    void reportsFirstMissingKey() {
        var policy = new MetadataPresencePolicy("attribution", "a", "b", "c");
        var deny = assertInstanceOf(PolicyDecision.Deny.class,
                policy.evaluate(reqWith(Map.of("a", "x"))));
        assertTrue(deny.reason().contains("'b'"),
                "first missing key in iteration order should be reported");
    }

    @Test
    void acceptsNumericAndBooleanValuesAsPresent() {
        var policy = new MetadataPresencePolicy("attribution", "count", "enabled");
        assertInstanceOf(PolicyDecision.Admit.class,
                policy.evaluate(reqWith(Map.of("count", 42, "enabled", true))));
    }

    @Test
    void requiredKeysExposedForAdminIntrospection() {
        var policy = new MetadataPresencePolicy("x", "tenant-id", "trace-id");
        assertEquals(List.of("tenant-id", "trace-id"), policy.requiredKeys());
    }

    @Test
    void rejectsEmptyKeyList() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataPresencePolicy("x"));
    }

    @Test
    void rejectsBlankKeyEntry() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataPresencePolicy("x", "a", "", "b"));
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataPresencePolicy("x", "a", null, "b"));
    }

    @Test
    void blankNameRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataPresencePolicy("", "a"));
    }
}
