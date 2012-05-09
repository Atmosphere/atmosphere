/*
 * Copyright 2012 Jason Burgess
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
package org.atmosphere.cpr;

import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;

import java.util.Collection;
import java.util.Enumeration;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertNotNull;

public class AtmosphereFrameworkTest {

    @Test
    public void testBroadcasterFactory() throws Exception {
        AtmosphereFramework f = new AtmosphereFramework();
        f.setBroadcasterFactory(new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", f.getAtmosphereConfig()));
        assertNotNull(f.getBroadcasterFactory());
    }

    @Test
    public void testAtmosphereServlet() throws ServletException {
        AtmosphereServlet s = new MyAtmosphereServlet();
        s.init(new ServletConfig() {
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
        assertNotNull(s);
    }

    public class MyAtmosphereServlet extends AtmosphereServlet {

        @Override
        public void init(ServletConfig config) throws ServletException {

            framework.setBroadcasterFactory(new MyBroadcasterFactory());
            super.init(config);

        }

    }

    public final static class MyBroadcasterFactory extends BroadcasterFactory {

        @Override
        public Broadcaster get() {
            return null;
        }

        @Override
        public Broadcaster get(Object id) {
            return null;
        }

        @Override
        public Broadcaster get(Class<? extends Broadcaster> c, Object id) {
            return null;
        }

        @Override
        public void destroy() {

        }

        @Override
        public boolean add(Broadcaster b, Object id) {
            return false;
        }

        @Override
        public boolean remove(Broadcaster b, Object id) {
            return false;
        }

        @Override
        public Broadcaster lookup(Class<? extends Broadcaster> c, Object id) {
            return null;
        }

        @Override
        public Broadcaster lookup(Class<? extends Broadcaster> c, Object id, boolean createIfNull) {
            return null;
        }

        @Override
        public Broadcaster lookup(Object id) {
            return null;
        }

        @Override
        public Broadcaster lookup(Object id, boolean createIfNull) {
            return null;
        }

        @Override
        public void removeAllAtmosphereResource(AtmosphereResource r) {

        }

        @Override
        public boolean remove(Object id) {
            return false;
        }

        @Override
        public Collection<Broadcaster> lookupAll() {
            return null;
        }
    }
}
