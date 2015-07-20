/*
 * Copyright 2015 Async-IO.org
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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereObjectFactory;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.inject.annotation.ApplicationScoped;
import org.atmosphere.inject.annotation.RequestScoped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.ServiceLoader;
import java.util.Set;

/**
 * Support injection of Atmosphere's Internal object using
 * {@link org.atmosphere.cpr.AtmosphereConfig},{@link AtmosphereFramework},{@link org.atmosphere.cpr.BroadcasterFactory,
 * {@link org.atmosphere.cpr.AtmosphereResourceFactory } ,{@link org.atmosphere.cpr.DefaultMetaBroadcaster } and
 * {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory } and classes implementing the {@link Injectable} defined inside
 * META_INF/services/org.atmosphere.inject.Inject
 *
 * @author Jeanfrancois Arcand
 */
public class InjectableObjectFactory implements AtmosphereObjectFactory<Injectable<?>> {

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);
    private final static ServiceLoader<Injectable> injectableServiceLoader = ServiceLoader.load(Injectable.class);
    private final LinkedList<Injectable<?>> injectables = new LinkedList<Injectable<?>>();
    private final LinkedList<InjectIntrospector<?>> introspectors = new LinkedList<InjectIntrospector<?>>();
    private final LinkedList<InjectIntrospector<?>> requestScopedIntrospectors = new LinkedList<InjectIntrospector<?>>();

    private AtmosphereConfig config;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        for (Injectable<?> i : injectableServiceLoader) {
            try {
                logger.debug("Adding class {} as injectable", i.getClass());
                if (InjectIntrospector.class.isAssignableFrom(i.getClass())) {
                    InjectIntrospector<?> ii = InjectIntrospector.class.cast(i);

                    introspectors.addFirst(ii);
                    if (i.getClass().isAnnotationPresent(RequestScoped.class)) {
                        config.properties().put(FrameworkConfig.NEED_RUNTIME_INJECTION, true);
                        requestScopedIntrospectors.addFirst(ii);
                    }
                }

                if (i.getClass().isAnnotationPresent(ApplicationScoped.class) ||
                        // For backward compatibility with 2.2+
                        (!i.getClass().isAnnotationPresent(RequestScoped.class) && !i.getClass().isAnnotationPresent(RequestScoped.class))) {
                    injectables.addFirst(i);
                }
            } catch (Exception e) {
                logger.error("", e);
            }
        }

        // Inject into injectable
        for (Injectable<?> i : injectables) {
            try {
                injectInjectable(i, i.getClass(), config.framework());
            } catch (IllegalAccessException e) {
                logger.error("", e);
            }
        }
    }

    @Override
    public <T, U extends T> U newClassInstance(Class<T> classType,
                                               Class<U> defaultType) throws InstantiationException, IllegalAccessException {

        U instance = defaultType.newInstance();

        injectInjectable(instance, defaultType, config.framework());
        applyMethods(instance, defaultType);

        return instance;
    }

    /**
     * Apply {@link Injectable} and {@link InjectIntrospector} to a class already constructed.
     *
     * @param instance the instance to inject to.
     * @return
     * @throws InstantiationException
     * @throws IllegalAccessException
     */
    /* @Override */
    public <T> T inject(T instance) throws InstantiationException, IllegalAccessException {

        injectInjectable(instance, instance.getClass(), config.framework());
        applyMethods(instance, (Class<T>) instance.getClass());

        return instance;
    }

    /**
     * Execute {@link InjectIntrospector#introspectMethod}
     *
     * @param instance    the requested object.
     * @param defaultType the type of the requested object
     * @param <U>
     * @throws IllegalAccessException
     */
    public <U> void applyMethods(U instance, Class<U> defaultType) throws IllegalAccessException {
        Set<Method> methods = new HashSet<Method>();
        methods.addAll(Arrays.asList(defaultType.getDeclaredMethods()));
        methods.addAll(Arrays.asList(defaultType.getMethods()));
        injectMethods(methods, instance);
    }

    private <U> void injectMethods(Set<Method> methods, U instance) throws IllegalAccessException {
        for (Method m : methods) {
            for (Injectable c : introspectors) {
                InjectIntrospector.class.cast(c).introspectMethod(m, instance);
            }
        }
    }

    /**
     * @param instance    the requested object.
     * @param defaultType the type of the requested object
     * @param framework   the {@link org.atmosphere.cpr.AtmosphereFramework}
     * @param <U>
     * @throws IllegalAccessException
     */
    public <U> void injectInjectable(U instance, Class<? extends U> defaultType, AtmosphereFramework framework) throws IllegalAccessException {
        Set<Field> fields = new HashSet<Field>();
        fields.addAll(getInheritedPrivateFields(defaultType));

        injectFields(fields, instance, framework, injectables);
    }

    private Set<Field> getInheritedPrivateFields(Class<?> type) {
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

    public <U> void injectFields(Set<Field> fields, U instance, AtmosphereFramework framework, LinkedList<Injectable<?>> injectable) throws IllegalAccessException {
        for (Field field : fields) {
            if (field.isAnnotationPresent(Inject.class)) {
                for (Injectable c : injectable) {
                    if (c.supportedType(field.getType())) {

                        if (InjectIntrospector.class.isAssignableFrom(c.getClass())) {
                            InjectIntrospector.class.cast(c).introspectField(field);
                        }

                        try {
                            field.setAccessible(true);
                            Object o = c.injectable(framework.getAtmosphereConfig());

                            if (o == null) continue;

                            if (field.getType().equals(Boolean.TYPE)) {
                                field.setBoolean(instance, Boolean.class.cast(o).booleanValue());
                            } else {
                                field.set(instance, o);
                            }
                        } catch (Exception ex) {
                            logger.warn("Injectable {} failed to inject", c, ex);
                        } finally {
                            field.setAccessible(false);
                        }
                        break;
                    }
                }
            }
        }
    }

    public AtmosphereObjectFactory allowInjectionOf(Injectable<?> injectable) {
        injectables.add(injectable);
        return this;
    }

    @Override
    public String toString() {
        return InjectableObjectFactory.class.getName();
    }

    /**
     * Use this method to retrieve available {@link Injectable}. This method is for application that inject their
     * own {@link Injectable} and needs already constructed classes.
     *
     * @param u   the class
     * @param <U> the type
     * @return the class if exists, null if not
     */
    public <U> U getInjectable(Class<U> u) {
        for (Injectable c : injectables) {
            if (c.supportedType(u)) {
                return (U) c.injectable(config);
            }
        }
        return null;
    }

    public void requestScoped(Object instance, Class defaultType, AtmosphereResource r) throws IllegalAccessException {
        Set<Field> fields = new HashSet<>();
        fields.addAll(getInheritedPrivateFields(defaultType));

        for (Field field : fields) {
            for (InjectIntrospector c : requestScopedIntrospectors) {

                for (Class annotation : c.getClass().getAnnotation(RequestScoped.class).value()) {
                    if (field.isAnnotationPresent(annotation)) {

                        c.introspectField(field);

                        if (c.supportedType(field.getType())) {
                            try {
                                field.setAccessible(true);
                                field.set(instance, c.injectable(r));
                            } finally {
                                field.setAccessible(false);
                            }
                            break;
                        }
                    }
                }
            }
        }
    }
}
