/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.tests.guice.managed;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.google.inject.servlet.GuiceServletContextListener;
import com.google.inject.servlet.ServletModule;
import org.atmosphere.container.GrizzlyCometSupport;
import org.atmosphere.container.Jetty7CometSupport;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.guice.AtmosphereGuiceServlet;
import org.atmosphere.guice.GuiceContainer;
import org.atmosphere.guice.GuiceManagedAtmosphereServlet;
import org.atmosphere.tests.guice.PubSubTest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public final class GuiceContextListener extends GuiceServletContextListener {
    @Override
    protected Injector getInjector() {
        return Guice.createInjector(new ServletModule() {
            @Override
            protected void configureServlets() {
                bind(PubSubTest.class);
                bind(new TypeLiteral<Map<String, String>>() {
                }).annotatedWith(Names.named(AtmosphereGuiceServlet.JERSEY_PROPERTIES)).toInstance(
                        Collections.<String, String>emptyMap());
                serve("/*").with(GuiceManagedAtmosphereServlet.class, new HashMap<String, String>() {
                    {
                        put(ApplicationConfig.PROPERTY_COMET_SUPPORT, Jetty7CometSupport.class.getName());
                        put("org.atmosphere.useNative", "true");
                    }
                });
            }
        });
    }
}
