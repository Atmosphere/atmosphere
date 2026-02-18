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
package org.atmosphere.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * An {@link InvocationHandler} that delegates method calls to a thread-local instance.
 * Used for request-scoped dependency injection via dynamic proxies.
 *
 * @param <T> the type of the proxied instance
 * @author Jeanfrancois Arcand
 */
public class ThreadLocalProxy<T> implements InvocationHandler {

    private final ThreadLocal<T> threadLocalInstance = new ThreadLocal<>();

    public void set(T instance) {
        threadLocalInstance.set(instance);
    }

    public T get() {
        return threadLocalInstance.get();
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        T instance = threadLocalInstance.get();
        if (instance == null) {
            throw new IllegalStateException("No thread-local instance for " + proxy.getClass());
        }
        try {
            return method.invoke(instance, args);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
