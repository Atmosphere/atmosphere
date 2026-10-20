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
package org.atmosphere.ai;

import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The connection a run started on, as the run reads it once the connection
 * may be gone. A run outlives its connection: a page reloaded in the middle of
 * a turn (before its first frame, or after) cancels the connection's request,
 * whose every read then throws {@code IllegalStateException: Request object no
 * longer valid}, while the run goes on — the {@code @Prompt} method, the
 * streaming session, the interceptors' {@code preProcess},
 * {@code beforeCompletion} and {@code postProcess} — and a reattached client
 * is written what it produces.
 *
 * <p>This view of the resource delegates everything to it but
 * {@link AtmosphereResource#getRequest()}, which returns a request that reads
 * the live one while it is valid and, once the connection is gone, what it
 * held: its attributes (with those the run set since through this view), its
 * headers and parameters, its principal and its request line, copied when the
 * view was made — when the prompt was admitted — and as the run read them
 * since. An attribute the run sets is set on the live request too while it is
 * valid. Any other method of a request no longer valid answers as the
 * request object itself still does, or with nothing ({@code null},
 * {@code false}, {@code 0}).</p>
 *
 * <p>Only the run reads the connection through this view: identity, the
 * broadcaster and the response stay the resource's own.</p>
 */
public final class RunScopedResource {

    private static final Logger logger = LoggerFactory.getLogger(RunScopedResource.class);

    private RunScopedResource() {
    }

    /**
     * The view of {@code resource} a run reads it through; {@code resource}
     * itself when it is {@code null} or already such a view.
     *
     * @param resource the connection the run started on, its request still valid
     */
    public static AtmosphereResource of(AtmosphereResource resource) {
        if (resource == null || Proxy.isProxyClass(resource.getClass())
                && Proxy.getInvocationHandler(resource) instanceof ResourceView) {
            return resource;
        }
        var request = new RequestView(resource);
        var view = new ResourceView(resource,
                (AtmosphereRequest) Proxy.newProxyInstance(AtmosphereRequest.class.getClassLoader(),
                        new Class<?>[] {AtmosphereRequest.class}, request));
        return (AtmosphereResource) Proxy.newProxyInstance(AtmosphereResource.class.getClassLoader(),
                new Class<?>[] {AtmosphereResource.class}, view);
    }

    /** The resource, but its request. */
    private record ResourceView(AtmosphereResource resource, AtmosphereRequest request) implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if ("getRequest".equals(method.getName()) && method.getParameterCount() == 0) {
                return request;
            }
            if ("equals".equals(method.getName()) && method.getParameterCount() == 1) {
                return proxy == args[0] || resource.equals(args[0]);
            }
            return call(resource, method, args);
        }
    }

    /** The live request while it is valid, what it held once it is not. */
    private static final class RequestView implements InvocationHandler {

        private final AtmosphereResource resource;
        private final Map<String, Object> attributes = new ConcurrentHashMap<>();
        private final Map<String, List<String>> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        private final Map<String, String[]> parameters;
        private final Principal principal;
        private final String remoteUser;
        private final Map<String, Object> line = new ConcurrentHashMap<>();
        /** Set once the request was found no longer valid: it never is again. */
        private volatile boolean gone;

        RequestView(AtmosphereResource resource) {
            this.resource = resource;
            AtmosphereRequest live;
            try {
                live = resource.getRequest();
            } catch (IllegalStateException e) {
                live = null;
            }
            if (live == null) {
                gone = true;
                this.parameters = Map.of();
                this.principal = null;
                this.remoteUser = null;
                return;
            }
            for (var names = live.getAttributeNames(); names != null && names.hasMoreElements(); ) {
                var name = names.nextElement();
                var value = live.getAttribute(name);
                if (name != null && value != null) {
                    attributes.put(name, value);
                }
            }
            for (var names = live.getHeaderNames(); names != null && names.hasMoreElements(); ) {
                var name = names.nextElement();
                if (name != null) {
                    var values = live.getHeaders(name);
                    headers.put(name, values != null ? Collections.list(values) : List.of());
                }
            }
            var params = live.getParameterMap();
            this.parameters = params != null ? Map.copyOf(params) : Map.of();
            this.principal = safe(live::getUserPrincipal);
            this.remoteUser = safe(live::getRemoteUser);
            putLine("getRequestURI", safe(live::getRequestURI));
            putLine("getRequestURL", safe(live::getRequestURL));
            putLine("getQueryString", safe(live::getQueryString));
            putLine("getMethod", safe(live::getMethod));
            putLine("getPathInfo", safe(live::getPathInfo));
            putLine("getServletPath", safe(live::getServletPath));
            putLine("getContextPath", safe(live::getContextPath));
            putLine("getRemoteAddr", safe(live::getRemoteAddr));
            putLine("getAuthType", safe(live::getAuthType));
        }

        private void putLine(String method, Object value) {
            if (value != null) {
                line.put(method, value);
            }
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            var name = method.getName();
            if ("equals".equals(name) && method.getParameterCount() == 1) {
                return proxy == args[0];
            }
            if ("hashCode".equals(name) && method.getParameterCount() == 0) {
                return System.identityHashCode(proxy);
            }
            var live = live();
            if (live != null) {
                var result = call(live, method, args);
                // What the run sets, and what it read, outlives the request.
                if ("setAttribute".equals(name) && args != null && args.length == 2 && args[0] instanceof String key) {
                    if (args[1] != null) {
                        attributes.put(key, args[1]);
                    } else {
                        attributes.remove(key);
                    }
                } else if ("removeAttribute".equals(name) && args != null && args.length == 1) {
                    attributes.remove(String.valueOf(args[0]));
                } else if ("getAttribute".equals(name) && args != null && args.length == 1
                        && args[0] instanceof String key && result != null) {
                    attributes.put(key, result);
                }
                return result;
            }
            return detached(method, args);
        }

        /** The live request, or {@code null} once it is no longer valid. */
        private AtmosphereRequest live() {
            if (gone) {
                return null;
            }
            try {
                return resource.getRequest();
            } catch (IllegalStateException e) {
                gone = true;
                logger.debug("The request of {} is no longer valid; its run reads what it held", resource.uuid());
                return null;
            }
        }

        /** Answer {@code method} from what the request held. */
        private Object detached(Method method, Object[] args) throws Throwable {
            var name = method.getName();
            var arity = method.getParameterCount();
            switch (name) {
                case "getAttribute" -> {
                    return arity == 1 ? attributes.get(String.valueOf(args[0])) : fallback(method, args);
                }
                case "setAttribute" -> {
                    if (arity == 2 && args[0] instanceof String key) {
                        if (args[1] != null) {
                            attributes.put(key, args[1]);
                        } else {
                            attributes.remove(key);
                        }
                    }
                    return null;
                }
                case "removeAttribute" -> {
                    if (arity == 1) {
                        attributes.remove(String.valueOf(args[0]));
                    }
                    return null;
                }
                case "getAttributeNames" -> {
                    return Collections.enumeration(List.copyOf(attributes.keySet()));
                }
                case "getHeader" -> {
                    if (arity == 1) {
                        var values = headers.get(String.valueOf(args[0]));
                        return values != null && !values.isEmpty() ? values.get(0) : null;
                    }
                    return fallback(method, args);
                }
                case "getHeaders" -> {
                    if (arity == 1) {
                        var values = headers.get(String.valueOf(args[0]));
                        return Collections.enumeration(values != null ? values : List.of());
                    }
                    return fallback(method, args);
                }
                case "getHeaderNames" -> {
                    return Collections.enumeration(List.copyOf(headers.keySet()));
                }
                case "getParameter" -> {
                    if (arity == 1) {
                        var values = parameters.get(String.valueOf(args[0]));
                        return values != null && values.length > 0 ? values[0] : null;
                    }
                    return fallback(method, args);
                }
                case "getParameterValues" -> {
                    return arity == 1 ? parameters.get(String.valueOf(args[0])) : fallback(method, args);
                }
                case "getParameterMap" -> {
                    return parameters;
                }
                case "getParameterNames" -> {
                    Enumeration<String> names = Collections.enumeration(List.copyOf(parameters.keySet()));
                    return names;
                }
                case "getUserPrincipal" -> {
                    return principal;
                }
                case "getRemoteUser" -> {
                    return remoteUser;
                }
                default -> {
                    if (arity == 0 && line.containsKey(name)) {
                        return line.get(name);
                    }
                    return fallback(method, args);
                }
            }
        }

        /**
         * Any other method of a request no longer valid: as the request object
         * itself still answers it, or nothing.
         */
        private Object fallback(Method method, Object[] args) {
            try {
                if (resource instanceof AtmosphereResourceImpl impl) {
                    var stale = impl.getRequest(false);
                    if (stale != null) {
                        return call(stale, method, args);
                    }
                }
            } catch (Throwable e) {
                logger.trace("{} of the request of {}, no longer valid, failed", method.getName(), resource.uuid(), e);
            }
            return nothing(method.getReturnType());
        }
    }

    private static Object nothing(Class<?> type) {
        if (!type.isPrimitive() || type == void.class) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        if (type == long.class) {
            return 0L;
        }
        if (type == float.class) {
            return 0f;
        }
        if (type == double.class) {
            return 0d;
        }
        if (type == byte.class) {
            return (byte) 0;
        }
        if (type == short.class) {
            return (short) 0;
        }
        return 0;
    }

    private static Object call(Object target, Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }

    private interface Read<T> {
        T get();
    }

    private static <T> T safe(Read<T> read) {
        try {
            return read.get();
        } catch (RuntimeException e) {
            logger.trace("Unable to copy a request field", e);
            return null;
        }
    }
}
