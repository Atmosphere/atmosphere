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
package org.atmosphere.cpr;

import org.atmosphere.interceptor.AndroidAtmosphereInterceptor;
import org.atmosphere.interceptor.CacheHeadersInterceptor;
import org.atmosphere.interceptor.CorsInterceptor;
import org.atmosphere.interceptor.HeartbeatInterceptor;
import org.atmosphere.interceptor.IdleResourceInterceptor;
import org.atmosphere.interceptor.InvokationOrder;
import org.atmosphere.interceptor.JSONPAtmosphereInterceptor;
import org.atmosphere.interceptor.JavaScriptProtocol;
import org.atmosphere.interceptor.OnDisconnectInterceptor;
import org.atmosphere.interceptor.PaddingAtmosphereInterceptor;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.atmosphere.interceptor.WebSocketMessageSuspendInterceptor;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import jakarta.servlet.ServletConfig;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Manages {@link AtmosphereInterceptor} registration, configuration, and lifecycle.
 */
public class InterceptorRegistry {

    private static final Logger logger = LoggerFactory.getLogger(InterceptorRegistry.class);

    public static final List<Class<? extends AtmosphereInterceptor>> DEFAULT_ATMOSPHERE_INTERCEPTORS = List.of(
            CorsInterceptor.class,
            CacheHeadersInterceptor.class,
            PaddingAtmosphereInterceptor.class,
            AndroidAtmosphereInterceptor.class,
            HeartbeatInterceptor.class,
            SSEAtmosphereInterceptor.class,
            JSONPAtmosphereInterceptor.class,
            JavaScriptProtocol.class,
            WebSocketMessageSuspendInterceptor.class,
            OnDisconnectInterceptor.class,
            IdleResourceInterceptor.class
    );

    private final AtmosphereConfig config;
    private final LinkedList<AtmosphereInterceptor> interceptors = new LinkedList<>();
    private final List<String> excludedInterceptors = new ArrayList<>();
    private Supplier<Map<String, AtmosphereHandlerWrapper>> handlersSupplier;

    public InterceptorRegistry(AtmosphereConfig config) {
        this.config = config;
    }

    /**
     * Set the supplier for the handlers map (resolves circular dependency with HandlerRegistry).
     */
    void setHandlersSupplier(Supplier<Map<String, AtmosphereHandlerWrapper>> handlersSupplier) {
        this.handlersSupplier = handlersSupplier;
    }

    /**
     * Add an {@link AtmosphereInterceptor}. The adding order will be used for invocation.
     */
    public boolean addInterceptor(AtmosphereInterceptor c, boolean initialized) {
        if (!checkDuplicate(c)) {
            interceptors.add(c);
            if (initialized) {
                addInterceptorToAllWrappers(c);
            }
            return true;
        }
        return false;
    }

    /**
     * Return the list of {@link AtmosphereInterceptor}s.
     */
    public LinkedList<AtmosphereInterceptor> interceptors() {
        return interceptors;
    }

    /**
     * Find an {@link AtmosphereInterceptor} of the given type.
     *
     * @deprecated Use {@link #findInterceptor(Class)} which returns {@link Optional} instead of null.
     */
    @Deprecated(since = "4.0.0", forRemoval = false)
    @SuppressWarnings("unchecked")
    public <T extends AtmosphereInterceptor> T interceptor(Class<T> c) {
        for (AtmosphereInterceptor i : interceptors) {
            if (c.isInstance(i)) {
                return c.cast(i);
            }
        }
        return null;
    }

    /**
     * Find an {@link AtmosphereInterceptor} of the given type.
     */
    public <T extends AtmosphereInterceptor> Optional<T> findInterceptor(Class<T> c) {
        for (AtmosphereInterceptor i : interceptors) {
            if (c.isInstance(i)) {
                return Optional.of(c.cast(i));
            }
        }
        return Optional.empty();
    }

    /**
     * Exclude an {@link AtmosphereInterceptor} from being added at startup.
     */
    public void excludeInterceptor(String interceptor) {
        excludedInterceptors.add(interceptor);
    }

    /**
     * Return the list of excluded interceptor class names.
     */
    public List<String> excludedInterceptors() {
        return excludedInterceptors;
    }

    /**
     * Return the default interceptor classes.
     */
    @SuppressWarnings("unchecked")
    public Class<? extends AtmosphereInterceptor>[] defaultInterceptors() {
        return DEFAULT_ATMOSPHERE_INTERCEPTORS.toArray(new Class[DEFAULT_ATMOSPHERE_INTERCEPTORS.size()]);
    }

