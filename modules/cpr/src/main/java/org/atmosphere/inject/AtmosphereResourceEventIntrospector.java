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
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link AtmosphereResourceEvent} injection implementation support.
 * <p>
 * One managed service instance can serve many resources at once (every client of
 * a room path, for instance), and each callback re-injects its own event into the
 * shared field just before it runs. While a callback runs, the injected proxy
 * resolves the event of the resource whose callback runs on the calling thread,
 * so concurrent callbacks never read each other's event. Outside a callback (a
 * reference handed to another thread, a broadcast delivery that injects nothing)
 * the proxy acts on the event it was injected for.
 * <p>
 * The framework brackets every callback that may inject with {@link #current()}
 * and {@link #restore(AtmosphereResourceEvent)}, so a pooled thread never keeps
 * the event of a callback it ran earlier.
 *
 * @author Jeanfrancois Arcand
 */
@RequestScoped
public class AtmosphereResourceEventIntrospector extends InjectIntrospectorAdapter<AtmosphereResourceEvent> {

    private static final ThreadLocal<AtmosphereResourceEvent> CALLBACK_EVENT = new ThreadLocal<>();

    /**
     * The event injected by the callback running on the calling thread, to be
     * handed back to {@link #restore(AtmosphereResourceEvent)} when the caller's
     * own callback ends.
     *
     * @return the event the calling thread resolves now, or {@code null}
     */
    public static AtmosphereResourceEvent current() {
        return CALLBACK_EVENT.get();
    }

    /**
     * End a callback: the calling thread resolves again what it resolved when
     * the callback started (an enclosing callback's event, or nothing).
     *
     * @param previous the value {@link #current()} returned when the callback started
     */
    public static void restore(AtmosphereResourceEvent previous) {
        if (previous == null) {
            CALLBACK_EVENT.remove();
        } else {
            CALLBACK_EVENT.set(previous);
        }
    }

    @Override
    public boolean supportedType(Type t) {
        return t instanceof Class<?> c && AtmosphereResourceEvent.class.isAssignableFrom(c);
    }

    @Override
    public AtmosphereResourceEvent injectable(AtmosphereResource r) {
        var injected = r.getAtmosphereResourceEvent();
        CALLBACK_EVENT.set(injected);
        return (AtmosphereResourceEvent) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{AtmosphereResourceEvent.class}, (target, method, args) -> {
                    var event = CALLBACK_EVENT.get();
                    if (event == null) {
                        event = injected;
                    }
                    try {
                        return method.invoke(event, args);
                    } catch (InvocationTargetException ex) {
                        throw ex.getTargetException();
                    }
                });
    }
}
