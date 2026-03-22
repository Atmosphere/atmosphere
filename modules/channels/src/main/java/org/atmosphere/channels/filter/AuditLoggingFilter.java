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
package org.atmosphere.channels.filter;

import org.atmosphere.channels.ChannelFilter;
import org.atmosphere.channels.ChannelType;
import org.atmosphere.channels.IncomingMessage;
import org.atmosphere.channels.OutgoingMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs all inbound and outbound channel messages at INFO level.
 * <p>
 * Runs first (order=10) so it captures messages before any filter
 * transforms or blocks them.
 */
public class AuditLoggingFilter implements ChannelFilter {

    private static final Logger log = LoggerFactory.getLogger(AuditLoggingFilter.class);

    @Override
    public IncomingMessage onIncoming(IncomingMessage message) {
        log.info("[AUDIT] IN  [{}] from={} text={}",
                message.channelType().id(),
                message.senderId(),
                message.text().substring(0, Math.min(200, message.text().length())));
        return message;
    }

    @Override
    public OutgoingMessage onOutgoing(OutgoingMessage message, ChannelType target) {
        log.info("[AUDIT] OUT [{}] to={} len={}",
                target.id(),
                message.recipientId(),
                message.text().length());
        return message;
    }

    @Override
    public int order() {
        return 10;
    }
}
