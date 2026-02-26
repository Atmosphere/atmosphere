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
package org.atmosphere.spring.boot;

import java.lang.reflect.Field;

import jakarta.inject.Inject;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.inject.InjectableObjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.context.ApplicationContext;

public class SpringAtmosphereObjectFactory extends InjectableObjectFactory {

    private static final Logger logger = LoggerFactory.getLogger(SpringAtmosphereObjectFactory.class);

    private final ApplicationContext applicationContext;

    public SpringAtmosphereObjectFactory(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    @Override
    public void configure(AtmosphereConfig config) {
        super.configure(config);
    }

    @Override
    public <T, U extends T> U newClassInstance(Class<T> classType, Class<U> defaultType)
            throws InstantiationException, IllegalAccessException {

        String[] beanNames = applicationContext.getBeanNamesForType(defaultType);
        if (beanNames.length > 0) {
            U bean = applicationContext.getBean(defaultType);
            logger.trace("Found Spring bean for {}", defaultType.getName());
            inject(bean);
            return bean;
        }

        try {
            AutowireCapableBeanFactory beanFactory = applicationContext.getAutowireCapableBeanFactory();
            U instance = beanFactory.createBean(defaultType);
            logger.trace("Created and autowired Spring bean for {}", defaultType.getName());
            inject(instance);
            return instance;
        } catch (Exception e) {
            logger.trace("Spring could not create {}; falling back to InjectableObjectFactory",
                    defaultType.getName());
        }

        // Hybrid path: Atmosphere creates the instance and injects its own
        // managed objects (AtmosphereResource, AtmosphereResourceEvent, etc.),
        // then we inject any Spring beans into remaining @Inject fields.
        U instance = super.newClassInstance(classType, defaultType);
        injectSpringBeans(instance);
        return instance;
    }

    /**
     * Inject Spring-managed beans into {@code @Inject} or {@code @Autowired}
     * annotated fields that Atmosphere's injector left null (i.e. fields whose
     * type is a Spring bean). This enables {@code @ManagedService} classes to
     * use either annotation for both Atmosphere-managed objects and Spring beans.
     */
    private void injectSpringBeans(Object instance) {
        for (Class<?> clazz = instance.getClass(); clazz != null && clazz != Object.class;
             clazz = clazz.getSuperclass()) {
            for (Field field : clazz.getDeclaredFields()) {
                if (!field.isAnnotationPresent(Inject.class)
                        && !field.isAnnotationPresent(Autowired.class)) {
                    continue;
                }
                try {
                    String[] names = applicationContext.getBeanNamesForType(field.getType());
                    if (names.length > 0) {
                        field.setAccessible(true);
                        if (field.get(instance) == null) {
                            field.set(instance, applicationContext.getBean(field.getType()));
                            logger.trace("Injected Spring bean {} into {}.{}",
                                    field.getType().getSimpleName(),
                                    instance.getClass().getSimpleName(), field.getName());
                        }
                    }
                } catch (Exception e) {
                    logger.warn("Could not inject Spring bean for {}.{}: {}",
                            instance.getClass().getSimpleName(), field.getName(), e.getMessage());
                }
            }
        }
    }

    @Override
    public String toString() {
        return "SpringAtmosphereObjectFactory";
    }
}
