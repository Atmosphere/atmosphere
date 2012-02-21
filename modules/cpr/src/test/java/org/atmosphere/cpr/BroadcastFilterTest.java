/*
 * Copyright 2012 Jean-Francois Arcand
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

import org.atmosphere.container.BlockingIOCometSupport;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.*;
import static org.testng.Assert.assertEquals;

public class BroadcastFilterTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @BeforeMethod
    public void setUp() throws Exception {
        AtmosphereConfig config = mock(AtmosphereConfig.class);
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl(config,
                broadcaster,
                mock(HttpServletRequest.class),
                mock(HttpServletResponse.class),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
    }

    @Test
    public void testMultipleFilter() throws ExecutionException, InterruptedException {

        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("4"));

        broadcaster.broadcast("0").get();

        assertEquals(atmosphereHandler.value.get().toString(), "01234");
    }

    @Test
    public void testMultiplePerRequestFilter() throws ExecutionException, InterruptedException {

        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("4"));

        broadcaster.broadcast("0").get();

        assertEquals(atmosphereHandler.value.get().toString(), "01234");
    }

    @Test
    public void testMultipleMixedFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("4"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("4"));

        broadcaster.broadcast("0").get();
        assertEquals(atmosphereHandler.value.get().toString(), "01234");
    }

    @Test
    public void testMultipleMixedPerRequestFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("a"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("b"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("c"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("4"));

        broadcaster.broadcast("0").get();
        assertEquals(atmosphereHandler.value.get().toString(), "0abc");
    }

    @Test
    public void testMixedPerRequestFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new DoNohingFilter("a"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new DoNohingFilter("b"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new DoNohingFilter("c"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("4"));

        broadcaster.broadcast("0").get();
        assertEquals(atmosphereHandler.value.get().toString(), "01a2b3c4");
    }

    private final static class PerRequestFilter implements PerRequestBroadcastFilter {

        String msg;

        public PerRequestFilter(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }

        @Override
        public BroadcastAction filter(AtmosphereResource<?, ?> atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }
    }

    private final static class DoNohingFilter implements PerRequestBroadcastFilter {

        String msg;

        public DoNohingFilter(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }

        @Override
        public BroadcastAction filter(AtmosphereResource<?, ?> atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, originalMessage);
        }
    }

    private final static class Filter implements BroadcastFilter {

        final String msg;

        public Filter(String msg) {
            this.msg = msg;
        }


        @Override
        public BroadcastAction filter(Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }
    }

    public final static class AR implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

        public AtomicReference<StringBuffer> value = new AtomicReference<StringBuffer>(new StringBuffer());

        @Override
        public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> e) throws IOException {
            value.get().append(e.getMessage());
        }

        @Override
        public void destroy() {
        }
    }
}
