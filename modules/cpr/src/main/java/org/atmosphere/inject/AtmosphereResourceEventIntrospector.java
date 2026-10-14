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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.inject.annotation.RequestScoped;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link AtmosphereResourceEvent} injection implementation support.
 * <p>
 * One managed service instance can serve many resources at once (every client of
 * a room path, for instance), and each callback re-injects its own event into the
 * shared field just before it runs. The field therefore holds one proxy that
 * resolves the event of the resource whose callback runs on the calling thread,
 * so concurrent callbacks never read each other's event. A thread that runs a
 * callback without an injection of its own (a broadcast delivery) sees the most
 * recently injected event.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped
public class AtmosphereResourceEventIntrospector extends InjectIntrospectorAdapter<AtmosphereResourceEvent> {

    private final ThreadLocal<AtmosphereResourceEvent> current = new ThreadLocal<>();
    private volatile AtmosphereResourceEvent latest;
    private final AtmosphereResourceEvent proxy = (AtmosphereResourceEvent) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{AtmosphereResourceEvent.class}, this::invoke);

    @Override
    public boolean supportedType(Type t) {
        return t instanceof Class<?> c && AtmosphereResourceEvent.class.isAssignableFrom(c);
    }

    @Override
    public AtmosphereResourceEvent injectable(AtmosphereResource r) {
        var event = r.getAtmosphereResourceEvent();
        current.set(event);
        latest = event;
        return proxy;
    }

    private Object invoke(Object target, Method method, Object[] args) throws Throwable {
        var event = current.get();
        if (event == null) {
            event = latest;
        }
        if (event == null) {
            throw new IllegalStateException("No AtmosphereResourceEvent injected for " + method.getName());
        }
        try {
            return method.invoke(event, args);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
