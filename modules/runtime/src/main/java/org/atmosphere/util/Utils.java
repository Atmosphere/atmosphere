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
package org.atmosphere.util;

import org.atmosphere.runtime.AtmosphereConfig;
import org.atmosphere.runtime.AtmosphereFramework;
import org.atmosphere.runtime.AtmosphereHandler;
import org.atmosphere.runtime.AtmosphereObjectFactory;
import org.atmosphere.runtime.AtmosphereRequest;
import org.atmosphere.runtime.AtmosphereResource;
import org.atmosphere.runtime.AtmosphereResourceImpl;
import org.atmosphere.runtime.FrameworkConfig;
import org.atmosphere.runtime.HeaderConfig;
import org.atmosphere.handler.AnnotatedProxy;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.inject.InjectableObjectFactory;
import org.atmosphere.websocket.WebSocketProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;

import static org.atmosphere.runtime.ApplicationConfig.SUSPENDED_ATMOSPHERE_RESOURCE_UUID;
import static org.atmosphere.runtime.FrameworkConfig.NEED_RUNTIME_INJECTION;
import static org.atmosphere.runtime.HeaderConfig.WEBSOCKET_UPGRADE;
import static org.atmosphere.runtime.HeaderConfig.WEBSOCKET_VERSION;

/**
 * Utils class.
 *
 * @author Jeanfrancois Arcand
 */
public final class Utils {

    /**
     * The logger.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(Utils.class);

    public final static boolean webSocketEnabled(HttpServletRequest request) {

        if (closeMessage(request) || !webSocketQueryStringPresentOrNull(request)) return false;

        boolean allowWebSocketWithoutHeaders = request.getHeader(HeaderConfig.X_ATMO_WEBSOCKET_PROXY) != null ? true : false;
        if (allowWebSocketWithoutHeaders) return true;
        boolean webSocketEnabled = rawWebSocket(request);

        return webSocketEnabled;
    }

    public final static boolean rawWebSocket(HttpServletRequest request) {
        Enumeration<String> connection = request.getHeaders("Connection");
        if (connection == null || !connection.hasMoreElements()) {
            connection = request.getHeaders("connection");
        }

        if (connection != null && connection.hasMoreElements()) {
            String[] e = connection.nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase(WEBSOCKET_UPGRADE)) {
                    return true;
                }
            }
        }
        return false;
    }

    public final static boolean firefoxWebSocketEnabled(HttpServletRequest request) {
        return webSocketEnabled(request)
                && request.getHeader(HeaderConfig.X_ATMO_PROTOCOL) != null
                && request.getHeader(HeaderConfig.X_ATMO_PROTOCOL).equals("true")
                && request.getHeader("User-Agent") != null
                && request.getHeader("User-Agent").toLowerCase().indexOf("firefox") != -1;
    }

    public final static boolean twoConnectionsTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case JSONP:
            case LONG_POLLING:
            case STREAMING:
            case SSE:
            case POLLING:
            case HTMLFILE:
                return true;
            default:
                return false;
        }
    }

    public final static boolean webSocketQueryStringPresentOrNull(HttpServletRequest request) {
        String transport = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        if (transport == null) {
            // ignore so other framework client can work
            return true;
        } else {
            return transport.equalsIgnoreCase(HeaderConfig.WEBSOCKET_TRANSPORT);
        }
    }

    public final static boolean resumableTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case JSONP:
            case LONG_POLLING:
                return true;
            default:
                return false;
        }
    }

    public final static boolean pollableTransport(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case POLLING:
            case CLOSE:
            case AJAX:
                return true;
            default:
                return false;
        }
    }

    public final static boolean pushMessage(AtmosphereResource.TRANSPORT t) {
        switch (t) {
            case POLLING:
            case UNDEFINED:
            case AJAX:
                return true;
            default:
                return false;
        }
    }

    public final static boolean atmosphereProtocol(AtmosphereRequest r) {
        String p = r.getHeader(HeaderConfig.X_ATMO_PROTOCOL);
        return (p != null && Boolean.valueOf(p));
    }

    public final static boolean webSocketMessage(AtmosphereResource r) {
        AtmosphereRequest request = AtmosphereResourceImpl.class.cast(r).getRequest(false);
        return request.getAttribute(FrameworkConfig.WEBSOCKET_MESSAGE) != null;
    }

    public static boolean properProtocol(HttpServletRequest request) {
        Enumeration<String> connection = request.getHeaders("Connection");
        if (connection == null || !connection.hasMoreElements()) {
            connection = request.getHeaders("connection");
        }

        boolean isOK = false;
        boolean isWebSocket = (request.getHeader(WEBSOCKET_VERSION) != null || request.getHeader("Sec-WebSocket-Draft") != null);
        if (connection != null && connection.hasMoreElements()) {
            String[] e = connection.nextElement().toString().split(",");
            for (String upgrade : e) {
                if (upgrade.trim().equalsIgnoreCase("upgrade")) {
                    isOK = true;
                }
            }
        }
        return isWebSocket ? isOK : true;
    }

    public static final AtmosphereResource websocketResource(AtmosphereResource r) {
        String parentUUID = (String) AtmosphereResourceImpl.class.cast(r).getRequest(false).getAttribute(SUSPENDED_ATMOSPHERE_RESOURCE_UUID);
        if (parentUUID != null) {
            AtmosphereResource resource = r.getAtmosphereConfig().resourcesFactory().find(parentUUID);
            if (resource != null) {
                r = resource;
            }
        }
        return r;
    }

    public static final boolean closeMessage(HttpServletRequest request) {
        String s = request.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT);
        return s != null && s.equalsIgnoreCase(HeaderConfig.DISCONNECT_TRANSPORT_MESSAGE);
    }

    /**
     * <p>
     * Manages the invocation of the given method on the specified 'proxied' instance. Logs any invocation failure.
     * </p>
     *
     * @param proxiedInstance the instance
     * @param m               the method to invoke that belongs to the instance
     * @param o               the optional parameter
     * @return the result of the invocation
     */
    public static Object invoke(final Object proxiedInstance, Method m, Object o) {
        if (m != null) {
            try {
                return m.invoke(proxiedInstance, (o == null || m.getParameterTypes().length == 0) ? new Object[]{} : new Object[]{o});
            } catch (IllegalAccessException e) {
                LOGGER.debug("", e);
            } catch (InvocationTargetException e) {
                LOGGER.debug("", e);
            }
        }
        LOGGER.trace("No Method Mapped for {}", o);
        return null;
    }

