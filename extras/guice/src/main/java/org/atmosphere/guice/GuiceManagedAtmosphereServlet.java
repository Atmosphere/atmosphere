/*
 * Copyright 2011 Jeanfrancois Arcand
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
import com.google.inject.Singleton;
import com.google.inject.TypeLiteral;
import com.google.inject.name.Names;
import com.sun.jersey.guice.spi.container.servlet.GuiceContainer;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.handler.ReflectorServletProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletConfig;
import java.util.Map;

/**
 * Google Guice Integration. The difference between this class and the {@link AtmosphereGuiceServlet} is you don't need
 * to define it in web.xml and instead use a pure Guice web.xml file.
 *
 * {@code
    &lt;listener&gt;
        &lt;listener-class&gt;org.company.GuiceContextListener&lt;/listener-class&gt;
    &lt;/listener&gt;
    &lt;filter&gt;
        &lt;filter-name&gt;Guice Filter&lt;/filter-name&gt;
        &lt;filter-class&gt;com.google.inject.servlet.GuiceFilter&lt;/filter-class&gt;
    &lt;/filter&gt;
    &lt;filter-mapping&gt;
        &lt;filter-name&gt;Guice Filter&lt;/filter-name&gt;
        &lt;url-pattern&gt;/*&lt;/url-pattern&gt;
    &lt;/filter-mapping&gt;

  and then:

     @Override
     protected Injector getInjector() {
         return Guice.createInjector(new ServletModule() {
             @Override
             protected void configureServlets() {
                 bind(MessageResource.class);
                 serve("/*async/**").with(*GuiceManagedAtmosphereServlet*.class, new HashMap&lt;String, String&gt;() {
                     {
                         put("org.atmosphere.useWebSocket", "true");
                         put("org.atmosphere.useNative", "true");
                     }
                 });
                 serve("/*rest/**").with(*GuiceContainer*.class);
             }
         });
     }

 }

 * @author Jeanfrancois Arcand
 * @author Richard Wallace
 * @author Mathieu Carbou
 */
@Singleton
public class GuiceManagedAtmosphereServlet extends AtmosphereServlet {

    private static final Logger logger = LoggerFactory.getLogger(GuiceManagedAtmosphereServlet.class);

    public static final String JERSEY_PROPERTIES = GuiceManagedAtmosphereServlet.class.getName() + ".properties";

    /**
     * Auto-detect Jersey when no atmosphere.xml file are specified.
     *
     * @param sc {@link javax.servlet.ServletConfig}
     * @return true if Jersey classes are detected
     */
    @Override
    protected boolean detectSupportedFramework(ServletConfig sc) {
        Injector injector = (Injector) config.getServletContext().getAttribute(Injector.class.getName());
        GuiceContainer guiceServlet = injector.getInstance(GuiceContainer.class);

        setUseStreamForFlushingComments(false);
        ReflectorServletProcessor rsp = new ReflectorServletProcessor();
        setDefaultBroadcasterClassName(JERSEY_BROADCASTER);
        setUseStreamForFlushingComments(true);

        rsp.setServlet(guiceServlet);
        getAtmosphereConfig().setSupportSession(false);

        String mapping = sc.getInitParameter(PROPERTY_SERVLET_MAPPING);
        if (mapping == null) {
            mapping = "/*";
        }

        try {
            Map<String, String> props = injector.getInstance(
                    Key.get(new TypeLiteral<Map<String, String>>() {},Names.named(JERSEY_PROPERTIES)));


            if (props != null) {
                for (String p : props.keySet()) {
                    addInitParameter(p, props.get(p));
                }
            }
        } catch (Exception ex) {
            // Do not fail
            logger.debug("failed to add Jersey init parameters to Atmosphere servlet", ex);
        }

        addAtmosphereHandler(mapping, rsp);
        return true;
    }
}

