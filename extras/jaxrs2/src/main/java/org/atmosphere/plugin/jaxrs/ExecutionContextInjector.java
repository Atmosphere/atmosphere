/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.plugin.jaxrs;

import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.jersey.BaseInjectableProvider;

import javax.ws.rs.core.Context;
import javax.ws.rs.core.ExecutionContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * Allow injection of {@link ExecutionContext}
 *
 * @author Jeanfrancois Arcand
 */
abstract public class ExecutionContextInjector extends BaseInjectableProvider {

    boolean isValidType(Type t) {
        return (t instanceof Class) && ExecutionContext.class.isAssignableFrom((Class) t);
    }

    public static final class PerRequest extends ExecutionContextInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.PerRequest;
        }

        @Override
        public Injectable getInjectable(ComponentContext ic, Context a, Type t) {
            if (!isValidType(t))
                return null;

            return new Injectable<ExecutionContext>() {
                @Override
                public ExecutionContext getValue() {
                    return new AtmosphereExecutionContext(AtmosphereResourceImpl.class.cast(getAtmosphereResource(AtmosphereResource.class, false)));
                }
            };
        }
    }

    public static final class Singleton extends ExecutionContextInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.Singleton;
        }

        @Override
        public Injectable getInjectable(ComponentContext ic, Context a, Type t) {
            if (!isValidType(t))
                return null;

            return new Injectable<ExecutionContext>() {
                @Override
                public ExecutionContext getValue() {
                    return (ExecutionContext) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                            new Class[]{ExecutionContext.class},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    return method.invoke(new AtmosphereExecutionContext(AtmosphereResourceImpl.class.cast(getAtmosphereResource(AtmosphereResource.class, false))),
                                            args);
                                }
                            });

                }
            };
        }
    }
}
