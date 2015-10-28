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

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Map;

public class VoidServletConfig implements ServletConfig {
    public final static String ATMOSPHERE_SERVLET =  "AtmosphereServlet";

    private final Map<String, String> initParams;

    public VoidServletConfig() {
        initParams = Collections.emptyMap();
    }

    public VoidServletConfig(Map<String, String> initParams) {
        this.initParams = initParams;
    }

    @Override
    public String getServletName() {
        return ATMOSPHERE_SERVLET;
    }

    @Override
    public ServletContext getServletContext() {
        return (ServletContext) Proxy.newProxyInstance(getClass().getClassLoader(), new Class[]{ServletContext.class},
                new InvocationHandler() {
                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
                        return ServletProxyFactory.getDefault().proxy(proxy, method, args);
                    }
                }
        );
    }

    @Override
    public String getInitParameter(String name) {
        return initParams.get(name);
    }

    @Override
    public Enumeration<String> getInitParameterNames() {
        return Collections.enumeration(initParams.values());
    }
}

