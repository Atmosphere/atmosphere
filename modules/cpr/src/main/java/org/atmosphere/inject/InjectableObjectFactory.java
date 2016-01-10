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

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereObjectFactory;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.inject.annotation.ApplicationScoped;
import org.atmosphere.inject.annotation.RequestScoped;
import org.atmosphere.util.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.ServiceLoader;
import java.util.Set;

import static org.atmosphere.util.Utils.getInheritedPrivateFields;
import static org.atmosphere.util.Utils.getInheritedPrivateMethod;

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
    private final ServiceLoader<Injectable> injectableServiceLoader;
    private final LinkedList<Injectable<?>> injectables = new LinkedList<Injectable<?>>();
    private final LinkedList<InjectIntrospector<?>> introspectors = new LinkedList<InjectIntrospector<?>>();
    private final LinkedList<InjectIntrospector<?>> requestScopedIntrospectors = new LinkedList<InjectIntrospector<?>>();
    private final LinkedHashSet<Object> pushBackInjection = new LinkedHashSet<>();
    private final List<InjectionListener> listeners = new LinkedList();
    private int maxTry;

    private AtmosphereConfig config;

    public InjectableObjectFactory() {
        injectableServiceLoader = ServiceLoader.load(Injectable.class);
    }

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        this.maxTry = config.getInitParameter(ApplicationConfig.INJECTION_TRY, 5);

        String s = config.getInitParameter(ApplicationConfig.INJECTION_LISTENERS, "");
        if (s != null && !s.isEmpty()) {
            String[] listeners = s.split(",");
            for (String l : listeners) {
                try {
                    listener((InjectionListener) IOUtils.loadClass(getClass(), l).newInstance());
                } catch (Exception ex) {
                    logger.warn("", ex);
                }
            }
        }

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
                logger.error("", e.getCause());
            }
        }

        // Inject into injectable
        for (Injectable<?> i : injectables) {
            try {
                inject(i);
            } catch (Exception e) {
                logger.error("", e.getCause());
            }
        }

        // Injectable that needs Injection
        if (!pushBackInjection.isEmpty()) {
            retryInjection(config.framework());
        }

        config.startupHook(new AtmosphereConfig.StartupHook() {
            @Override
            public void started(AtmosphereFramework framework) {
                retryInjection(framework);
            }
        });
    }

    protected void retryInjection(AtmosphereFramework framework){
        int maxTryPerCycle = maxTry;
        // Give another chance to injection in case we failed at first place. We may still fail if there is a strong
        // dependency between Injectable, e.g one depend on other, or if the Injectable is not defined at the right place
        // in META-INF/services/org/atmosphere/inject.Injectable
        Set<Field> fields = new HashSet<Field>();
        Object instance = null;
        final LinkedHashSet<Object> postponedMethodExecution = new LinkedHashSet<>(pushBackInjection);
        while (!pushBackInjection.isEmpty() & maxTryPerCycle-- > 0) {
            Iterator<Object> t = new LinkedList(pushBackInjection).iterator();
            pushBackInjection.clear();
            while (t.hasNext()) {
                instance = t.next();
                fields.addAll(getInheritedPrivateFields(instance.getClass()));
                try {
                    injectFields(fields, instance, framework, injectables);
                } catch (IllegalAccessException e) {
                    logger.warn("", e);
                } finally {
                    fields.clear();
                }
            }
        }

        if (!pushBackInjection.isEmpty()) {
            injectionFailed();
        }

        for (Object o : postponedMethodExecution) {
            try {
                applyMethods(o, (Class<Object>) o.getClass());
            } catch (IllegalAccessException e) {
                logger.warn("", e);
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
        if (!pushBackInjection.contains(instance)) {
            Set<Method> methods = (getInheritedPrivateMethod(defaultType));
            injectMethods(methods, instance);
        }
    }

    private <U> void injectMethods(Set<Method> methods, U instance) throws IllegalAccessException {
        for (Method m : methods) {
            for (Injectable c : introspectors) {
                if (!pushBackInjection.contains(instance)) {
                    try {
                        preMethodInjection(m, instance, (Class<U>) instance.getClass());
                        InjectIntrospector.class.cast(c).introspectMethod(m, instance);
                        postMethodInjection(m, instance, (Class<U>) instance.getClass());
                    } catch (Exception ex) {
                        methodInjectionException(m, instance, (Class<U>) instance.getClass(), ex);
                        throw ex;
                    }
                }
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
        Set<Field> fields = getInheritedPrivateFields(defaultType);

        injectFields(fields, instance, framework, injectables);
    }

    public <U> void injectFields(Set<Field> fields, U instance, AtmosphereFramework framework, LinkedList<Injectable<?>> injectable) throws IllegalAccessException {
        for (Field field : fields) {
            if (field.isAnnotationPresent(Inject.class)) {
                for (Injectable c : injectable) {
                    if (c.supportedType(field.getType())) {

                        if (InjectIntrospector.class.isAssignableFrom(c.getClass())) {
                            InjectIntrospector.class.cast(c).introspectField(instance.getClass(), field);
                        }

                        Class<U> clazz = (Class<U>) instance.getClass();
                        try {
                            field.setAccessible(true);
                            preFieldInjection(field, instance, clazz);
                            Object o = c.injectable(framework.getAtmosphereConfig());

                            if (o == null) {
                                nullFieldInjectionFor(field,instance,clazz);
                                pushBackInjection.add(instance);
                                continue;
                            }
                            postFieldInjection(field, instance, clazz);

                            if (field.getType().equals(Boolean.TYPE)) {
                                field.setBoolean(instance, Boolean.class.cast(o).booleanValue());
                            } else if (field.getType().equals(Integer.TYPE)) {
                                field.setInt(instance, Integer.class.cast(o).intValue());
                            } else if (field.getType().equals(Byte.TYPE)) {
                                field.setByte(instance, Byte.class.cast(o).byteValue());
                            } else if (field.getType().equals(Double.TYPE)) {
                                field.setDouble(instance, Double.class.cast(o).doubleValue());
                            } else if (field.getType().equals(Long.TYPE)) {
                                field.setLong(instance, Long.class.cast(o).longValue());
                            } else if (field.getType().equals(Float.TYPE)) {
                                field.setFloat(instance, Float.class.cast(o).floatValue());
                            } else {
                                field.set(instance, o);
                            }
                        } catch (Exception ex) {
                            fieldInjectionException(field, instance, clazz, ex);
                            throw ex;
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
        return allowInjectionOf(injectable, false);
    }

    public AtmosphereObjectFactory allowInjectionOf(Injectable<?> injectable, boolean first) {
        if (first) {
            injectables.addFirst(injectable);
        } else {
            injectables.add(injectable);
        }
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

                        c.introspectField(instance.getClass(), field);

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

    public void requestScoped(Object instance, Class defaultType) throws IllegalAccessException {
        Set<Field> fields = new HashSet<>();
        fields.addAll(getInheritedPrivateFields(defaultType));

        for (Field field : fields) {
            for (InjectIntrospector c : requestScopedIntrospectors) {

                for (Class annotation : c.getClass().getAnnotation(RequestScoped.class).value()) {
                    if (field.isAnnotationPresent(annotation)) {

                        c.introspectField(instance.getClass(), field);

                        if (c.supportedType(field.getType())) {
                            try {
                                field.setAccessible(true);
                                field.set(instance, c.injectable(config));
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

    public boolean needRequestScoped(Class defaultType) throws IllegalAccessException {
        Set<Field> fields = new HashSet<>();
        fields.addAll(getInheritedPrivateFields(defaultType));

        for (Field field : fields) {
            for (InjectIntrospector c : requestScopedIntrospectors) {
                if (c.supportedType(field.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    public InjectableObjectFactory listener(InjectionListener i) {
        listeners.add(i);
        return this;
    }

    protected void injectionFailed() {
        for (InjectionListener i : listeners) {
            try {
                i.injectionFailed(pushBackInjection);
            } catch (Exception ex) {
                logger.error("", ex);
            }
        }
    }

    protected <T, U extends T> void nullFieldInjectionFor(Field field, U instance, Class<T> clazz) {
        for (InjectionListener i : listeners) {
            try {
                i.nullFieldInjectionFor(field, instance, clazz);
            } catch (Exception ex2) {
                logger.error("", ex2);
            }
        }
    }

    protected <T, U extends T> void fieldInjectionException(Field field, U instance, Class<T> clazz, Exception ex) {
        for (InjectionListener i : listeners) {
            try {
                i.fieldInjectionException(field, instance, clazz, ex);
            } catch (Exception ex2) {
                logger.error("", ex2);
            }
        }
    }

    protected <T, U extends T> void methodInjectionException(Method m, U instance, Class<T> clazz, Exception ex) {
        for (InjectionListener i : listeners) {
            try {
                i.methodInjectionException(m, instance, clazz, ex);
            } catch (Exception ex2) {
                logger.error("", ex2);
            }
        }
    }

    protected <T, U extends T> void preFieldInjection(Field field, U instance, Class<T> clazz) {
        for (InjectionListener i : listeners) {
            try {
                i.preFieldInjection(field, instance, clazz);
            } catch (Exception ex) {
                logger.error("", ex);
            }
        }
    }

    protected <T, U extends T> void postFieldInjection(Field field, U instance, Class<T> clazz) {
        for (InjectionListener i : listeners) {
            try {
                i.postFieldInjection(field, instance, clazz);
            } catch (Exception ex) {
                logger.error("", ex);
            }
        }
    }

    protected <T, U extends T> void preMethodInjection(Method method, U instance, Class<T> clazz) {
        for (InjectionListener i : listeners) {
            try {
                i.preMethodInjection(method, instance, clazz);
            } catch (Exception ex) {
                logger.error("", ex);
            }
        }
    }

    protected <T, U extends T> void postMethodInjection(Method method, U instance, Class<T> clazz) {
        for (InjectionListener i : listeners) {
            try {
                i.postMethodInjection(method, instance, clazz);
            } catch (Exception ex) {
                logger.error("", ex);
            }
        }
    }
}
