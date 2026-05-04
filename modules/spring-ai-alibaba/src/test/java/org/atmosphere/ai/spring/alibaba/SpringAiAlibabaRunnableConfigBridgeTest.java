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
package org.atmosphere.ai.spring.alibaba;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import org.atmosphere.ai.AgentExecutionContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the per-request Spring AI Alibaba {@link RunnableConfig} sidecar
 * ({@link SpringAiAlibabaRunnableConfig}). Without an attached config the
 * runtime falls back to {@code agent.call(messages)}; with one attached it
 * dispatches via {@code agent.call(messages, config)} so thread continuation
 * ({@code threadId}), checkpoint resume, stream mode, metadata, and store all
 * flow through. The runtime read path is exercised end-to-end by
 * {@code SpringAiAlibabaRuntimeContractTest}; this test pins the helper-level
 * semantics.
 */
class SpringAiAlibabaRunnableConfigBridgeTest {

    @Test
    void fromReturnsNullWhenNoSlot() {
        var ctx = baseContext(Map.of());
        assertNull(SpringAiAlibabaRunnableConfig.from(ctx),
                "missing slot must yield null so the runtime falls back to "
                        + "the no-arg agent.call(messages) overload");
    }

    @Test
    void fromReturnsNullWhenContextIsNull() {
        assertNull(SpringAiAlibabaRunnableConfig.from(null),
                "null context must yield null rather than NPE");
    }

    @Test
    void fromRejectsNonRunnableConfigSlot() {
        var ctx = baseContext(Map.of(SpringAiAlibabaRunnableConfig.METADATA_KEY, "not a config"));
        var iae = assertThrows(IllegalArgumentException.class,
                () -> SpringAiAlibabaRunnableConfig.from(ctx),
                "a non-RunnableConfig slot must fail loudly — silently dropping "
                        + "the override would mask the per-request thread / checkpoint "
                        + "/ streamMode never being honored");
        assertTrue(iae.getMessage().contains(SpringAiAlibabaRunnableConfig.METADATA_KEY));
        assertTrue(iae.getMessage().contains(RunnableConfig.class.getName()));
    }

    @Test
    void attachStoresConfigUnderCanonicalKey() {
        var config = newRunnableConfig("thread-1");
        var ctx = SpringAiAlibabaRunnableConfig.attach(baseContext(Map.of()), config);
        assertSame(config, ctx.metadata().get(SpringAiAlibabaRunnableConfig.METADATA_KEY));
        assertSame(config, SpringAiAlibabaRunnableConfig.from(ctx),
                "round-trip from(attach(ctx, c)) must return c");
    }

    @Test
    void attachReplacesPreviousConfig() {
        var first = newRunnableConfig("thread-A");
        var second = newRunnableConfig("thread-B");
        var withFirst = SpringAiAlibabaRunnableConfig.attach(baseContext(Map.of()), first);
        var withSecond = SpringAiAlibabaRunnableConfig.attach(withFirst, second);
        assertSame(second, SpringAiAlibabaRunnableConfig.from(withSecond),
                "a request dispatches against exactly one RunnableConfig — "
                        + "attach replaces");
        assertNotSame(withFirst, withSecond);
    }

    @Test
    void attachRejectsNullArgs() {
        var ctx = baseContext(Map.of());
        assertThrows(IllegalArgumentException.class,
                () -> SpringAiAlibabaRunnableConfig.attach(ctx, null),
                "null config must fail loudly at attach time");
        assertThrows(IllegalArgumentException.class,
                () -> SpringAiAlibabaRunnableConfig.attach(null, newRunnableConfig("t")),
                "null context must fail loudly at attach time");
    }

    @Test
    void attachPreservesOtherMetadataEntries() {
        var ctx = baseContext(Map.of("other.key", "preserved"));
        var with = SpringAiAlibabaRunnableConfig.attach(ctx, newRunnableConfig("t"));
        assertEquals("preserved", with.metadata().get("other.key"),
                "attach must not clobber unrelated metadata entries");
    }

    private static RunnableConfig newRunnableConfig(String threadId) {
        return RunnableConfig.builder()
                .threadId(threadId)
                .build();
    }

    private static AgentExecutionContext baseContext(Map<String, Object> metadata) {
        return new AgentExecutionContext(
                "Hello", "You are helpful", "qwen-turbo",
                null, "session-1", "user-1", "conv-1",
                List.of(), null, null, List.of(), metadata,
                List.of(), null, null);
    }
}
