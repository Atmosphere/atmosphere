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

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * The resource whose callback runs on the calling thread, which the request
 * scoped {@code @Inject} fields of a shared service resolve.
 * <p>
 * One managed service instance serves every client of its path, and each
 * callback re-injects its own {@link AtmosphereResource},
 * {@link AtmosphereRequest}, {@link AtmosphereResponse} and
 * {@link AtmosphereResourceEvent} into the shared fields just before it runs. A
 * field therefore holds a proxy, which acts on one of two resources:
 * <ul>
 * <li>a proxy written into its field after the callback running on the calling
 * thread started (the callback's own injection, or a concurrent callback's that
 * replaced it in the field) resolves the resource of that callback, so
 * concurrent callbacks never read each other's client;</li>
 * <li>any other proxy, a reference stored by an earlier callback (a map of
 * connected users) or used outside every callback, acts on the resource it was
 * injected for.</li>
 * </ul>
 * What decides is when the proxy was written into its field, not when it was
 * created: a concurrent injection can create its proxy before the calling
 * thread's callback starts and write it into the shared field after that
 * callback's own injection. Which one never changes while a callback runs. One
 * window remains, inherent to a field shared by concurrent callbacks: a
 * reference a concurrent callback stored after the calling thread's callback
 * started resolves, for the rest of that callback, the calling thread's
 * resource.
 * <p>
 * An injection pass binds its resource to the injecting thread. A handler that
 * runs a callback on a thread that injected nothing (a broadcast delivery, a
 * {@code @Disconnect} a broadcast thread claimed) injects the callback's
 * resource again and binds it itself. The framework brackets every callback
 * that may bind with {@link #current()} and {@link #restore(Binding)}, so a
 * pooled thread never keeps the resource of a callback it ran earlier.
 */
public final class CallbackScope {

    private static final ThreadLocal<Binding> CALLBACK = new ThreadLocal<>();

    /** Orders the start of each callback and the writing of each injected proxy into its field. */
    private static final AtomicLong CLOCK = new AtomicLong();

    /**
     * The resource of the callback running on a thread, the event that callback
     * runs for, and when the callback started.
     *
     * @param resource the resource
     * @param event    the event of the callback, or {@code null} for the resource's own
     * @param since    when the callback started, on the clock that orders injections
     */
    public record Binding(AtmosphereResource resource, AtmosphereResourceEvent event, long since) {

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
     * Run a callback of {@code resource} on the calling thread. A {@code null}
     * resource (an event already destroyed) binds nothing. A callback of the
     * resource the thread already runs one for (a request that injected it, a
     * callback nested in another of the same resource) keeps its start, so the
     * fields that injection filled still resolve it.
     *
     * @param resource the resource whose callback runs
     * @param event    the event the callback runs for, or {@code null} for the resource's own
     * @return the binding to {@link #restore(Binding)} when the callback ends
     */
    public static Binding bind(AtmosphereResource resource, AtmosphereResourceEvent event) {
        var previous = CALLBACK.get();
        if (resource != null) {
            var bound = unwrap(resource);
            var since = previous != null && previous.resource() == bound ? previous.since() : CLOCK.incrementAndGet();
            CALLBACK.set(new Binding(bound, event, since));
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
     * The object an injected proxy acts on now (see the class description).
     * Anything else is returned as is.
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
     * Start a callback of {@code resource} on the calling thread for an injection
     * pass: the proxies the pass injects resolve it there.
     *
     * @param resource the resource being injected
     */
    static void enter(AtmosphereResource resource) {
        CALLBACK.set(new Binding(unwrap(resource), resource.getAtmosphereResourceEvent(), CLOCK.incrementAndGet()));
    }

    /**
     * Return the handler of the proxy a shared field holds for {@code injected},
     * binding {@code injected} to the injecting thread unless a callback of it
     * already runs there. Each introspector creates its proxy itself, with a
     * literal interface array, so that native-image analysis can detect the proxy
     * class.
     *
     * @param injected the resource being injected
     * @param resolve  what the field resolves from the bound resource
     * @return the handler of the injected proxy
     */
    static InvocationHandler inject(AtmosphereResource injected, Function<Binding, ?> resolve) {
        var resource = unwrap(injected);
        var bound = CALLBACK.get();
        if (bound == null || bound.resource() != resource) {
            enter(resource);
            bound = CALLBACK.get();
        }
        var binding = new Binding(resource, injected.getAtmosphereResourceEvent(), bound.since());
        return new Resolver(binding, resolve);
    }

    /**
     * Write {@code value}, what an introspector injected, into {@code field} of
     * the shared {@code instance}, then record when a proxy got there: from then
     * on, a callback started earlier reads it as a reference stored for its own
     * client, and a callback started later as its own injection.
     *
     * @param instance the service instance
     * @param field    the field, accessible
     * @param value    the injected proxy, or any other value
     * @throws IllegalAccessException if the field cannot be set
     */
    static void publish(Object instance, Field field, Object value) throws IllegalAccessException {
        field.set(instance, value);
        if (value != null && Proxy.isProxyClass(value.getClass())
                && Proxy.getInvocationHandler(value) instanceof Resolver resolver) {
            resolver.published = CLOCK.incrementAndGet();
        }
    }

    /** Resolves what an injected proxy acts on (see the class description). */
    private static final class Resolver implements InvocationHandler {

        /** Not written into a field yet: the proxy stands for the running callback's client. */
        private static final long UNPUBLISHED = Long.MAX_VALUE;

        private final Binding injected;
        private final Function<Binding, ?> resolve;
        /**
         * When the proxy was written into its field, on the clock that orders
         * callbacks. Stamped only after the write: a callback that reads the
         * field in between sees {@link #UNPUBLISHED}, which resolves its own
         * client, as the stamp would.
         */
        private volatile long published = UNPUBLISHED;

        /**
         * @param injected the binding of the resource the proxy was injected for
         * @param resolve  what the proxy acts on, from a binding
         */
        Resolver(Binding injected, Function<Binding, ?> resolve) {
            this.injected = injected;
            this.resolve = resolve;
        }

        Object target() {
            var binding = CALLBACK.get();
            // Only a proxy written into its field during the running callback stands
            // for that callback's client; an older one is a reference stored for its own.
            return resolve.apply(binding != null && published > binding.since() ? binding : injected);
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
