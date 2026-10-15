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

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.function.Function;

/**
 * The resource whose callback runs on the calling thread, which the request
 * scoped {@code @Inject} fields of a shared service resolve.
 * <p>
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own {@link AtmosphereResource},
 * {@link AtmosphereRequest}, {@link AtmosphereResponse} and
 * {@link AtmosphereResourceEvent} into the shared fields just before it runs. A
 * field therefore holds a proxy: while a callback runs, it resolves the
 * resource bound to the calling thread, so concurrent callbacks never read each
 * other's client. Outside a callback (a reference handed to another thread) the
 * proxy acts on the resource it was injected for.
 * <p>
 * An injection binds its resource to the injecting thread. A handler that runs
 * a callback on a thread that injected nothing (a broadcast delivery, a
 * {@code @Disconnect} a broadcast thread claimed) binds the callback's resource
 * itself. The framework brackets every callback that may bind with
 * {@link #current()} and {@link #restore(Binding)}, so a pooled thread never
 * keeps the resource of a callback it ran earlier.
 */
public final class CallbackScope {

    private static final ThreadLocal<Binding> CALLBACK = new ThreadLocal<>();

    /**
     * The resource of the callback running on a thread, and the event that
     * callback runs for.
     *
     * @param resource the resource
     * @param event    the event of the callback, or {@code null} for the resource's own
     */
    public record Binding(AtmosphereResource resource, AtmosphereResourceEvent event) {

        AtmosphereResourceEvent resolvedEvent() {
            return event != null ? event : resource.getAtmosphereResourceEvent();
        }

        AtmosphereRequest request() {
            // Unscoped: a @Disconnect still reads the request of its cancelled resource.
            return resource instanceof AtmosphereResourceImpl impl ? impl.getRequest(false) : resource.getRequest();
        }

        AtmosphereResponse response() {
            return resource instanceof AtmosphereResourceImpl impl ? impl.getResponse(false) : resource.getResponse();
        }
    }

    private CallbackScope() {
    }

    /**
     * What the calling thread resolves now, to be handed back to
     * {@link #restore(Binding)} when the caller's own callback ends.
     *
     * @return the binding of the calling thread, or {@code null}
     */
    public static Binding current() {
        return CALLBACK.get();
    }

    /**
     * Start a callback of {@code resource} on the calling thread. A {@code null}
     * resource (an event already destroyed) binds nothing.
     *
     * @param resource the resource whose callback runs
     * @param event    the event the callback runs for, or {@code null} for the resource's own
     * @return the binding to {@link #restore(Binding)} when the callback ends
     */
    public static Binding bind(AtmosphereResource resource, AtmosphereResourceEvent event) {
        var previous = CALLBACK.get();
        if (resource != null) {
            CALLBACK.set(new Binding(unwrap(resource), event));
        }
        return previous;
    }

    /**
     * End a callback: the calling thread resolves again what it resolved when
     * the callback started (an enclosing callback's resource, or nothing).
     *
     * @param previous the value {@link #current()} or {@link #bind} returned when the callback started
     */
    public static void restore(Binding previous) {
        if (previous == null) {
            CALLBACK.remove();
        } else {
            CALLBACK.set(previous);
        }
    }

    /**
     * The object an injected proxy acts on now: the callback's own on the thread
     * running it, the injected one elsewhere. Anything else is returned as is.
     * <p>
     * A service that keeps a client's resource beyond its callback (a map of
     * connected users) must store {@code unwrap(field)}, not the field's value:
     * the proxy resolves the client of whichever callback reads it.
     *
     * @param resource a resource, possibly an injected proxy
     * @return the resource itself, never a proxy
     */
    public static AtmosphereResource unwrap(AtmosphereResource resource) {
        return (AtmosphereResource) target(resource);
    }

    static Object target(Object injected) {
        if (injected != null && Proxy.isProxyClass(injected.getClass())
                && Proxy.getInvocationHandler(injected) instanceof Resolver resolver) {
            return resolver.target();
        }
        return injected;
    }

    /**
     * Bind {@code injected} to the injecting thread, and return the handler of
     * the proxy a shared field holds for it. Each introspector creates its proxy
     * itself, with a literal interface array, so that native-image analysis can
     * detect the proxy class.
     *
     * @param injected the resource being injected
     * @param resolve  what the field resolves from the bound resource
     * @return the handler of the injected proxy
     */
    static InvocationHandler inject(AtmosphereResource injected, Function<Binding, ?> resolve) {
        var binding = new Binding(unwrap(injected), injected.getAtmosphereResourceEvent());
        CALLBACK.set(binding);
        return new Resolver(binding, resolve);
    }

    private record Resolver(Binding injected, Function<Binding, ?> resolve) implements InvocationHandler {

        Object target() {
            var binding = CALLBACK.get();
            return resolve.apply(binding != null ? binding : injected);
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (args != null && args.length == 1 && method.getName().equals("equals")
                    && method.getParameterTypes()[0] == Object.class) {
                // Two injected proxies, or a proxy and its resource, are equal.
                args = new Object[]{CallbackScope.target(args[0])};
            }
            try {
                return method.invoke(target(), args);
            } catch (InvocationTargetException ex) {
                throw ex.getTargetException();
            }
        }
    }
}
