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
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.util.ThreadLocalInvoker;

import javax.inject.Named;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;

/**
 * {@link Broadcaster} and {@link Named} injection support.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterIntrospector extends InjectIntrospectorAdapter<Broadcaster> {
    private String name = Broadcaster.ROOT_MASTER;

    public WHEN when() {
        return WHEN.RUNTIME;
    }

    @Override
    public boolean supportedType(Type t) {
        return (t instanceof Class) && Broadcaster.class.isAssignableFrom((Class) t);
    }

    @Override
    public Broadcaster injectable(AtmosphereConfig config) {

        String s = (String) config.properties().get(Thread.currentThread().getName() + ".PATH");
        if (s != null) {
            name = name.substring(0, name.indexOf("{")) + s;
        }

        final Broadcaster broadcaster = config.getBroadcasterFactory().lookup(name, true);

        return (Broadcaster) Proxy.newProxyInstance(this.getClass().getClassLoader(),
                new Class[]{Broadcaster.class}, new ThreadLocalInvoker() {
                    {
                        set(broadcaster);
                    }

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return method.invoke(broadcaster, args);
                    }
                });
    }

    @Override
    public void introspectField(Field f) {
        if (f.isAnnotationPresent(Named.class)) {
            name = f.getAnnotation(Named.class).value();
        }
    }
}
