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
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.BroadcasterListener;

import javax.ws.rs.core.Context;
import java.lang.reflect.Type;
import java.util.Collection;

/**
 * Allow {@link org.atmosphere.cpr.BroadcasterFactory} injection via the {@link Context} annotation supported
 * by Jersey.
 *
 * @author Jeanfrancois Arcand
 * @author Paul Sandoz
 */
abstract class BroadcasterFactoryInjector extends BaseInjectableProvider {

    protected BroadcasterFactoryInjector() {
    }

    boolean isValidType(Type t) {
        return (t instanceof Class) && BroadcasterFactory.class.isAssignableFrom((Class) t);
    }

    public static final class PerRequest extends BroadcasterFactoryInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.PerRequest;
        }

        @Override
        public Injectable<BroadcasterFactory> getInjectable(ComponentContext ic, Context a, Type c) {
            if (!isValidType(c))
                return null;

            return new Injectable<BroadcasterFactory>() {
                @Override
                public BroadcasterFactory getValue() {
                    return getAtmosphereResource(AtmosphereResource.class, true).getAtmosphereConfig().getBroadcasterFactory();
                }
            };
        }
    }

    public static final class Singleton extends BroadcasterFactoryInjector {
        @Override
        public ComponentScope getScope() {
            return ComponentScope.Singleton;
        }

        @Override
        public Injectable<BroadcasterFactory> getInjectable(ComponentContext ic, Context a, Type c) {
            if (!isValidType(c))
                return null;

            return new Injectable<BroadcasterFactory>() {
                @Override
                public BroadcasterFactory getValue() {
                    return new BroadcasterFactoryProxy();
                }
            };
        }

        class BroadcasterFactoryProxy implements BroadcasterFactory {
            BroadcasterFactory _get() {
                return getAtmosphereResource(AtmosphereResource.class, true).getAtmosphereConfig().getBroadcasterFactory();
            }

            @Override
            public void configure(Class<? extends Broadcaster> clazz, String broadcasterLifeCyclePolicy, AtmosphereConfig c) {
                _get().configure(clazz, broadcasterLifeCyclePolicy, c);
            }

            @Override
            public Broadcaster get() {
                return _get().get();
            }

            @Override
            public Broadcaster get(Object id) {
                return _get().get(id);
            }

            @Override
            public <T extends Broadcaster> T get(Class<T> c, Object id) {
                return _get().get(c, id);
            }

            @Override
            public void destroy() {
                _get().destroy();
            }

            @Override
            public boolean add(Broadcaster b, Object id) {
                return _get().add(b, id);
            }

            @Override
            public boolean remove(Broadcaster b, Object id) {
                return _get().remove(b, id);
            }

            @Override
            public <T extends Broadcaster> T lookup(Class<T> c, Object id) {
                return _get().lookup(c, id);
            }

            @Override
            public <T extends Broadcaster> T lookup(Class<T> c, Object id, boolean createIfNull) {
                return _get().lookup(c, id, createIfNull);
            }

            @Override
            public Broadcaster lookup(Object id) {
                return _get().lookup(id);
            }

            @Override
            public Broadcaster lookup(Object id, boolean createIfNull) {
                return _get().lookup(id, createIfNull);
            }

            @Override
            public void removeAllAtmosphereResource(AtmosphereResource r) {
                _get().removeAllAtmosphereResource(r);
            }

            @Override
            public boolean remove(Object id) {
                return _get().remove(id);
            }

            @Override
            public Collection<Broadcaster> lookupAll() {
                return _get().lookupAll();
            }

            @Override
            public BroadcasterFactory addBroadcasterListener(BroadcasterListener b) {
                _get().addBroadcasterListener(b);
                return this;
            }

            @Override
            public BroadcasterFactory removeBroadcasterListener(BroadcasterListener b) {
                _get().removeBroadcasterListener(b);
                return this;
            }

            @Override
            public Collection<BroadcasterListener> broadcasterListeners() {
                return _get().broadcasterListeners();
            }
        }
    }
}
