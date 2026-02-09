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

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.inject.InjectableObjectFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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

        return super.newClassInstance(classType, defaultType);
    }

    @Override
    public String toString() {
        return "SpringAtmosphereObjectFactory";
    }
}
