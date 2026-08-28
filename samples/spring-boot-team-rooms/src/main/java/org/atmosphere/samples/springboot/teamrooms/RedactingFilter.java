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
package org.atmosphere.samples.springboot.teamrooms;

import java.util.regex.Pattern;

import org.atmosphere.config.service.BroadcasterFilterService;
import org.atmosphere.cpr.BroadcastFilter;

/**
 * Moderation that cannot be bypassed by a client. Installed on <em>every</em> Broadcaster
 * by {@code @BroadcasterFilterService}, it rewrites outbound text on the way to the wire —
 * so it applies to every room, to announcements, and to anything added later, without the
 * endpoints knowing it exists.
 *
 * <p>This is the difference between filtering in the handler and filtering in the pipeline:
 * a handler-level check protects the one path it lives on.</p>
 */
@BroadcasterFilterService
public class RedactingFilter implements BroadcastFilter {

    /** Anything shaped like a bearer token, an AWS key, or a long hex secret. */
    private static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(?:bearer\\s+[a-z0-9._~+/-]{12,}|AKIA[0-9A-Z]{12,}|[a-f0-9]{32,})\\b");

    static final String REDACTED = "[redacted]";

    @Override
    public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
        if (message instanceof Message m && m.text() != null) {
            String cleaned = SECRET.matcher(m.text()).replaceAll(REDACTED);
            if (!cleaned.equals(m.text())) {
                return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, m.withText(cleaned));
            }
        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }
}
