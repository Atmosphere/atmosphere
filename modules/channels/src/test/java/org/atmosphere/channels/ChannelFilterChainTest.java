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
package org.atmosphere.channels;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class ChannelFilterChainTest {

    private static IncomingMessage incoming(String text) {
        return new IncomingMessage(ChannelType.TELEGRAM, "sender1",
                Optional.empty(), text, "conv1", "msg1", Instant.now());
    }

    private static OutgoingMessage outgoing(String text) {
        return new OutgoingMessage("recipient1", text);
    }

    @Test
    void emptyChainPassesIncomingThrough() {
        var chain = new ChannelFilterChain(List.of());
        var msg = incoming("hello");
        var result = chain.filterIncoming(msg);
        assertNotNull(result);
        assertEquals("hello", result.text());
    }

    @Test
    void emptyChainPassesOutgoingThrough() {
        var chain = new ChannelFilterChain(List.of());
        var msg = outgoing("hello");
        var result = chain.filterOutgoing(msg, ChannelType.SLACK);
        assertNotNull(result);
        assertEquals("hello", result.text());
    }

    @Test
    void singleFilterTransformsIncoming() {
        ChannelFilter uppercaser = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                return new IncomingMessage(message.channelType(), message.senderId(),
                        message.senderName(), message.text().toUpperCase(),
                        message.conversationId(), message.messageId(), message.timestamp());
            }
        };
        var chain = new ChannelFilterChain(List.of(uppercaser));
        var result = chain.filterIncoming(incoming("hello"));
        assertNotNull(result);
        assertEquals("HELLO", result.text());
    }

    @Test
    void singleFilterTransformsOutgoing() {
        ChannelFilter appender = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
                return new OutgoingMessage(message.recipientId(),
                        message.text() + " [via " + target.id() + "]");
            }
        };
        var chain = new ChannelFilterChain(List.of(appender));
        var result = chain.filterOutgoing(outgoing("hi"), ChannelType.DISCORD);
        assertNotNull(result);
        assertEquals("hi [via discord]", result.text());
    }

    @Test
    void filterReturningNullBlocksIncoming() {
        ChannelFilter blocker = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                return null;
            }
        };
        var chain = new ChannelFilterChain(List.of(blocker));
        assertNull(chain.filterIncoming(incoming("blocked")));
    }

    @Test
    void filterReturningNullBlocksOutgoing() {
        ChannelFilter blocker = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
                return null;
            }
        };
        var chain = new ChannelFilterChain(List.of(blocker));
        assertNull(chain.filterOutgoing(outgoing("blocked"), ChannelType.TELEGRAM));
    }

    @Test
    void blockingFilterStopsChainForSubsequentFilters() {
        var executionOrder = new ArrayList<String>();
        ChannelFilter first = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                executionOrder.add("first");
                return null;
            }

            @Override
            public int order() { return 1; }
        };
        ChannelFilter second = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                executionOrder.add("second");
                return message;
            }

            @Override
            public int order() { return 2; }
        };
        var chain = new ChannelFilterChain(List.of(second, first));
        assertNull(chain.filterIncoming(incoming("test")));
        assertEquals(List.of("first"), executionOrder);
    }

    @Test
    void filtersExecuteInOrderByPriority() {
        var executionOrder = new ArrayList<String>();
        ChannelFilter low = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                executionOrder.add("low-50");
                return message;
            }

            @Override
            public int order() { return 50; }
        };
        ChannelFilter high = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                executionOrder.add("high-10");
                return message;
            }

            @Override
            public int order() { return 10; }
        };
        ChannelFilter medium = new ChannelFilter() {
            @Override
            public IncomingMessage onIncoming(IncomingMessage message) {
                executionOrder.add("med-30");
                return message;
            }

            @Override
            public int order() { return 30; }
        };
        // Pass in reverse order to verify sorting
        var chain = new ChannelFilterChain(List.of(low, medium, high));
        chain.filterIncoming(incoming("test"));
        assertEquals(List.of("high-10", "med-30", "low-50"), executionOrder);
    }

    @Test
    void multipleFiltersChainTransformations() {
        ChannelFilter prefix = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
                return new OutgoingMessage(message.recipientId(), "[prefix] " + message.text());
            }

            @Override
            public int order() { return 1; }
        };
        ChannelFilter suffix = new ChannelFilter() {
            @Override
            public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
                return new OutgoingMessage(message.recipientId(), message.text() + " [suffix]");
            }

            @Override
            public int order() { return 2; }
        };
        var chain = new ChannelFilterChain(List.of(suffix, prefix));
        var result = chain.filterOutgoing(outgoing("msg"), ChannelType.WHATSAPP);
        assertNotNull(result);
        assertEquals("[prefix] msg [suffix]", result.text());
    }
}
