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
package org.atmosphere.ai.cache;

import org.atmosphere.ai.AiEnvironment;
import org.atmosphere.ai.EmbeddingRuntimeResolver;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * An endpoint's semantic response cache scores similarity with its own
 * application's embedding runtime while another application runs in the JVM,
 * not with what the JVM-wide static API resolves then (the process default,
 * which may have none).
 */
class ResponseCacheApplicationEmbeddingsTest {

    private final List<AiEnvironment> environments = new ArrayList<>();

    @AfterEach
    void tearDown() {
        environments.forEach(AiEnvironment::close);
        EmbeddingRuntimeResolver.resetCache();
    }

    @Test
    void theSemanticCacheUsesItsApplicationsEmbeddingRuntime() throws Exception {
        var first = config("remote");
        config("fake");

        var resolved = ResponseCacheConfig.resolve(first);

        assertTrue(resolved.isPresent(), "the application's own embedding runtime backs its cache");
        var field = SemanticResponseCache.class.getDeclaredField("embeddings");
        field.setAccessible(true);
        assertSame(AiEnvironment.of(first).embeddings().resolve().orElseThrow(),
                field.get(resolved.get().cache()),
                "the cache must score with its own application's embedding runtime instance");
    }

    /** An application whose semantic response cache is on, with its own environment. */
    private AtmosphereConfig config(String mode) {
        var config = mock(AtmosphereConfig.class);
        when(config.framework()).thenReturn(new AtmosphereFramework());
        when(config.properties()).thenReturn(new ConcurrentHashMap<>());
        when(config.getInitParameter(anyString(), anyBoolean())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getInitParameter(anyString(), anyInt())).thenAnswer(inv -> inv.getArgument(1));
        when(config.getInitParameter(ResponseCacheConfig.SEMANTIC_KEY, false)).thenReturn(true);
        var environment = AiEnvironment.create(config, key -> null);
        environments.add(environment);
        environment.configure(mode, "model-" + mode, "k", "http://127.0.0.1:9/v1");
        return config;
    }
}
