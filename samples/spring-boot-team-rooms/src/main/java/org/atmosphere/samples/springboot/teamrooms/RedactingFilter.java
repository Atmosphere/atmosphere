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
import org.atmosphere.cpr.RawMessage;

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
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, redact(message));
    }

    /**
     * Redacts whatever actually reaches the wire.
     *
     * <p>A {@code @Message}-annotated method's return value is encoded <em>before</em>
     * the broadcast filters run — {@code ManagedAtmosphereHandler} calls the encoder and
     * hands {@code IOUtils.deliver} a {@link RawMessage} wrapping the encoded JSON. So a
     * filter that only matches the domain type never fires on the managed path: the
     * secret goes out verbatim. Handling the encoded {@code String} (and the
     * {@code RawMessage} it arrives in) is what makes this filter actually moderate.</p>
     */
    private Object redact(Object message) {
        if (message instanceof RawMessage raw) {
            Object inner = redact(raw.message());
            return inner == raw.message() ? raw : new RawMessage(inner);
        }
        if (message instanceof Message m && m.text() != null) {
            String cleaned = SECRET.matcher(m.text()).replaceAll(REDACTED);
            return cleaned.equals(m.text()) ? m : m.withText(cleaned);
        }
        if (message instanceof String s) {
            String cleaned = SECRET.matcher(s).replaceAll(REDACTED);
            return cleaned.equals(s) ? s : cleaned;
        }
        return message;
    }
}
