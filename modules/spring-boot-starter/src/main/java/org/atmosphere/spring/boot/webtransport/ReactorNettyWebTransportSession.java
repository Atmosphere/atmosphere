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
package org.atmosphere.spring.boot.webtransport;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.webtransport.WebTransportSession;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link WebTransportSession} backed by a Netty {@link Channel}
 * representing a QUIC bidirectional stream. Writes are sent as
 * {@link io.netty.buffer.ByteBuf} frames through the Netty channel.
 *
 * <p>This is the WebTransport equivalent of the JSR 356 WebSocket
 * implementation: it bridges Netty's channel API into Atmosphere's
 * abstract session model.</p>
 */
public class ReactorNettyWebTransportSession extends WebTransportSession {

    private final Channel channel;
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public ReactorNettyWebTransportSession(AtmosphereConfig config, Channel channel) {
        super(config);
        this.channel = channel;
    }

    @Override
    public boolean isOpen() {
        return channel.isActive() && !closed.get();
    }

    @Override
    public WebTransportSession write(String s) throws IOException {
        if (!isOpen()) {
            throw new IOException("WebTransport session is closed for " + uuid());
        }
        channel.writeAndFlush(Unpooled.copiedBuffer(s, StandardCharsets.UTF_8));
        lastWrite = System.currentTimeMillis();
        return this;
    }

    @Override
    public WebTransportSession write(byte[] b, int offset, int length) throws IOException {
        if (!isOpen()) {
            throw new IOException("WebTransport session is closed for " + uuid());
        }
        channel.writeAndFlush(Unpooled.wrappedBuffer(b, offset, length));
        lastWrite = System.currentTimeMillis();
        return this;
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            channel.close();
        }
    }

    @Override
    public void close(int errorCode, String reason) {
        close();
    }

    /**
     * Return the underlying Netty channel for advanced use.
     */
    public Channel channel() {
        return channel;
    }
}
