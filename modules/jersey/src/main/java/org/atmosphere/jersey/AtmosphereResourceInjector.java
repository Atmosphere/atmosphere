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
package org.atmosphere.jersey;

import com.sun.jersey.core.spi.component.ComponentContext;
import com.sun.jersey.core.spi.component.ComponentScope;
import com.sun.jersey.spi.inject.Injectable;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponseImpl;

import javax.ws.rs.core.Context;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * Allow {@link AtmosphereResourceEvent} injection via the {@link Context} annotation supported
 * by Jersey.
 *
 * @author Jeanfrancois Arcand
 * @author Paul Sandoz
 */
abstract class AtmosphereResourceInjector extends BaseInjectableProvider {

    boolean isValidType(Type c) {
        if (c == AtmosphereResource.class) return true;

        if (c instanceof ParameterizedType) {
            ParameterizedType pt = (ParameterizedType) c;
            if (pt.getRawType() != AtmosphereResource.class) return false;

            if (pt.getActualTypeArguments().length != 2) return false;

            if (pt.getActualTypeArguments()[0] != AtmosphereRequestImpl.class) return false;
            if (pt.getActualTypeArguments()[1] != AtmosphereResponseImpl.class) return false;

            return true;
        }
        return false;
    }

    public static final class PerRequest extends AtmosphereResourceInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.PerRequest;
        }

        @Override
        public Injectable<AtmosphereResource> getInjectable(ComponentContext ic, Context a, Type c) {
            if (!isValidType(c))
                return null;

            return new Injectable<AtmosphereResource>() {
                @Override
                public AtmosphereResource getValue() {
                    return getAtmosphereResource(AtmosphereResourceImpl.class, false);
                }
            };
        }
    }

    public static final class Singleton extends AtmosphereResourceInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.Singleton;
        }

        @Override
        public Injectable<AtmosphereResource> getInjectable(ComponentContext ic, Context a, Type c) {
            if (!isValidType(c))
                return null;

            return new Injectable<AtmosphereResource>() {
                @Override
                public AtmosphereResource getValue() {
                    return (AtmosphereResource) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                            new Class[]{AtmosphereResource.class}, new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                            return method.invoke(getAtmosphereResource(AtmosphereResource.class, false), args);
                        }
                    });
                }
            };
        }
    }

}
