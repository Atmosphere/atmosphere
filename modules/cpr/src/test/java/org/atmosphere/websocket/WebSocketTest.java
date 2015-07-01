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
package org.atmosphere.websocket;

import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.cpr.AsyncIOInterceptor;
import org.atmosphere.cpr.AtmosphereFramework;
import org.atmosphere.cpr.AtmosphereResponse;
import org.atmosphere.cpr.AtmosphereResponseImpl;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import java.io.IOException;
import java.util.Arrays;
import java.util.Enumeration;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertFalse;

/**
 *
 */
public class WebSocketTest {
    private static final byte[] TEST_DATA = "Hello Atmosphere!".getBytes();
    private AtmosphereFramework framework;

    @BeforeMethod
    public void create() throws Throwable {
        framework = new AtmosphereFramework();
        framework.setAsyncSupport(new BlockingIOCometSupport(framework.getAtmosphereConfig()));
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
    }

    @Test
    public void testTransformWithConcurrency() throws Exception {
        WebSocket ws = new WebSocket(framework.getAtmosphereConfig()) {

            @Override
            public boolean isOpen() {
                return false;
            }

            @Override
            public WebSocket write(String s) throws IOException {
                return null;
            }

            @Override
            public WebSocket write(byte[] b, int offset, int length) throws IOException {
                return null;
            }

            @Override
            public void close() {
            }
        };

        ws.interceptor(new DummyInterceptor(500));
        ws.interceptor(new DummyInterceptor(10));
        
        AtmosphereResponse response1 = AtmosphereResponseImpl.newInstance();
        AtmosphereResponse response2 = AtmosphereResponseImpl.newInstance();
        
        Worker worker1 = new Worker(ws, response1);
        Worker worker2 = new Worker(ws, response2);
        Thread t1 = new Thread(worker1);
        Thread t2 = new Thread(worker2);
        t1.start();
        t2.start();
        
        t1.join(2000);
        t2.join(2000);

        assertFalse(worker1.isCorrupted(), "corrupted");
        assertFalse(worker2.isCorrupted(), "corrupted");
    }

    private static class Worker implements Runnable {
        private boolean corrupted;
        private WebSocket ws;
        private AtmosphereResponse response;
        
        public Worker(WebSocket ws, AtmosphereResponse response) {
            this.ws = ws;
            this.response = response;
        }

        @Override
        public void run() {
            try {
                byte[] b = ws.transform(response, TEST_DATA, 0, TEST_DATA.length);
                corrupted |= !Arrays.equals(TEST_DATA, b);
                b = ws.transform(response, TEST_DATA, 0, TEST_DATA.length);
                corrupted |= !Arrays.equals(TEST_DATA, b);
            } catch (IOException e) {
                // ignore;
            }
        }
        
        public boolean isCorrupted() {
            return corrupted;
        }
    }

    private static class DummyInterceptor implements AsyncIOInterceptor {
        private long time;

        public DummyInterceptor(long time) {
            this.time = time;
        }

        @Override
        public void prePayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        @Override
        public byte[] transformPayload(AtmosphereResponse response, byte[] responseDraft, byte[] data) throws IOException {
            try {
                Thread.sleep(time);
            } catch (InterruptedException e) {
                // ignore
            }
            return responseDraft;
        }

        @Override
        public void postPayload(AtmosphereResponse response, byte[] data, int offset, int length) {
        }

        @Override
        public byte[] error(AtmosphereResponse response, int statusCode, String reasonPhrase) {
            return null;
        }

        @Override
        public void redirect(AtmosphereResponse response, String location) {
        }
    }
}
