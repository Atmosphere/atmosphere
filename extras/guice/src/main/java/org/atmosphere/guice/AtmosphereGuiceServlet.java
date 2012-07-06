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
package org.atmosphere.guice;

import com.google.inject.Injector;
import com.google.inject.Key;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.DefaultBroadcasterFactory;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.atmosphere.jersey.JerseyBroadcaster;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.util.Map;

import static org.atmosphere.cpr.FrameworkConfig.JERSEY_CONTAINER;

/**
 * Google Guice Integration. To use it, just do in web.xml:
 * <p/>
 * <blockquote><code>
 * &lt;web-app version="2.4" xmlns="http://java.sun.com/xml/ns/j2ee"
 * xmlns:j2ee = "http://java.sun.com/xml/ns/j2ee"
 * xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
 * xsi:schemaLocation="http://java.sun.com/xml/ns/j2ee    http://java.sun.com/xml/ns/j2ee/web-app_2_4.xsd"&gt;
 * &lt;listener&gt;
 * &lt;listener-class&gt;org.atmosphere.samples.guice.GuiceChatConfig&lt;/listener-class&gt;
 * &lt;/listener&gt;
 * &lt;description&gt;Atmosphere Chat&lt;/description&gt;
 * &lt;display-name&gt;Atmosphere Chat&lt;/display-name&gt;
 * &lt;servlet&gt;
 * &lt;description&gt;AtmosphereServlet&lt;/description&gt;
 * &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 * &lt;servlet-class&gt;org.atmosphere.guice.AtmosphereGuiceServlet&lt;/servlet-class&gt;
 * &lt;load-on-startup&gt;0&lt;/load-on-startup&gt;
 * &lt;/servlet&gt;
 * &lt;servlet-mapping&gt;
 * &lt;servlet-name&gt;AtmosphereServlet&lt;/servlet-name&gt;
 * &lt;url-pattern&gt;/chat/*&lt;/url-pattern&gt;
 * &lt;/servlet-mapping&gt;
 * &lt;/web-app&gt;
 * <p/>
 * and then
 * <p/>
 * public class GuiceConfig extends GuiceServletContextListener {
 *
 * @author Jeanfrancois Arcand
 * @author Richard Wallace
 * @Override protected Injector getInjector() {
 * return Guice.createInjector(new ServletModule() {
 * @Override protected void configureServlets() {
 * bind(PubSubTest.class);
 * bind(new TypeLiteral&lt;Map&lt;String, String&gt;&gt;() {
 * }).annotatedWith(Names.named(AtmosphereGuiceServlet.JERSEY_PROPERTIES)).toInstance(
 * Collections.&lt;String, String>emptyMap());
 * }
 * });
 * }
 * }
 * </code></blockquote>
 */
public class AtmosphereGuiceServlet extends AtmosphereServlet {

    private static final Logger logger = LoggerFactory.getLogger(AtmosphereGuiceServlet.class);
    public static final String JERSEY_PROPERTIES = AtmosphereGuiceServlet.class.getName() + ".properties";
    private static final String GUICE_FILTER = "com.google.inject.servlet.GuiceFilter";
    private boolean guiceInstalled = false;

    public AtmosphereGuiceServlet() {
        this(false,true);
    }

    /**
     * Create an Atmosphere Servlet.
     *
     * @param isFilter true if this instance is used as an {@link org.atmosphere.cpr.AtmosphereFilter}
     */
    public AtmosphereGuiceServlet(boolean isFilter, boolean autoDetectHandlers) {
        framework = new AtmosphereFramework(isFilter, autoDetectHandlers) {

            /**
             * Install Guice event if other extension has been already installed.
             *
             * @param sc {@link javax.servlet.ServletConfig}
             * @throws ServletException
             */
            public void loadConfiguration(ServletConfig sc) throws ServletException {
                super.loadConfiguration(sc);

                if (!guiceInstalled) {
                    detectSupportedFramework(sc);
                }
            }

            /**
             * Auto-detect Jersey when no atmosphere.xml file are specified.
             *
             * @param sc {@link javax.servlet.ServletConfig}
             * @return true if Jersey classes are detected
             */
            protected boolean detectSupportedFramework(ServletConfig sc) {
                Injector injector = (Injector) framework().getAtmosphereConfig().getServletContext().getAttribute(Injector.class.getName());
                GuiceContainer guiceServlet = injector.getInstance(GuiceContainer.class);

                setUseStreamForFlushingComments(false);
                ReflectorServletProcessor rsp = new ReflectorServletProcessor();

                boolean isJersey = false;
                try {
                    Thread.currentThread().getContextClassLoader().loadClass(JERSEY_CONTAINER);
                    setDefaultBroadcasterClassName(FrameworkConfig.JERSEY_BROADCASTER)
                            .setUseStreamForFlushingComments(true)
                            .getAtmosphereConfig().setSupportSession(false);
                    DefaultBroadcasterFactory.buildAndReplaceDefaultfactory(JerseyBroadcaster.class, getAtmosphereConfig());
                    isJersey = true;
                } catch (Throwable t) {
                }

                rsp.setServlet(guiceServlet);

                String mapping = sc.getInitParameter(ApplicationConfig.PROPERTY_SERVLET_MAPPING);
                if (mapping == null) {
                    mapping = "/*";
                }

                if (isJersey) {
                    try {
                        Map<String, String> props = injector.getInstance(
                                Key.get(new TypeLiteral<Map<String, String>>() {
                                }, Names.named(JERSEY_PROPERTIES)));


                        if (props != null) {
                            for (String p : props.keySet()) {
                                framework().addInitParameter(p, props.get(p));
                            }
                        }
                    } catch (Exception ex) {
                        // Do not fail
                        logger.debug("failed to add Jersey init parameters to Atmosphere servlet", ex);
                    }
                }

                addAtmosphereHandler(mapping, rsp);
                guiceInstalled = true;
                return true;
            }
        };
    }
}

