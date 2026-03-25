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
package org.atmosphere.config.managed;

import org.atmosphere.config.service.Disconnect;
import org.atmosphere.config.service.Heartbeat;
import org.atmosphere.config.service.PathParam;
import org.atmosphere.config.service.Ready;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.inject.InjectableObjectFactory;
import org.atmosphere.util.Utils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Shared lifecycle and injection support for annotated endpoint classes.
 * Provides a unified framework that both {@link ManagedAtmosphereHandler}
 * ({@code @ManagedService}) and {@code @AiEndpoint} delegate to, so
 * annotation scanning, lifecycle invocation, and field injection are
 * never duplicated.
 *
 * <h3>Usage</h3>
 * <pre>{@code
 * // At registration time:
 * var lifecycle = AnnotatedLifecycle.scan(MyEndpoint.class);
 * AnnotatedLifecycle.injectFields(framework, instance);
 *
 * // On connect:
 * lifecycle.onReady(instance, resource);
 * lifecycle.injectPathParams(instance, resource, pathTemplate, config);
 *
 * // On disconnect:
 * lifecycle.onDisconnect(instance, event);
 * }</pre>
 *
 * <h3>Capabilities</h3>
 * <ul>
 *   <li>Scans for {@link Ready @Ready} and {@link Disconnect @Disconnect}
 *       lifecycle methods</li>
 *   <li>Detects {@link PathParam @PathParam} fields for per-request injection</li>
 *   <li>Invokes lifecycle methods via {@link Utils#invoke}</li>
 *   <li>Triggers per-request {@code @PathParam} field injection via
 *       {@link InjectableObjectFactory#requestScoped}</li>
 *   <li>Triggers one-time {@code @Inject} field injection via
 *       {@link InjectableObjectFactory#injectInjectable}</li>
 * </ul>
 *
 * @author Jeanfrancois Arcand
 */
public final class AnnotatedLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(AnnotatedLifecycle.class);

    private final Method readyMethod;
    private final Method disconnectMethod;
    private final Method heartbeatMethod;
    private final boolean hasPathParams;

    private AnnotatedLifecycle(Method readyMethod, Method disconnectMethod,
                                Method heartbeatMethod, boolean hasPathParams) {
        this.readyMethod = readyMethod;
        this.disconnectMethod = disconnectMethod;
        this.heartbeatMethod = heartbeatMethod;
        this.hasPathParams = hasPathParams;
        if (this.readyMethod != null) {
            this.readyMethod.setAccessible(true);
        }
        if (this.disconnectMethod != null) {
            this.disconnectMethod.setAccessible(true);
        }
        if (this.heartbeatMethod != null) {
            this.heartbeatMethod.setAccessible(true);
        }
    }

    /**
     * Scans the given class for {@link Ready @Ready}, {@link Disconnect @Disconnect}
     * methods and {@link PathParam @PathParam} fields.
     *
     * @param clazz the annotated endpoint class
     * @return an immutable lifecycle descriptor
     */
    public static AnnotatedLifecycle scan(Class<?> clazz) {
        return new AnnotatedLifecycle(
                findMethod(clazz, Ready.class),
                findMethod(clazz, Disconnect.class),
                findMethod(clazz, Heartbeat.class),
                hasPathParamFields(clazz)
        );
    }

    // ---- Lifecycle invocation ----

    /**
     * Invokes the {@code @Ready}-annotated method if present.
     * Supports signatures: {@code ()}, {@code (AtmosphereResource)}.
     */
    public void onReady(Object target, AtmosphereResource resource) {
        Utils.invoke(target, readyMethod, resource);
    }

    /**
     * Invokes the {@code @Disconnect}-annotated method if present.
     * Supports signatures: {@code ()}, {@code (AtmosphereResourceEvent)}.
     */
    public void onDisconnect(Object target, AtmosphereResourceEvent event) {
        Utils.invoke(target, disconnectMethod, event);
    }

    /**
     * Invokes the {@code @Heartbeat}-annotated method if present.
     * Supports signatures: {@code ()}, {@code (AtmosphereResourceEvent)}.
     */
    public void onHeartbeat(Object target, AtmosphereResourceEvent event) {
        Utils.invoke(target, heartbeatMethod, event);
    }

    // ---- Accessors ----

    public Method readyMethod() {
        return readyMethod;
    }

    public Method disconnectMethod() {
        return disconnectMethod;
    }

    public Method heartbeatMethod() {
        return heartbeatMethod;
    }

    public boolean hasPathParams() {
        return hasPathParams;
    }

    // ---- Injection ----

    /**
     * Injects {@code @PathParam}-annotated fields on the target instance for
     * the current request. Sets the request attribute that
     * {@link org.atmosphere.inject.PathParamIntrospector} expects, then
     * delegates to {@link Utils#inject(Object, Class, AtmosphereResource)}.
     *
     * @param target       the endpoint instance
     * @param resource     the current atmosphere resource
     * @param pathTemplate the path template (e.g. {@code "/chat/{room}"})
     * @param config       the atmosphere configuration
     */
    public void injectPathParams(Object target, AtmosphereResource resource,
                                 String pathTemplate, AtmosphereConfig config) {
        if (!hasPathParams || config == null) {
            return;
        }
        try {
            var requestUri = resource.getRequest().getRequestURI();
            if (requestUri != null && pathTemplate != null) {
                resource.getRequest().setAttribute(
                        PathParam.class.getName(),
                        new String[]{requestUri, pathTemplate});
            }
            Utils.inject(target, target.getClass(), resource);
        } catch (IllegalAccessException e) {
            logger.error("Failed to inject @PathParam fields on {}", target.getClass().getName(), e);
        }
    }

    /**
     * Injects {@code @Inject}-annotated fields on the target instance at
     * registration time using Atmosphere's {@link InjectableObjectFactory}.
     *
     * @param framework the atmosphere framework
     * @param instance  the endpoint instance to inject into
     */
    public static void injectFields(AtmosphereFramework framework, Object instance) {
        try {
            var factory = framework.objectFactory();
            if (factory instanceof InjectableObjectFactory iof) {
                iof.injectInjectable(instance, instance.getClass(), framework);
            }
        } catch (Exception e) {
            logger.warn("Field injection failed for {}: {}", instance.getClass().getName(), e.getMessage());
        }
    }

    // ---- Scanning helpers (reusable by ManagedAtmosphereHandler) ----

    /**
     * Finds the first method annotated with the given annotation.
     * Checks all public methods (including inherited) via {@link Class#getMethods()}.
     *
     * @param clazz      the class to scan
     * @param annotation the annotation to look for
     * @return the annotated method, or {@code null} if not found
     */
    public static Method findMethod(Class<?> clazz, Class<? extends Annotation> annotation) {
        for (var m : clazz.getMethods()) {
            if (m.isAnnotationPresent(annotation)) {
                return m;
            }
        }
        return null;
    }

    /**
     * Checks whether the class has any {@code @PathParam}-annotated fields.
     *
     * @param clazz the class to scan
     * @return true if at least one field is annotated with {@code @PathParam}
     */
    public static boolean hasPathParamFields(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(PathParam.class)) {
                return true;
            }
        }
        return false;
    }
}
