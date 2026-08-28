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

import org.atmosphere.config.service.DeliverTo;
import org.atmosphere.config.service.ManagedService;
import org.atmosphere.config.service.Message;
import org.atmosphere.config.service.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A single broadcast desk: whatever is posted here reaches every room at once.
 *
 * <p>{@code @Singleton} is correct here and wrong on {@link RoomChat}. This class has no
 * path template and holds no per-connection state, so one instance for the whole
 * application is exactly right — and it avoids the per-request instantiation a templated
 * service pays for.</p>
 *
 * <p>{@code @DeliverTo(ALL)} is what makes the fan-out happen: the returned value goes to
 * every created Broadcaster rather than just the one this connection belongs to. Without
 * it the announcement would only reach clients connected to this endpoint, which is
 * nobody — people are in rooms.</p>
 */
@Singleton
@ManagedService(path = "/atmosphere/announcements")
public class Announcements {

    private static final Logger logger = LoggerFactory.getLogger(Announcements.class);

    @Message(encoders = JacksonEncoder.class, decoders = JacksonDecoder.class)
    @DeliverTo(DeliverTo.DELIVER_TO.ALL)
    public org.atmosphere.samples.springboot.teamrooms.Message onAnnouncement(
            org.atmosphere.samples.springboot.teamrooms.Message message) {
        logger.info("announcement from {}: {}", message.author(), message.text());
        return message.stamped("*");
    }
}
