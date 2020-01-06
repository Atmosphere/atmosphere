/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.cpr;

import java.util.Collection;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author uklance (https://github.com/uklance)
 */
public class DefaultAtmosphereResourceSession implements AtmosphereResourceSession {
    private final ConcurrentMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();
    private volatile boolean valid = true;

    @Override
    public Object setAttribute(String name, Object value) {
        checkValid();
        return attributes.put(name, value);
    }

    @Override
    public Object getAttribute(String name) {
        checkValid();
        return attributes.get(name);
    }

    @Override
    public <T> T getAttribute(String name, Class<T> type) {
        return type.cast(getAttribute(name));
    }

    @Override
    public Collection<String> getAttributeNames() {
        checkValid();
        return Collections.unmodifiableSet(attributes.keySet());
    }

    @Override
    public void invalidate() {
        checkValid();
        valid = false;
        attributes.clear();
    }

    protected void checkValid() {
        if (!valid) {
            throw new IllegalStateException("Session is invalid");
        }
    }
}
