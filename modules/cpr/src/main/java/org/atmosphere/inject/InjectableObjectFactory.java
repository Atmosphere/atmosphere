/*
 * Copyright 2014 Jeanfrancois Arcand
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

import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereObjectFactory;

import javax.annotation.PostConstruct;
import javax.inject.Inject;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

public class InjectableObjectFactory implements AtmosphereObjectFactory {

    private final static Class<Injectable<?>>[] defaultInjectables = new Class[]{
            AtmosphereConfigInjectable.class,
            AtmosphereFrameworkInjectable.class,
            AtmosphereResourceFactoryInjectable.class,
            AtmosphereResourceSessionFactoryInjectable.class,
            BroadcasterFactoryInjectable.class,
            MetaBroadcasterInjectable.class
    };

    private final List<Injectable<?>> injectables = new ArrayList<Injectable<?>>();

    @Override
    public <T, U extends T> U newClassInstance(AtmosphereFramework framework,
                                               Class<T> classType,
                                               Class<U> defaultType) throws InstantiationException, IllegalAccessException {

        // Thread safe
        if (injectables.isEmpty()) {
            for (Class<Injectable<?>> i : defaultInjectables) {
                injectables.add(i.newInstance());
            }
        }

        U instance = defaultType.newInstance();

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

        Method[] methods = defaultType.getMethods();
        for (Method m : methods) {
            if (m.isAnnotationPresent(PostConstruct.class)) {
                try {
                    m.invoke(instance);
                } catch (InvocationTargetException e) {
                    throw new RuntimeException(e);
                }
            }
        }

        return instance;
    }

    public InjectableObjectFactory injectable(Injectable<?> injectable) {
        injectables.add(injectable);
        return this;
    }

    @Override
    public String toString() {
        return InjectableObjectFactory.class.getName();
    }

}
