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

import org.atmosphere.cache.BroadcasterCacheInspector;
import org.atmosphere.config.service.BroadcasterCacheInspectorService;
import org.atmosphere.cache.BroadcastMessage;

/**
 * Decides what is worth caching at all.
 *
 * <p>{@code @BroadcasterCacheInspectorService} attaches this to the installed cache. Where
 * {@link ReplayCache} bounds how much is kept, this bounds <em>what</em> is kept: an empty
 * or whitespace-only line is noise on reconnect, and announcements are already delivered to
 * every room by {@link Announcements}, so replaying them per-room would duplicate them.</p>
 */
@BroadcasterCacheInspectorService
public class RecentOnlyInspector implements BroadcasterCacheInspector {

    @Override
    public boolean inspect(BroadcastMessage message) {
        Object m = message.message();
        if (m instanceof Message chat) {
            return chat.text() != null && !chat.text().isBlank() && !"*".equals(chat.room());
        }
        return true;
    }
}
