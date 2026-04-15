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

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ThreadLocalProxyTest {

    @Test
    void setAndGetReturnsValue() {
        var proxy = new ThreadLocalProxy<String>();
        proxy.set("hello");
        assertEquals("hello", proxy.get());
    }

    @Test
    void getReturnsNullWhenNotSet() {
        var proxy = new ThreadLocalProxy<String>();
        assertNull(proxy.get());
    }

    @Test
    void setOverwritesPreviousValue() {
        var proxy = new ThreadLocalProxy<String>();
        proxy.set("first");
        proxy.set("second");
        assertEquals("second", proxy.get());
    }

    @Test
    void setNullClearsValue() {
        var proxy = new ThreadLocalProxy<String>();
        proxy.set("value");
        proxy.set(null);
        assertNull(proxy.get());
    }

    @Test
    void invokeWithNoInstanceThrows() throws Throwable {
        var proxy = new ThreadLocalProxy<Runnable>();
        var method = Runnable.class.getMethod("run");
        assertThrows(IllegalStateException.class,
                () -> proxy.invoke(new Object(), method, null));
    }

    @Test
    void invokeDelegatesToThreadLocalInstance() throws Throwable {
        var proxy = new ThreadLocalProxy<CharSequence>();
        proxy.set("hello");
        var method = CharSequence.class.getMethod("length");
        Object result = proxy.invoke(proxy, method, null);
        assertEquals(5, result);
    }

    @Test
    void invokeWithArgsForwardsToDelegate() throws Throwable {
        var proxy = new ThreadLocalProxy<CharSequence>();
        proxy.set("hello world");
        var method = CharSequence.class.getMethod("charAt", int.class);
        Object result = proxy.invoke(proxy, method, new Object[]{6});
        assertEquals('w', result);
    }
}
