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
package org.atmosphere.util;

import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.servlet.http.HttpSessionContext;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

public class FakeHttpSession implements HttpSession {
    private final long creationTime;
    private final ConcurrentHashMap<String, Object> attributes = new ConcurrentHashMap<String, Object>();
    private final String sessionId;
    private final ServletContext servletContext;
    private int maxInactiveInterval;
    private final AtomicBoolean valid = new AtomicBoolean(true);
      
    public FakeHttpSession(String sessionId, ServletContext servletContext, long creationTime, int maxInactiveInterval) {
        this.sessionId = sessionId;
        this.servletContext = servletContext;
        this.creationTime = creationTime;
        this.maxInactiveInterval = maxInactiveInterval;
    }

    public FakeHttpSession(HttpSession session) {
        this(session.getId(), session.getServletContext(), session.getLastAccessedTime(), session.getMaxInactiveInterval());
        copyAttributes(session);
    }

    public void destroy() {
        attributes.clear();
    }

    @Override
    public long getCreationTime() {
        if (!valid.get()) throw new IllegalStateException();
        return creationTime;
    }

    @Override
    public String getId() {
        if (!valid.get()) throw new IllegalStateException();
        return sessionId;
    }

    // TODO: Not supported for now. Must update on every WebSocket Message
    @Override
    public long getLastAccessedTime() {
        if (!valid.get()) throw new IllegalStateException();
        return 0;
    }

    @Override
    public ServletContext getServletContext() {
        return servletContext;
    }

    @Override
    public void setMaxInactiveInterval(int interval) {
        this.maxInactiveInterval = interval;
    }

    @Override
    public int getMaxInactiveInterval() {
        return maxInactiveInterval;
    }

    @Override
    public HttpSessionContext getSessionContext() {
        return null;
    }

    @Override
    public Object getAttribute(String name) {
        if (!valid.get()) throw new IllegalStateException();
        return attributes.get(name);
    }

    @Override
    public Object getValue(String name) {
        if (!valid.get()) throw new IllegalStateException();
        return attributes.get(name);
    }

    @Override
    public Enumeration<String> getAttributeNames() {
        if (!valid.get()) throw new IllegalStateException();
        return attributes.keys();
    }

    @Override
    public String[] getValueNames() {
        if (!valid.get()) throw new IllegalStateException();
        return (String[]) Collections.list(attributes.keys()).toArray();
    }

    @Override
    public void setAttribute(String name, Object value) {
        if (!valid.get()) throw new IllegalStateException();
        attributes.put(name, value);
    }

    @Override
    public void putValue(String name, Object value) {
        if (!valid.get()) throw new IllegalStateException();
        attributes.put(name, value);
    }

    @Override
    public void removeAttribute(String name) {
        if (!valid.get()) throw new IllegalStateException();
        attributes.remove(name);
    }

    @Override
    public void removeValue(String name) {
        if (!valid.get()) throw new IllegalStateException();
        attributes.remove(name);
    }

    public FakeHttpSession copyAttributes(HttpSession httpSession){
        Enumeration<String> e = httpSession.getAttributeNames();
        String k;
        while(e.hasMoreElements()) {
            k = e.nextElement();
            if (k == null) continue;

            Object o = httpSession.getAttribute(k);
            if (o == null) continue;

            attributes.put(k, o);
        }
        return this;
    }

    @Override
    public void invalidate() {
        if (!valid.get()) throw new IllegalStateException();
    	valid.set(false);
    }

    @Override
    public boolean isNew() {
        if (!valid.get()) throw new IllegalStateException();
        return false;
    }


}
