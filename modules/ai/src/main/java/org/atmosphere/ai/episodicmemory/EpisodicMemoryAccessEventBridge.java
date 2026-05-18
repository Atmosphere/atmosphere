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
package org.atmosphere.ai.episodicmemory;

import org.atmosphere.ai.jfr.EpisodicMemoryAccessEvent;

/**
 * Package-private helper that shares the JFR emission boilerplate between
 * the bundled {@link EpisodicMemoryStore} implementations so they report
 * via the same event type without leaking JFR details into the SPI.
 */
final class EpisodicMemoryAccessEventBridge {

    static final String STORE = EpisodicMemoryAccessEvent.OPERATION_STORE;
    static final String RECALL = EpisodicMemoryAccessEvent.OPERATION_RECALL;
    static final String FORGET = EpisodicMemoryAccessEvent.OPERATION_FORGET;

    private EpisodicMemoryAccessEventBridge() { }

    static void emit(Class<?> storeClass, String operation,
                     EpisodicMemoryType type, int count) {
        var event = new EpisodicMemoryAccessEvent();
        if (!event.shouldCommit()) {
            return;
        }
        event.storeClass = storeClass != null ? storeClass.getName() : "unknown";
        event.operation = operation != null ? operation : "unknown";
        event.type = type != null ? type.name() : "";
        event.count = count;
        event.commit();
    }
}
