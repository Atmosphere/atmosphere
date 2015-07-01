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
package org.atmosphere.util;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

public class ThreadLocalInvoker<T> implements InvocationHandler {

    private ThreadLocal<T> threadLocalInstance = new ThreadLocal<T>();

    public void set(T threadLocalInstance) {
        this.threadLocalInstance.set(threadLocalInstance);
    }

    public T get() {
        return this.threadLocalInstance.get();
    }

    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if (threadLocalInstance.get() == null) {
            throw new IllegalStateException("No thread local" + proxy.getClass());
        }

        try {
            return method.invoke(threadLocalInstance.get(), args);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        } catch (InvocationTargetException ex) {
            throw ex.getTargetException();
        }
    }
}
