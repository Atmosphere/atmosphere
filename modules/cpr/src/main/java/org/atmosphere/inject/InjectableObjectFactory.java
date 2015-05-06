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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Support injection of Atmosphere's Internal object using {@link }
 * {@link org.atmosphere.cpr.AtmosphereConfig},{@link AtmosphereFramework,{@link AtmosphereFramework,{@link org.atmosphere.cpr.BroadcasterFactory,
 * {@link org.atmosphere.cpr.AtmosphereResourceFactory } ,{@link org.atmosphere.cpr.DefaultMetaBroadcaster } and
 * {@link org.atmosphere.cpr.AtmosphereResourceSessionFactory }
 *
 * @author Jeanfrancois Arcand
 */
public class InjectableObjectFactory implements AtmosphereObjectFactory<Injectable<?>> {

    protected static final Logger logger = LoggerFactory.getLogger(AtmosphereFramework.class);

    public final static Class<Injectable<?>>[] DEFAULT_ATMOSPHERE_INJECTABLE = new Class[]{
            AtmosphereConfigInjectable.class,
            AtmosphereFrameworkInjectable.class,
            AtmosphereResourceFactoryInjectable.class,
            AtmosphereResourceSessionFactoryInjectable.class,
            BroadcasterFactoryInjectable.class,
            MetaBroadcasterInjectable.class
    };

    private final List<Injectable<?>> injectables = new ArrayList<Injectable<?>>();
    private AtmosphereConfig config;

    @Override
    public void configure(AtmosphereConfig config) {
        this.config = config;
        for (Class<Injectable<?>> i : DEFAULT_ATMOSPHERE_INJECTABLE) {
            try {
                injectables.add(i.newInstance());
            } catch (Exception e) {
                logger.error("", e);
            }
        }
    }

    @Override
    public <T, U extends T> U newClassInstance(Class<T> classType,
                                               Class<U> defaultType) throws InstantiationException, IllegalAccessException {

        U instance = defaultType.newInstance();

        injectAtmosphereInternalObject(instance, defaultType, config.framework());
        postConstructExecution(instance, defaultType);

        return instance;
    }

    /**
     * Execute {@PostConstruct} method.
     *
     * @param instance    the requested object.
     * @param defaultType the type of the requested object
     * @param <U>
     * @throws IllegalAccessException
     */
    public <U> void postConstructExecution(U instance, Class<U> defaultType) throws IllegalAccessException {
        Method[] methods = defaultType.getDeclaredMethods();
        for (Method m : methods) {
            if (m.isAnnotationPresent(PostConstruct.class)) {
                try {
                    m.invoke(instance);
                    break;
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        methods = defaultType.getMethods();
        for (Method m : methods) {
            if (m.isAnnotationPresent(PostConstruct.class)) {
                try {
                    m.invoke(instance);
                    return;
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
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
    public <U> void injectAtmosphereInternalObject(U instance, Class<U> defaultType, AtmosphereFramework framework) throws IllegalAccessException {
        Field[] fields = defaultType.getDeclaredFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Inject.class)) {
                for (Injectable c : injectables) {
                    if (c.supportedType(field.getType())) {
                        field.set(instance, c.injectable(framework.getAtmosphereConfig()));
                        break;
                    }
                }
            }
        }

        fields = defaultType.getFields();
        for (Field field : fields) {
            field.setAccessible(true);
            if (field.isAnnotationPresent(Inject.class)) {
                for (Injectable c : injectables) {
                    if (c.supportedType(field.getType())) {
                        field.set(instance, c.injectable(framework.getAtmosphereConfig()));
                        return;
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

}
