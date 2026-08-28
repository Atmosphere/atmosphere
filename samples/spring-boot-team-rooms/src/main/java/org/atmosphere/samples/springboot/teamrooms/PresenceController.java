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

import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Makes the annotation-installed machinery observable from a browser: room occupancy comes
 * from {@link PresenceRegistry} (a {@code @BroadcasterListenerService}) and the replay
 * counters from {@link CacheAuditListener} (a {@code @BroadcasterCacheListenerService}).
 */
@RestController
public class PresenceController {

    @GetMapping("/api/presence")
    public Map<String, Object> presence() {
        return Map.of(
                "rooms", PresenceRegistry.snapshot(),
                "cacheAdded", CacheAuditListener.added(),
                "cacheRemoved", CacheAuditListener.removed());
    }
}
