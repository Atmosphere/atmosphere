/*
 * Copyright 2013 Jeanfrancois Arcand
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
package org.atmosphere.cache;

/**
 * Inspect {@link BroadcastMessage}s before they get added to the BroadcasterCache. Messages can also be modified
 * before they get added to the cache.
 *
 * @author Jeanfrancois Arcand
 */
public interface BroadcasterCacheInspector {
    /**
     * Inspect the {@link BroadcastMessage} and return true if the message can be cached, false if not. A
     * Message can also be modified.
     * @param message {@link BroadcastMessage}
     * @return true if allowed to be cached, false if not.
     */
    boolean inspect(BroadcastMessage message);
}
