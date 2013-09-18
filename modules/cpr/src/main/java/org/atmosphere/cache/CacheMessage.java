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

public class CacheMessage {

    private final Object message;

    private final String id;
    private long createTime;

    public CacheMessage(String id, Object message) {
        this.id = id;
        this.message = message;
        this.createTime = System.nanoTime();
    }

    public CacheMessage(String id, Long now, Object message) {
        this.id = id;
        this.message = message;
        this.createTime = now;
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
}
