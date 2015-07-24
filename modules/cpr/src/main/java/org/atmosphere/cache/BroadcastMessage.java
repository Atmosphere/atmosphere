/*
 * Copyright 2015 Async-IO.org
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

import java.util.UUID;

/**
 * A wrapper around an object passed to {@link org.atmosphere.cpr.Broadcaster#broadcast(Object)}
 *
 * @author Jeanfrancois Arcand
 */
public final class BroadcastMessage {

    private final String id;
    private final Object message;

    public BroadcastMessage(String id, Object message) {
        this.id = id;
        this.message = message;
    }

    public BroadcastMessage(Object message) {
        this(UUID.randomUUID().toString(), message);
    }

    public String id() {
        return id;
    }

    public Object message() {
        return message;
    }

}