    public static final void inject(AtmosphereResource r) throws IllegalAccessException {
        AtmosphereConfig config = r.getAtmosphereConfig();

        // No Injectable supports Injection
        if (config.properties().get(NEED_RUNTIME_INJECTION) == null) {
            return;
        }

        AtmosphereObjectFactory injectableFactory = config.framework().objectFactory();
        if (!InjectableObjectFactory.class.isAssignableFrom(injectableFactory.getClass())) {
            return;
        }

        Object injectIn = injectWith(r);
        if (injectIn != null) {
            inject(injectIn, injectIn.getClass(), r);
        }
    }

    public static final void inject(Object object, Class clazz, AtmosphereResource r) throws IllegalAccessException {
        InjectableObjectFactory.class.cast(r.getAtmosphereConfig().framework().objectFactory()).requestScoped(object, clazz, r);
    }

    public static final void inject(Object object, Class clazz, AtmosphereConfig config) throws IllegalAccessException {
        InjectableObjectFactory.class.cast(config.framework().objectFactory()).requestScoped(object, clazz);
    }

    private static final Object injectWith(AtmosphereResource r) {

        // Check for null when disconnect happens. This happens when websocket are closed by both the client and the server
        // concurrently.
        if (r == null) {
            return null;
        }

        AtmosphereHandler h = r.getAtmosphereHandler();
        if (r.getAtmosphereHandler() == null) {
            return null;
        }
        if (AtmosphereFramework.REFLECTOR_ATMOSPHEREHANDLER.getClass().isAssignableFrom(h.getClass())) {
            if (AtmosphereResourceImpl.class.cast(r).webSocket() == null
                    || AtmosphereResourceImpl.class.cast(r).webSocket().webSocketHandler() == null) {
                return null;
            }
            return WebSocketProcessor.WebSocketHandlerProxy.class.cast(AtmosphereResourceImpl.class.cast(r).webSocket().webSocketHandler()).proxied();
        } else {
            return injectWith(h);
        }
    }

    private static Object injectWith(AtmosphereHandler h) {
        if (AnnotatedProxy.class.isAssignableFrom(h.getClass())) {
            return AnnotatedProxy.class.cast(h).target();
        } else if (ReflectorServletProcessor.class.isAssignableFrom(h.getClass())) {
            return ReflectorServletProcessor.class.cast(h).getServlet();
        } else {
            return h;
        }
    }

    public final static Set<Field> getInheritedPrivateFields(Class<?> type) {
        Set<Field> result = new HashSet<Field>();

        Class<?> i = type;
        while (i != null && i != Object.class) {
            for (Field field : i.getDeclaredFields()) {
                if (!field.isSynthetic()) {
                    result.add(field);
                }
            }
            i = i.getSuperclass();
        }

        return result;
    }

    public final static Set<Method> getInheritedPrivateMethod(Class<?> type) {
        Set<Method> result = new HashSet<>();

        Class<?> i = type;
        while (i != null && i != Object.class) {
            for (Method m : i.getDeclaredMethods()) {
                if (!m.isSynthetic()) {
                    result.add(m);
                }
            }
            i = i.getSuperclass();
        }

        return result;
    }

    public final static boolean requestScopedInjection(AtmosphereConfig config, AtmosphereHandler h) {
        AtmosphereObjectFactory injectableFactory = config.framework().objectFactory();
        if (!InjectableObjectFactory.class.isAssignableFrom(injectableFactory.getClass())) {
            return false;
        }

        try {
            Object obj = injectWith(h);
            return obj == null ? false : InjectableObjectFactory.class.cast(config.framework().objectFactory()).needRequestScoped(obj.getClass());
        } catch (Exception e) {
            LOGGER.error("", e);
            return false;
        }
    }

    /**
     * Inject custom object. This method is mostly for external framework.
     *
     * @param config
     * @param o
     * @return
     */
    public static final boolean requestScopedInjection(AtmosphereConfig config, Object o) {
        AtmosphereObjectFactory injectableFactory = config.framework().objectFactory();
        if (!InjectableObjectFactory.class.isAssignableFrom(injectableFactory.getClass())) {
            return false;
        }

        try {
            return InjectableObjectFactory.class.cast(config.framework().objectFactory()).needRequestScoped(o.getClass());
        } catch (Exception var4) {
            LOGGER.error("", var4);
            return false;
        }
    }

    public static String pathInfo(AtmosphereRequest request) {
        String pathInfo = null;
        String path = null;
        try {
            pathInfo = request.getPathInfo();
        } catch (IllegalStateException ex) {
            // http://java.net/jira/browse/GRIZZLY-1301
        }

        if (pathInfo != null) {
            path = request.getServletPath() + pathInfo;
        } else {
            path = request.getServletPath();
        }

        if (path == null || path.isEmpty()) {
            path = "/";
        }
        return path;
    }
}
