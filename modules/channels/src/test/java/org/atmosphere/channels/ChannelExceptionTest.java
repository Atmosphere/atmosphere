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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChannelExceptionTest {

    @Test
    void twoArgConstructorFormatsMessageAndDefaultsNotRetryable() {
        var ex = new ChannelException(ChannelType.TELEGRAM, "rate limited");
        assertEquals("[telegram] rate limited", ex.getMessage());
        assertEquals(ChannelType.TELEGRAM, ex.channelType());
        assertFalse(ex.isRetryable());
    }

    @Test
    void threeArgConstructorSetsRetryableFlag() {
        var ex = new ChannelException(ChannelType.SLACK, "timeout", true);
        assertEquals("[slack] timeout", ex.getMessage());
        assertEquals(ChannelType.SLACK, ex.channelType());
        assertTrue(ex.isRetryable());
    }

    @Test
    void threeArgConstructorExplicitlyNotRetryable() {
        var ex = new ChannelException(ChannelType.DISCORD, "auth failed", false);
        assertEquals("[discord] auth failed", ex.getMessage());
        assertFalse(ex.isRetryable());
    }

    @Test
    void fourArgConstructorWithCause() {
        var cause = new RuntimeException("connection reset");
        var ex = new ChannelException(ChannelType.WHATSAPP, "send failed", cause, true);
        assertEquals("[whatsapp] send failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertEquals(ChannelType.WHATSAPP, ex.channelType());
        assertTrue(ex.isRetryable());
    }

    @Test
    void fourArgConstructorNotRetryableWithCause() {
        var cause = new IllegalStateException("bad config");
        var ex = new ChannelException(ChannelType.MESSENGER, "config error", cause, false);
        assertEquals("[messenger] config error", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertFalse(ex.isRetryable());
    }

    @Test
    void extendsRuntimeException() {
        var ex = new ChannelException(ChannelType.TELEGRAM, "test");
        assertTrue(ex instanceof RuntimeException);
    }

    @Test
    void messageFormatUsesChannelId() {
        for (ChannelType type : ChannelType.values()) {
            var ex = new ChannelException(type, "error");
            assertEquals("[" + type.id() + "] error", ex.getMessage());
        }
    }
}
