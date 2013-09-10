/*
 * Copyright 2013 Jean-Francois Arcand
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
package org.atmosphere.annotation.custom;

import org.atmosphere.annotation.Processor;
import org.atmosphere.config.AtmosphereAnnotation;
import org.atmosphere.cpr.Action;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler;
import org.atmosphere.util.SimpleBroadcaster;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.util.Enumeration;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;

public class CustomAnnotationTest {
    private AtmosphereFramework framework;
    private static final AtomicReference<AtmosphereResource> r = new AtomicReference<AtmosphereResource>();
    private static final AtomicReference<String> message = new AtomicReference<String>();

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setDefaultBroadcasterClassName(SimpleBroadcaster.class.getName()) ;
        framework.addAnnotationPackage(MyAnnotation.class);
        framework.setAsyncSupport(new AsynchronousProcessor(framework.getAtmosphereConfig()) {

            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return suspended(req, res);
            }

            public void action(AtmosphereResourceImpl r) {
                try {
                    resumed(r.getRequest(), r.getResponse());
                } catch (IOException e) {
                    e.printStackTrace();
                } catch (ServletException e) {
                    e.printStackTrace();
                }
            }
        }).init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return mock(ServletContext.class);
            }

            @Override
            public String getInitParameter(String name) {
                return null;
            }

            @Override
            public Enumeration<String> getInitParameterNames() {
                return null;
            }
        });
    }

    @AfterMethod
    public void after() {
        r.set(null);
        framework.destroy();
    }

    @AtmosphereAnnotation(MyAnnotation.class)
    public final static class MyAnnotationProcessor implements Processor {

        @Override
        public void handle(AtmosphereFramework framework, Class<?> annotatedClass) {
            MyAnnotation a = annotatedClass.getAnnotation(MyAnnotation.class);
            try {
                framework.addAtmosphereHandler(a.value(), (AtmosphereHandler) annotatedClass.newInstance());
            } catch (InstantiationException e) {
                e.printStackTrace();
            } catch (IllegalAccessException e) {
                e.printStackTrace();
            }
        }
    }

    @Target({ElementType.TYPE})
    @Retention(RetentionPolicy.RUNTIME)
    @Documented
    public static @interface MyAnnotation {
        String value() default "/z";
    }

    @MyAnnotation
    public final static class MyAnnotationImplementation extends AbstractReflectorAtmosphereHandler {

        @Override
        public void onRequest(AtmosphereResource resource) throws IOException {
            resource.suspend();
            r.set(resource);
        }
    }

    @Test
    public void testGet() throws IOException, ServletException {

        AtmosphereRequest request = new AtmosphereRequest.Builder().pathInfo("/z").method("GET").build();
        framework.doCometSupport(request, AtmosphereResponse.newInstance());
        r.get().resume();

        assertNotNull(r.get());
    }
}
