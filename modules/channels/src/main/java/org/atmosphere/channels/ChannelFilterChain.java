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

import java.util.Comparator;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Executes {@link ChannelFilter}s in order for both inbound and outbound messages.
 */
public class ChannelFilterChain {

    private static final Logger log = LoggerFactory.getLogger(ChannelFilterChain.class);

    private final List<ChannelFilter> filters;

    public ChannelFilterChain(List<ChannelFilter> filters) {
        this.filters = filters.stream()
                .sorted(Comparator.comparingInt(ChannelFilter::order))
                .toList();
        if (!filters.isEmpty()) {
            log.info("Channel filter chain: {} filter(s) registered", filters.size());
        }
    }

    /**
     * Run inbound filters. Returns null if any filter blocks the message.
     */
    public IncomingMessage filterIncoming(IncomingMessage message) {
        var current = message;
        for (ChannelFilter filter : filters) {
            current = filter.onIncoming(current);
            if (current == null) {
                log.debug("Inbound message blocked by {}", filter.getClass().getSimpleName());
                return null;
            }
        }
        return current;
    }

    /**
     * Run outbound filters. Returns null if any filter blocks the message.
     */
    public OutgoingMessage filterOutgoing(OutgoingMessage message, ChannelType target) {
        var current = message;
        for (ChannelFilter filter : filters) {
            current = filter.onOutgoing(current, target);
            if (current == null) {
                log.debug("Outbound message blocked by {}", filter.getClass().getSimpleName());
                return null;
            }
        }
        return current;
    }
}
