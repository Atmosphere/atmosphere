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
package org.atmosphere.a2a.runtime;

import org.atmosphere.protocol.ProtocolSession;

import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class A2aSession extends ProtocolSession {

    public static final String ATTRIBUTE_KEY = "org.atmosphere.a2a.session";
    public static final String SESSION_ID_HEADER = "A2a-Session-Id";

    private final Set<String> activeTaskIds = ConcurrentHashMap.newKeySet();

    public A2aSession() {
        super();
    }

    public A2aSession(int maxPending) {
        super(maxPending);
    }

    public void trackTask(String taskId) {
        activeTaskIds.add(taskId);
    }

    public void untrackTask(String taskId) {
        activeTaskIds.remove(taskId);
    }

    public Set<String> activeTaskIds() {
        return Collections.unmodifiableSet(activeTaskIds);
    }
}
