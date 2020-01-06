/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.jersey;

import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import org.atmosphere.cpr.Broadcaster;

import javax.ws.rs.core.Context;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * Allow {@link Broadcaster} injection via the {@link Context} annotation supported
 * by Jersey.
 *
 * @author Jeanfrancois Arcand
 * @author Paul Sandoz
 */
abstract class BroadcasterInjector extends BaseInjectableProvider {

    boolean isValidType(Type t) {
        return (t instanceof Class) && Broadcaster.class.isAssignableFrom((Class) t);
    }

    public static final class PerRequest extends BroadcasterInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.PerRequest;
        }

        @Override
        public Injectable getInjectable(ComponentContext ic, Context a, Type t) {
            if (!isValidType(t))
                return null;

            return new Injectable<Broadcaster>() {
                @Override
                public Broadcaster getValue() {
                    return getAtmosphereResource(Broadcaster.class, true).getBroadcaster();
                }
            };
        }
    }

    public static final class Singleton extends BroadcasterInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.Singleton;
        }

        @Override
        public Injectable getInjectable(ComponentContext ic, Context a, Type t) {
            if (!isValidType(t))
                return null;

            return new Injectable<Broadcaster>() {
                @Override
                public Broadcaster getValue() {
                    return (Broadcaster) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                            new Class[]{Broadcaster.class},
                            new InvocationHandler() {
                                @Override
                                public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                                    return method.invoke(getAtmosphereResource(Broadcaster.class, true).getBroadcaster(),
                                            args);
                                }
                            });

                }
            };
        }
    }
}
