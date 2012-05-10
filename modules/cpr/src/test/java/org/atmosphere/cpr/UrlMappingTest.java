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
package org.atmosphere.cpr;

import org.atmosphere.cpr.AtmosphereFramework.AtmosphereHandlerWrapper;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.*;

public class UrlMappingTest {

    private AtmosphereFramework framework;
    private AtmosphereConfig config;
    private AsynchronousProcessor processor;
    private final AtmosphereHandler handler = mock(AtmosphereHandler.class);

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
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
        config = framework.getAtmosphereConfig();
        processor = new AsynchronousProcessor(config) {
            @Override
            public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
                return new Action(Action.TYPE.CREATED);
            }
        };
    }

    @Test
    public void mappingTest1() throws ServletException {
        framework.addAtmosphereHandler("/a/", handler);
        framework.addAtmosphereHandler("/a", handler);
        framework.addAtmosphereHandler("/ab/", handler);
        framework.addAtmosphereHandler("/abc/", handler);
        framework.addAtmosphereHandler("/a/b", handler);
        framework.addAtmosphereHandler("/a/b/", handler);
        framework.addAtmosphereHandler("/a/b/c", handler);
        framework.addAtmosphereHandler("/", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/ab/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/abc/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/c").build();
        try {
            processor.map(r);
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingTestServletPath() throws ServletException {
        framework.addAtmosphereHandler("/foo/a/", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingSubWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/foo/*", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c/d/////").build();
        try {
            assertNotNull(processor.map(r));
        } catch (AtmosphereMappingException e) {
            assertNotNull(e);
        }
    }

    @Test
    public void mappingWildcardPath() throws ServletException {
        framework.addAtmosphereHandler("/*", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().servletPath("/foo").pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/b/c/d/////").build();
        assertNotNull(processor.map(r));
    }

    @Test
    public void mappingTestTraillingHandler() throws ServletException {
        // servlet-mapping : /a/*
        framework.addAtmosphereHandler("/a", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/a/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/a/1").build();
        assertNotNull(processor.map(r));
    }
    
    @Test
    public void mappingTestFailed() throws ServletException {
        // servlet-mapping : /a/*
        framework.addAtmosphereHandler("/ChatAtmosphereHandler", handler);

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/").build();
        assertNotNull(processor.map(r));

        r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/1").build();
        assertNotNull(processor.map(r));
        
        r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/1/xhr-polling").build();
        assertNotNull(processor.map(r));
        
        r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/1/xhr-polling/").build();
        assertNotNull(processor.map(r));
        
        r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/1/xhr-polling/ta_edv9TEfsX908fxx1-").build();
        assertNotNull(processor.map(r));
        
        r = new AtmosphereRequest.Builder().pathInfo("/ChatAtmosphereHandler/1/xhr-polling/00-6cgf5R4KwIWIiODXv").build();
        assertNotNull(processor.map(r));
        
        
    }
    
    @Test
    public void mappingTestFull() throws ServletException {
    	final AtmosphereHandler red = mock(AtmosphereHandler.class);
    	final AtmosphereHandler blue = mock(AtmosphereHandler.class);
    	final AtmosphereHandler redred = mock(AtmosphereHandler.class);
    	final AtmosphereHandler redblue = mock(AtmosphereHandler.class);
    	final AtmosphereHandler blueblue = mock(AtmosphereHandler.class);
    	
        framework.addAtmosphereHandler("/", handler);
        framework.addAtmosphereHandler("/red", red);
        framework.addAtmosphereHandler("/red/red", redred);
        framework.addAtmosphereHandler("/red/blue", redblue);
        framework.addAtmosphereHandler("/blue", blue);
        framework.addAtmosphereHandler("/blue/blue", blueblue);

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/").build();
        assertEquals(processor.map(r).atmosphereHandler, handler);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red").build();
        assertEquals(processor.map(r).atmosphereHandler, red);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/1").build();
        assertEquals(processor.map(r).atmosphereHandler, red);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/red").build();
        assertEquals(processor.map(r).atmosphereHandler, redred);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/red/1").build();
        assertEquals(processor.map(r).atmosphereHandler, redred);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/blue/").build();
        assertEquals(processor.map(r), redblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/blue/1").build();
        assertEquals(processor.map(r).atmosphereHandler, redblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/blue").build();
        assertEquals(processor.map(r).atmosphereHandler, blue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/blue/blue").build();
        assertEquals(processor.map(r).atmosphereHandler, blueblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/green").build();
        assertNull(processor.map(r));
    }
   
    @Test
    public void findMapping() throws Exception {
    	
    	final AtmosphereHandler red = mock(AtmosphereHandler.class);
    	final AtmosphereHandler blue = mock(AtmosphereHandler.class);
    	final AtmosphereHandler redred = mock(AtmosphereHandler.class);
    	final AtmosphereHandler redblue = mock(AtmosphereHandler.class);
    	final AtmosphereHandler blueblue = mock(AtmosphereHandler.class);
    	
        framework.addAtmosphereHandler("/", handler);
        framework.addAtmosphereHandler("/red", red);
        framework.addAtmosphereHandler("/red/red", redred);
        framework.addAtmosphereHandler("/red/blue", redblue);
        framework.addAtmosphereHandler("/blue", blue);
        framework.addAtmosphereHandler("/blue/blue", blueblue);

        AtmosphereRequest r = new AtmosphereRequest.Builder().pathInfo("/").build();
        assertEquals(processor.map(r).atmosphereHandler, handler);
    	
        String path = "/red/red/1";
        
        // try exact match
        AtmosphereHandlerWrapper tmp = framework.atmosphereHandlers.get(path);
        
        if(tmp==null){
        	PathMap p = new PathMap();

            p.put("/abs/path", "1");
            p.put("/abs/path/longer", "2");
            p.put("/animal/bird/*", "3");
            p.put("/animal/fish/*", "4");
            p.put("/animal/*", "5");
            p.put("*.tar.gz", "6");
            p.put("*.gz", "7");
            p.put("/", "8");
            p.put("/XXX:/YYY", "9");
            p.put("", "10");
            
            String[][] tests = {
                    { "/abs/path", "1"},
                    { "/abs/path/xxx", "8"},
                    { "/abs/pith", "8"},
                    { "/abs/path/longer", "2"},
                    { "/abs/path/", "8"},
                    { "/abs/path/xxx", "8"},
                    { "/animal/bird/eagle/bald", "3"},
                    { "/animal/fish/shark/grey", "4"},
                    { "/animal/insect/bug", "5"},
                    { "/animal", "5"},
                    { "/animal/", "5"},
                    { "/animal/x", "5"},
                    { "/animal/*", "5"},
                    { "/suffix/path.tar.gz", "6"},
                    { "/suffix/path.gz", "7"},
                    { "/animal/path.gz", "5"},
                    { "/Other/path", "8"},};

				    for (String[] test : tests)
				    {
				    	assertEquals(test[1], p.getMatch(test[0]).getValue());
				    }
				    
        }
        
        PathMap p = new PathMap();
        
        p.put("/", handler);
        p.put("/red", red);
        p.put("/red/*", red);
        p.put("/red/red/*", redred);
        p.put("/red/blue/*", redblue);
        p.put("/blue/*", blue);
        p.put("/blue/blue/*", blueblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), handler);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), red);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/1").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), red);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/red").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), redred);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/red/1").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), redred);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/blue/").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), redblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/red/blue/1").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), redblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/blue").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), blue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/blue/blue").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), blueblue);
        
        r = new AtmosphereRequest.Builder().pathInfo("/green").build();
        assertEquals(p.getMatch(r.getPathInfo()).getValue(), handler);
        
        //enleve le default handler
        p.remove("/");
        r = new AtmosphereRequest.Builder().pathInfo("/green").build();
        assertNull(p.getMatch(r.getPathInfo()));
    }
}
