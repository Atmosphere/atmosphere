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
package org.atmosphere.inject;

import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Type;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class InjectableObjectFactoryTest {

    private InjectableObjectFactory factory;
    private AtmosphereConfig config;
    private AtmosphereFramework framework;

    @BeforeEach
    void setUp() {
        factory = new InjectableObjectFactory();
        config = mock(AtmosphereConfig.class);
        framework = mock(AtmosphereFramework.class);
        when(config.framework()).thenReturn(framework);
        when(framework.getAtmosphereConfig()).thenReturn(config);
        when(config.getInitParameter("org.atmosphere.cpr.injection.try", 5)).thenReturn(5);
        when(config.getInitParameter("org.atmosphere.cpr.injection.listeners", "")).thenReturn("");
    }

    @Test
    void newClassInstanceCreatesInstance() throws Exception {
        factory.configure(config);

        SimplePojo pojo = factory.newClassInstance(SimplePojo.class, SimplePojo.class);

        assertNotNull(pojo);
    }

    @Test
    void newClassInstanceReturnsDifferentInstances() throws Exception {
        factory.configure(config);

        SimplePojo first = factory.newClassInstance(SimplePojo.class, SimplePojo.class);
        SimplePojo second = factory.newClassInstance(SimplePojo.class, SimplePojo.class);

        assertNotNull(first);
        assertNotNull(second);
    }

    @Test
    void newClassInstanceCreatesSubtype() throws Exception {
        factory.configure(config);

        SimpleBase instance = factory.newClassInstance(SimpleBase.class, SimpleSub.class);

        assertNotNull(instance);
    }

    @Test
    void allowInjectionOfAddsInjectable() {
        factory.configure(config);

        Injectable<String> injectable = new Injectable<>() {
            @Override
            public boolean supportedType(Type t) {
                return t == String.class;
            }

            @Override
            public String injectable(AtmosphereConfig cfg) {
                return "injected-value";
            }
        };

        factory.allowInjectionOf(injectable);

        String result = factory.getInjectable(String.class);
        assertEquals("injected-value", result);
    }

    @Test
    void allowInjectionOfFirstAddsToFront() {
        factory.configure(config);

        Injectable<String> first = new Injectable<>() {
            @Override
            public boolean supportedType(Type t) {
                return t == String.class;
            }

            @Override
            public String injectable(AtmosphereConfig cfg) {
                return "first";
            }
        };

        Injectable<String> second = new Injectable<>() {
            @Override
            public boolean supportedType(Type t) {
                return t == String.class;
            }

            @Override
            public String injectable(AtmosphereConfig cfg) {
                return "second";
            }
        };

        factory.allowInjectionOf(first);
        factory.allowInjectionOf(second, true);

        String result = factory.getInjectable(String.class);
        assertEquals("second", result);
    }

    @Test
    void getInjectableReturnsNullWhenNotFound() {
        factory.configure(config);

        Object result = factory.getInjectable(Runnable.class);

        assertNull(result);
    }

    @Test
    void toStringReturnsClassName() {
        assertEquals(InjectableObjectFactory.class.getName(), factory.toString());
    }

    @Test
    void listenerIsRegistered() {
        factory.configure(config);

        InjectionListener listener = mock(InjectionListener.class);
        InjectableObjectFactory returned = factory.listener(listener);

        assertSame(factory, returned);
    }

    @Test
    void allowInjectionOfReturnsSelf() {
        factory.configure(config);

        Injectable<String> injectable = new Injectable<>() {
            @Override
            public boolean supportedType(Type t) {
                return t == String.class;
            }

            @Override
            public String injectable(AtmosphereConfig cfg) {
                return "test";
            }
        };

        var result = factory.allowInjectionOf(injectable);
        assertNotNull(result);
    }

    // Simple test classes with no-arg constructors
    public static class SimplePojo {
        public SimplePojo() {
        }
    }

    public static class SimpleBase {
        public SimpleBase() {
        }
    }

    public static class SimpleSub extends SimpleBase {
        public SimpleSub() {
        }
    }
}
