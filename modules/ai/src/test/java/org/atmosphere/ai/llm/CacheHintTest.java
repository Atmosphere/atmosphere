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
package org.atmosphere.ai.llm;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheHintTest {

    @Test
    void noneFactoryReturnsDisabledHint() {
        var hint = CacheHint.none();

        assertEquals(CacheHint.CachePolicy.NONE, hint.policy());
        assertFalse(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void conservativeFactoryReturnsEnabledHint() {
        var hint = CacheHint.conservative();

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, hint.policy());
        assertTrue(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void conservativeWithKeyReturnsEnabledHintWithKey() {
        var hint = CacheHint.conservative("my-key");

        assertEquals(CacheHint.CachePolicy.CONSERVATIVE, hint.policy());
        assertTrue(hint.enabled());
        assertEquals("my-key", hint.cacheKey().orElse(null));
    }

    @Test
    void conservativeWithNullKeyReturnsEmptyOptional() {
        var hint = CacheHint.conservative(null);

        assertTrue(hint.enabled());
        assertTrue(hint.cacheKey().isEmpty());
    }

    @Test
    void aggressiveWithKeyReturnsAggressivePolicy() {
        var hint = CacheHint.aggressive("agg-key");

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, hint.policy());
        assertTrue(hint.enabled());
        assertEquals("agg-key", hint.cacheKey().orElse(null));
    }

    @Test
    void enabledReturnsFalseOnlyForNone() {
        assertFalse(CacheHint.none().enabled());
        assertTrue(CacheHint.conservative().enabled());
        assertTrue(CacheHint.aggressive("k").enabled());
    }

    @Test
    void constructorDefaultsNullPolicyToNone() {
        var hint = new CacheHint(null, Optional.of("key"), Optional.of(Duration.ofMinutes(5)));

        assertEquals(CacheHint.CachePolicy.NONE, hint.policy());
        assertFalse(hint.enabled());
    }

    @Test
    void constructorDefaultsNullCacheKeyToEmpty() {
        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE, null, Optional.empty());

        assertTrue(hint.cacheKey().isEmpty());
    }

    @Test
    void constructorDefaultsNullTtlToEmpty() {
        var hint = new CacheHint(CacheHint.CachePolicy.CONSERVATIVE, Optional.empty(), null);

        assertTrue(hint.ttl().isEmpty());
    }

    @Test
    void constructorPreservesProvidedValues() {
        var ttl = Duration.ofMinutes(10);
        var hint = new CacheHint(CacheHint.CachePolicy.AGGRESSIVE,
                Optional.of("custom-key"), Optional.of(ttl));

        assertEquals(CacheHint.CachePolicy.AGGRESSIVE, hint.policy());
        assertEquals("custom-key", hint.cacheKey().orElse(null));
        assertEquals(ttl, hint.ttl().orElse(null));
    }

    @Test
    void metadataKeyIsNotNull() {
        assertFalse(CacheHint.METADATA_KEY.isBlank());
    }

    @Test
    void fromNullContextReturnsNone() {
        var hint = CacheHint.from(null);
        assertFalse(hint.enabled());
    }
}
