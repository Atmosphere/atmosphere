/*
 * Copyright 2008-2020 Async-IO.org
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
package org.atmosphere.interceptor;

import org.atmosphere.cpr.AsyncSupport;
import org.atmosphere.cpr.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereRequest;
import org.atmosphere.cpr.AtmosphereRequestImpl;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.atmosphere.cpr.HeaderConfig;
import org.atmosphere.interceptor.SSEAtmosphereInterceptor;
import org.mockito.Mockito;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletResponse;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Enumeration;

import static org.testng.Assert.assertEquals;

//REVISIT mock more artifacts to speed up the test when only testing the output serialization
public class SSEAtmosphereInterceptorTest {
    private AtmosphereFramework framework;
    private AtmosphereConfig config;

    @BeforeMethod
    public void setup() throws Exception {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(Mockito.mock(AsyncSupport.class));
        framework.init(new ServletConfig() {
            @Override
            public String getServletName() {
                return "void";
            }

            @Override
            public ServletContext getServletContext() {
                return Mockito.mock(ServletContext.class);
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
    }

    @Test
    public void testDataWriter() throws Exception {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ServletResponse resp = Mockito.mock(HttpServletResponse.class);
        Mockito.when(resp.getOutputStream()).thenReturn(new ServletOutputStream() {
            @Override
            public void write(int b) throws IOException {
                baos.write(b);
            }
            @Override
            public void write(byte[] b) throws IOException {
                baos.write(b);
            }
            @Override
            public void write(byte[] b, int off, int len) throws IOException {
                baos.write(b, off, len);
            }
        });

        AtmosphereRequest request = AtmosphereRequestImpl.newInstance();
        request.header(HeaderConfig.X_ATMOSPHERE_TRANSPORT, "SSE");
        AtmosphereResponse response = AtmosphereResponseImpl.newInstance(request);
        response.request(request);
        response.setResponse(resp);
        AtmosphereResourceImpl resource = new AtmosphereResourceImpl();
        resource.initialize(framework.getAtmosphereConfig(),
                framework.getBroadcasterFactory().get(),
                request, response, Mockito.mock(AsyncSupport.class), null);
        resource.suspend();
        
        SSEAtmosphereInterceptor interceptor = new SSEAtmosphereInterceptor();
        interceptor.configure(config);
        interceptor.inspect(resource);
        
        // no newline
        response.write("Good Morning".getBytes());
        assertEquals(baos.toString(), "data:Good Morning\r\n\r\n");
        baos.reset();
        
        // \n
        response.write("Hello World!\nHave a nice day!".getBytes());
        assertEquals(baos.toString(), "data:Hello World!\r\ndata:Have a nice day!\r\n\r\n");
        baos.reset();

        // \r
        response.write("Hello World!\rHave a nice day!".getBytes());
        assertEquals(baos.toString(), "data:Hello World!\r\ndata:Have a nice day!\r\n\r\n");
        baos.reset();

        // \r\n
        response.write("Hello World!\r\nHave a nice day!".getBytes());
        assertEquals(baos.toString(), "data:Hello World!\r\ndata:Have a nice day!\r\n\r\n");
    }
}
