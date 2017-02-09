/*
 * Copyright 2017 Async-IO.org
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

import java.io.Serializable;

public class CacheMessage implements Serializable {
    private static final long serialVersionUID = -126253550299206646L;

    private final Object message;

    private final String id;
    private final long createTime;
    private final String uuid;
    private final String broadcasterId;

    public CacheMessage(String id, Object message, String uuid, String broadcasterId) {
        this.id = id;
        this.message = message;
        this.createTime = System.nanoTime();
        this.uuid = uuid;
        this.broadcasterId = broadcasterId;
    }

    public CacheMessage(String id, Long now, Object message, String uuid, String broadcasterId) {
        this.id = id;
        this.message = message;
        this.createTime = now;
        this.uuid = uuid;
        this.broadcasterId = broadcasterId;
    }

    public Object getMessage() {
        return message;
    }

    public String getId() {
        return id;
    }

    public String toString() {
        return message.toString();
    }

    public long getCreateTime() {
        return createTime;
    }

    /**
     * Return the {@link org.atmosphere.runtime.AtmosphereResource#uuid()}
     * @return {@link org.atmosphere.runtime.AtmosphereResource#uuid()}
     */
    public String uuid(){
        return uuid;
    }

    public String getBroadcasterId(){
        return this.broadcasterId;
    }
}
