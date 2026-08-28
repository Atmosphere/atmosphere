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

import java.time.Instant;

/**
 * One chat line. A record because it is an immutable data carrier and nothing more.
 *
 * @param room   the room this line belongs to, filled in server-side from the path
 * @param author who typed it
 * @param text   what they typed — may be rewritten by {@link RedactingFilter} on the way out
 * @param at     server-assigned timestamp
 */
public record Message(String room, String author, String text, Instant at) {

    public Message withText(String replacement) {
        return new Message(room, author, replacement, at);
    }

    public Message stamped(String resolvedRoom) {
        return new Message(resolvedRoom, author, text, at == null ? Instant.now() : at);
    }
}
