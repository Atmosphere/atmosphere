/*
 * Copyright 2013 Jeanfrancois Arcand
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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

/**
 * A Factory class that can be used to handle the Servlet API internal proxy.
 *
 * @author Jeanfrancois Arcand
 */
public class ServletProxyFactory {
    private final static Logger logger = LoggerFactory.getLogger(ServletProxyFactory.class);
    private static ServletProxyFactory factory;
    private final Map<String, MethodHandler> methods = new HashMap<String, MethodHandler>();
    private final static MethodHandler voidMethodHandler = new EchoMethodHandler();

    private ServletProxyFactory() {
        addMethodHandler("encodeURL", voidMethodHandler)
                .addMethodHandler("encodeRedirectURL", voidMethodHandler)
                .addMethodHandler("getCharacterEncoding", new UTF8Handler())
                .addMethodHandler("getMajorVersion", new MethodHandler() {
                    @Override
                    public Object handle(Object clazz, Method method, Object[] methodObjects) {
                        return new Integer(3);
                    }
                });

    }

    public final Object proxy(Object clazz, Method method, Object[] methodObjects) {
        MethodHandler m = methods.get(method.getName());
        if (m != null) {
            logger.trace("Method {} handled by MethodHandler {}", method.getName(), m);
            return m.handle(clazz, method, methodObjects);
        }
        logger.trace("Method {} not supported", method.getName());
        return null;
    }

    public static ServletProxyFactory getDefault() {
        if (factory == null) {
            factory = new ServletProxyFactory();
        }
        return factory;
    }

    public ServletProxyFactory addMethodHandler(String method, MethodHandler m) {
        methods.put(method, m);
        return this;
    }

    /**
     * A MethodHandler can be added to allow Frameworks using Atmosphere to customize internal behavior.
     */
    public static interface MethodHandler {
        /**
         * Same API as the {@link java.lang.reflect.Proxy} class
         * @param clazz
         * @param method
         * @param methodObjects
         * @return this
         */
        public Object handle(Object clazz, Method method, Object[] methodObjects);
    }

    public static class EchoMethodHandler implements MethodHandler{
        @Override
        public Object handle(Object clazz, Method method, Object[] methodObjects) {
            return methodObjects[0];
        }
    }

    public static class UTF8Handler implements MethodHandler{
        @Override
        public Object handle(Object clazz, Method method, Object[] methodObjects) {
            return "UTF-8";
        }
    }
}


