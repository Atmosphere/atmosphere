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
package org.atmosphere.a2a.runtime;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Tests for the {@link LocalDispatchable} interface contract.
 */
class LocalDispatchableTest {

    /** Simple implementation that echoes the request back as a response. */
    static class EchoDispatchable implements LocalDispatchable {
        @Override
        public String dispatchLocal(String jsonRpcRequest) {
            return "{\"result\":\"" + jsonRpcRequest + "\"}";
        }

        @Override
        public void dispatchLocalStreaming(String jsonRpcRequest,
                                           Consumer<String> onToken, Runnable onComplete) {
            for (String word : jsonRpcRequest.split(" ")) {
                onToken.accept(word);
            }
            onComplete.run();
        }
    }

    /** Implementation that returns null for notifications. */
    static class NotificationDispatchable implements LocalDispatchable {
        @Override
        public String dispatchLocal(String jsonRpcRequest) {
            return null;
        }

        @Override
        public void dispatchLocalStreaming(String jsonRpcRequest,
                                           Consumer<String> onToken, Runnable onComplete) {
            onComplete.run();
        }
    }

    @Test
    void dispatchLocalReturnsResponse() {
        var dispatchable = new EchoDispatchable();
        var result = dispatchable.dispatchLocal("hello");
        assertEquals("{\"result\":\"hello\"}", result);
    }

    @Test
    void dispatchLocalReturnsNullForNotifications() {
        var dispatchable = new NotificationDispatchable();
        assertNull(dispatchable.dispatchLocal("{\"method\":\"notify\"}"));
    }

    @Test
    void dispatchLocalStreamingDeliversTokensAndCompletes() {
        var dispatchable = new EchoDispatchable();
        List<String> tokens = new ArrayList<>();
        var completed = new AtomicBoolean(false);

        dispatchable.dispatchLocalStreaming("one two three",
                tokens::add, () -> completed.set(true));

        assertEquals(List.of("one", "two", "three"), tokens);
        assertEquals(true, completed.get());
    }

    @Test
    void dispatchLocalStreamingCompletesWithNoTokens() {
        var dispatchable = new NotificationDispatchable();
        List<String> tokens = new ArrayList<>();
        var completed = new AtomicBoolean(false);

        dispatchable.dispatchLocalStreaming("{\"method\":\"notify\"}",
                tokens::add, () -> completed.set(true));

        assertEquals(List.of(), tokens);
        assertEquals(true, completed.get());
    }
}