    /**
     * Configure interceptors from servlet init parameters and default list.
     */
    @SuppressWarnings("unchecked")
    public void configure(ServletConfig sc) {
        String s = sc.getInitParameter(ApplicationConfig.ATMOSPHERE_INTERCEPTORS);
        if (s != null) {
            String[] list = s.split(",");
            for (String a : list) {
                try {
                    AtmosphereInterceptor ai = config.framework().newClassInstance(AtmosphereInterceptor.class,
                            (Class<AtmosphereInterceptor>) IOUtils.loadClass(config.framework().getClass(), a.trim()));
                    addInterceptor(ai, config.framework().isInit);
                } catch (Exception e) {
                    logger.warn("", e);
                }
            }
        }

        s = sc.getInitParameter(ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        if (!Boolean.parseBoolean(s)) {
            logger.info("Installing Default AtmosphereInterceptors");

            for (Class<? extends AtmosphereInterceptor> a : DEFAULT_ATMOSPHERE_INTERCEPTORS) {
                if (!excludedInterceptors.contains(a.getName())) {
                    AtmosphereInterceptor ai = newInterceptor(a);
                    if (ai != null) {
                        interceptors.add(ai);
                    }
                } else {
                    logger.info("Dropping Interceptor {}", a.getName());
                }
            }
            logger.info("Set {} to disable them.", ApplicationConfig.DISABLE_ATMOSPHEREINTERCEPTOR);
        }
        addDefaultOrAppInterceptors();
    }

    /**
     * Create a new interceptor instance.
     */
    @SuppressWarnings("unchecked")
    public AtmosphereInterceptor newInterceptor(Class<? extends AtmosphereInterceptor> a) {
        AtmosphereInterceptor ai = null;
        try {
            ai = config.framework().newClassInstance(AtmosphereInterceptor.class,
                    (Class<AtmosphereInterceptor>) IOUtils.loadClass(config.framework().getClass(), a.getName()));
            logger.info("\t{} : {}", a.getName(), ai);
        } catch (Exception ex) {
            logger.warn("", ex);
        }
        return ai;
    }

    /**
     * Add all interceptors to all handler wrappers.
     */
    public void addDefaultOrAppInterceptors() {
        for (AtmosphereInterceptor c : interceptors) {
            addInterceptorToAllWrappers(c);
        }
    }

    /**
     * Add an interceptor to all registered handler wrappers.
     */
    public void addInterceptorToAllWrappers(AtmosphereInterceptor c) {
        c.configure(config);
        InvokationOrder.PRIORITY p = c instanceof InvokationOrder io ? io.priority() : InvokationOrder.AFTER_DEFAULT;

        logger.info("Installed AtmosphereInterceptor {} with priority {} ", c, p.name());
        if (handlersSupplier != null) {
            for (AtmosphereHandlerWrapper wrapper : handlersSupplier.get().values()) {
                addInterceptorToWrapper(wrapper, c);
            }
        }
    }

    /**
     * Add an interceptor to a specific wrapper.
     */
    public void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, AtmosphereInterceptor c) {
        if (!checkDuplicate(wrapper.interceptors, c.getClass())) {
            wrapper.interceptors.add(c);
            wrapper.interceptors.sort(new InterceptorComparator());
        }
    }

    /**
     * Add framework interceptors and handler-specific interceptors to a wrapper.
     */
    public void addInterceptorToWrapper(AtmosphereHandlerWrapper wrapper, List<AtmosphereInterceptor> handlerInterceptors) {
        for (AtmosphereInterceptor c : this.interceptors) {
            addInterceptorToWrapper(wrapper, c);
        }

        for (AtmosphereInterceptor c : handlerInterceptors) {
            addInterceptorToWrapper(wrapper, c);
            c.configure(config);
        }
    }

    /**
     * Destroy all interceptors in all handler wrappers.
     */
    public void destroyInterceptors(Map<String, AtmosphereHandlerWrapper> handlers) {
        for (AtmosphereHandlerWrapper w : handlers.values()) {
            for (AtmosphereInterceptor i : w.interceptors) {
                try {
                    i.destroy();
                } catch (Throwable ex) {
                    logger.warn("", ex);
                }
            }
        }
    }

    /**
     * Clear all interceptors and excluded interceptors.
     */
    public void clear() {
        interceptors.clear();
        excludedInterceptors.clear();
    }

    private boolean checkDuplicate(final AtmosphereInterceptor c) {
        return checkDuplicate(interceptors, c.getClass());
    }

    private boolean checkDuplicate(final List<AtmosphereInterceptor> interceptorList, Class<? extends AtmosphereInterceptor> c) {
        for (final AtmosphereInterceptor i : interceptorList) {
            if (i.getClass().equals(c)) {
                return true;
            }
        }
        return false;
    }

    static class InterceptorComparator implements Comparator<AtmosphereInterceptor> {
        @Override
        public int compare(AtmosphereInterceptor i1, AtmosphereInterceptor i2) {
            InvokationOrder.PRIORITY p1, p2;

            if (i1 instanceof InvokationOrder io1) {
                p1 = io1.priority();
            } else {
                p1 = InvokationOrder.PRIORITY.AFTER_DEFAULT;
            }

            if (i2 instanceof InvokationOrder io2) {
                p2 = io2.priority();
            } else {
                p2 = InvokationOrder.PRIORITY.AFTER_DEFAULT;
            }

            int orderResult = 0;

            switch (p1) {
                case AFTER_DEFAULT -> {
                    switch (p2) {
                        case BEFORE_DEFAULT, FIRST_BEFORE_DEFAULT -> orderResult = 1;
                        default -> {}
                    }
                }
                case BEFORE_DEFAULT -> {
                    switch (p2) {
                        case AFTER_DEFAULT -> orderResult = -1;
                        case FIRST_BEFORE_DEFAULT -> orderResult = 1;
                        default -> {}
                    }
                }
                case FIRST_BEFORE_DEFAULT -> {
                    switch (p2) {
                        case AFTER_DEFAULT, BEFORE_DEFAULT -> orderResult = -1;
                        default -> {}
                    }
                }
            }

            return orderResult;
        }
    }
}
