/*
 * Copyright 2008-2026 Async-IO.org
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

import org.atmosphere.cache.UUIDBroadcasterCache;
import org.atmosphere.client.TrackMessageSizeFilter;
import org.atmosphere.container.BlockingIOCometSupport;
import org.atmosphere.util.ExcludeSessionBroadcaster;

import jakarta.servlet.ServletConfig;
import jakarta.servlet.ServletContext;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public class BroadcastFilterTest {

    private AtmosphereResource ar;
    private Broadcaster broadcaster;
    private AR atmosphereHandler;

    @BeforeEach
    public void setUp() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        HashMap<String, String> m = new HashMap<String, String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE, "true");
        AtmosphereRequest req = new AtmosphereRequestImpl.Builder().headers(m).build();
        ar = new AtmosphereResourceImpl();
        ar.initialize(config,
                broadcaster,
                req,
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);
    }

    @Test
    public void testProgrammaticBroadcastFilter() throws ExecutionException, InterruptedException, ServletException {
        broadcaster.getBroadcasterConfig().addFilter(new Filter());
        broadcaster.broadcast("0").get();

        assertEquals("0foo", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testInitBroadcastFilter() throws ExecutionException, InterruptedException, ServletException {
        AtmosphereConfig config = new AtmosphereFramework()
                .addInitParameter(ApplicationConfig.BROADCAST_FILTER_CLASSES, Filter.class.getName())
                .setAsyncSupport(mock(BlockingIOCometSupport.class))
                .init(new ServletConfig() {
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
                })
                .getAtmosphereConfig();

        DefaultBroadcasterFactory factory = new DefaultBroadcasterFactory();
        factory.configure(DefaultBroadcaster.class, "NEVER", config);
        broadcaster = factory.get(DefaultBroadcaster.class, "test");
        atmosphereHandler = new AR();
        ar = new AtmosphereResourceImpl();
        ar.initialize(config,
                broadcaster,
                mock(AtmosphereRequestImpl.class),
                AtmosphereResponseImpl.newInstance(),
                mock(BlockingIOCometSupport.class),
                atmosphereHandler);

        broadcaster.addAtmosphereResource(ar);

        broadcaster.broadcast("0").get();

        assertEquals("0foo", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testMultipleFilter() throws ExecutionException, InterruptedException {

        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("4"));

        broadcaster.broadcast("0").get();

        assertEquals("01234", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testMultiplePerRequestFilter() throws ExecutionException, InterruptedException {

        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("2"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("3"));
        broadcaster.getBroadcasterConfig().addFilter(new PerRequestFilter("4"));

        broadcaster.broadcast("0").get();

        assertEquals("01234", atmosphereHandler.value.get().toString());
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
        assertEquals("012341234", atmosphereHandler.value.get().toString());
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
        assertEquals("01234abc", atmosphereHandler.value.get().toString());
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
        assertEquals("01a2b3c4", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testAbortFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new AbortFilter(true));
        broadcaster.broadcast("0").get();
        assertEquals("", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testAbortPerFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new AbortFilter(false));
        broadcaster.getBroadcasterConfig().addFilter(new AbortFilter(true));

        broadcaster.broadcast("0").get();
        assertEquals("", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testSkipFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new SlipFilter(true));
        broadcaster.broadcast("0").get();
        assertEquals("0-filter-perFilter", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testSkipPerFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new SlipFilter(true));
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(new SlipFilter(false));
        broadcaster.getBroadcasterConfig().addFilter(new DoNohingFilter("b"));

        broadcaster.broadcast("0").get();
        assertEquals("0-filter-perFilter", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testVoidAtmosphereResouce() throws ExecutionException, InterruptedException {
        broadcaster.removeAtmosphereResource(ar);
        broadcaster.getBroadcasterConfig().addFilter(new VoidAtmosphereResource("1"));
        String s = (String) broadcaster.broadcast("0").get();
        assertEquals("01", s);
    }

    @Test
    public void testMessageLengthFilter() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new TrackMessageSizeFilter());
        broadcaster.broadcast("0").get();
        assertEquals("1|0", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testMultipleMessageLengthFilter() throws ExecutionException, InterruptedException {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE, "true");
        AtmosphereRequest req = new AtmosphereRequestImpl.Builder().headers(m).build();
        for (int i = 0; i < 10; i++) {
            AtmosphereResourceImpl res = new AtmosphereResourceImpl();
            res.initialize(ar.getAtmosphereConfig(),
                    broadcaster,
                    req,
                    AtmosphereResponseImpl.newInstance(),
                    mock(BlockingIOCometSupport.class),
                    new AR());
            broadcaster.addAtmosphereResource(res);
        }

        broadcaster.getBroadcasterConfig().addFilter(new TrackMessageSizeFilter());
        broadcaster.broadcast("0").get();
        assertEquals("1|0", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testSetMultipleMessageLengthFilter() throws ExecutionException, InterruptedException {
        HashMap<String, String> m = new HashMap<String, String>();
        m.put(HeaderConfig.X_ATMOSPHERE_TRACKMESSAGESIZE, "true");
        AtmosphereRequest req = new AtmosphereRequestImpl.Builder().headers(m).build();
        Set<AtmosphereResource> s = new HashSet<AtmosphereResource>();
        s.add(ar);
        AtmosphereConfig cfg = ar.getAtmosphereConfig();
        for (int i = 0; i < 10; i++) {
            ar = new AtmosphereResourceImpl();
            ar.initialize(cfg,
                    broadcaster,
                    req,
                    AtmosphereResponseImpl.newInstance(),
                    mock(BlockingIOCometSupport.class),
                    new AR());
            broadcaster.addAtmosphereResource(ar);
        }

        broadcaster.getBroadcasterConfig().addFilter(new TrackMessageSizeFilter());
        broadcaster.broadcast("0", s).get();
        assertEquals("1|0", atmosphereHandler.value.get().toString());
    }

    @Test
    public void testAbortPerFilterFuture() throws ExecutionException, InterruptedException {
        broadcaster.getBroadcasterConfig().addFilter(new AbortFilter2());

        broadcaster.broadcast("0").get();
        assertEquals("", atmosphereHandler.value.get().toString());
    }

    @Test
    public void clusterFilterPublishesOnlyBroadcastsToEverySubscriber() throws Exception {
        // A broadcast to chosen resources targets this node's connections: a
        // cluster filter would have every other node deliver it to all its
        // subscribers. Local filters still run on it.
        var cluster = new RecordingClusterFilter();
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(cluster);

        broadcaster.broadcast("a", ar).get();
        broadcaster.broadcast("b", Set.of(ar)).get();
        assertEquals("a1b1", atmosphereHandler.value.get().toString());
        assertTrue(cluster.published.isEmpty(), "published to the cluster: " + cluster.published);

        broadcaster.broadcast("c").get();
        assertEquals(List.of("c1"), cluster.published);
        assertEquals("a1b1c1", atmosphereHandler.value.get().toString());
    }

    @Test
    public void cachedBroadcastToChosenResourceReplaysWithoutClusterFilters() throws Exception {
        // A message for one resource, cached while it was away, is replayed to that
        // resource alone on reconnect: running the cluster filters again would have
        // every other node deliver it to all its subscribers.
        var cluster = new RecordingClusterFilter();
        var cache = new UUIDBroadcasterCache();
        cache.configure(broadcaster.getBroadcasterConfig().getAtmosphereConfig());
        broadcaster.getBroadcasterConfig().setBroadcasterCache(cache);
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(cluster);

        broadcaster.removeAtmosphereResource(ar);
        broadcaster.broadcast("private-to-ar", ar).get();
        assertEquals("", atmosphereHandler.value.get().toString());

        broadcaster.addAtmosphereResource(ar);
        assertTrue(atmosphereHandler.value.get().toString().contains("private-to-ar"),
                "cached message not replayed: " + atmosphereHandler.value.get());
        assertTrue(cluster.published.isEmpty(), "published to the cluster: " + cluster.published);
    }

    @Test
    public void applyFiltersOnCachedMessagesSkipsClusterFilters() {
        var cluster = new RecordingClusterFilter();
        broadcaster.getBroadcasterConfig().addFilter(new Filter("1"));
        broadcaster.getBroadcasterConfig().addFilter(cluster);

        var replayed = broadcaster.getBroadcasterConfig().applyFilters(ar, List.of("private-to-ar"));

        assertEquals(List.of("private-to-ar1"), replayed);
        assertTrue(cluster.published.isEmpty(), "published to the cluster: " + cluster.published);
    }

    @Test
    public void excludeSessionBroadcasterKeepsChosenResourcesOffClusterFilters() throws Exception {
        AtmosphereConfig config = new AtmosphereFramework().getAtmosphereConfig();
        var factory = new DefaultBroadcasterFactory();
        factory.configure(ExcludeSessionBroadcaster.class, "NEVER", config);
        config.framework().setBroadcasterFactory(factory);
        Broadcaster b = factory.get(ExcludeSessionBroadcaster.class, "exclude");
        var handler = new AR();
        var target = new AtmosphereResourceImpl();
        target.initialize(config, b, new AtmosphereRequestImpl.Builder().build(),
                AtmosphereResponseImpl.newInstance(), mock(BlockingIOCometSupport.class), handler);
        b.addAtmosphereResource(target);
        var cluster = new RecordingClusterFilter();
        b.getBroadcasterConfig().addFilter(cluster);

        b.broadcast("private-to-target", new HashSet<>(Set.of(target))).get();
        assertEquals("private-to-target", handler.value.get().toString());
        assertTrue(cluster.published.isEmpty(), "published to the cluster: " + cluster.published);

        // Everyone but one resource is the whole audience less that one: the
        // other nodes must still receive it.
        b.broadcast("all-but-other", mock(AtmosphereResource.class)).get();
        assertEquals(List.of("all-but-other"), cluster.published);
        factory.destroy();
    }

    @Test
    public void broadcastToAllExceptRunsClusterFiltersAndSkipsTheExcluded() throws Exception {
        var cluster = new RecordingClusterFilter();
        broadcaster.getBroadcasterConfig().addFilter(cluster);
        var db = (DefaultBroadcaster) broadcaster;

        db.broadcastToAllExcept("everyone-else", ar).get();
        assertEquals("", atmosphereHandler.value.get().toString());
        assertEquals(List.of("everyone-else"), cluster.published,
                "the excluded resource is the only one here, yet other nodes must receive it");

        db.broadcastToAllExcept("to-ar", mock(AtmosphereResource.class)).get();
        assertEquals("to-ar", atmosphereHandler.value.get().toString());
        assertEquals(List.of("everyone-else", "to-ar"), cluster.published);
    }

    private final static class RecordingClusterFilter implements ClusterBroadcastFilter {

        private final List<Object> published = new CopyOnWriteArrayList<>();
        private Broadcaster bc;

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            published.add(message);
            return new BroadcastAction(message);
        }

        @Override
        public void setUri(String name) {
        }

        @Override
        public void setBroadcaster(Broadcaster bc) {
            this.bc = bc;
        }

        @Override
        public Broadcaster getBroadcaster() {
            return bc;
        }

        @Override
        public void init(AtmosphereConfig config) {
        }

        @Override
        public void destroy() {
        }
    }

    private final static class PerRequestFilter implements PerRequestBroadcastFilter {

        String msg;

        public PerRequestFilter(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }
    }

    private final static class VoidAtmosphereResource implements PerRequestBroadcastFilter {

        String msg;

        public VoidAtmosphereResource(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource r, Object originalMessage, Object message) {
            if (r.uuid().equals(VOID_ATMOSPHERE_RESOURCE_UUID)) {
                return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, originalMessage);
            } else {
                return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, "error");
            }
        }
    }

    private final static class AbortFilter implements PerRequestBroadcastFilter {

        final boolean perRequest;

        public AbortFilter(boolean perRequest) {
            this.perRequest = perRequest;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(perRequest ? BroadcastAction.ACTION.ABORT : BroadcastAction.ACTION.CONTINUE, message);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(perRequest ? BroadcastAction.ACTION.ABORT : BroadcastAction.ACTION.CONTINUE, message);
        }
    }

    private final static class AbortFilter2 implements PerRequestBroadcastFilter {

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.ABORT, message);
        }
    }

    private final static class SlipFilter implements PerRequestBroadcastFilter {

        final boolean perRequest;

        public SlipFilter(boolean perRequest) {
            this.perRequest = perRequest;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(perRequest ? BroadcastAction.ACTION.SKIP : BroadcastAction.ACTION.CONTINUE, message + "-filter");
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(perRequest ? BroadcastAction.ACTION.SKIP : BroadcastAction.ACTION.CONTINUE, message + "-perFilter");
        }
    }

    private final static class DoNohingFilter implements PerRequestBroadcastFilter {

        String msg;

        public DoNohingFilter(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }

        @Override
        public BroadcastAction filter(String broadcasterId, AtmosphereResource atmosphereResource, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
        }
    }

    public final static class Filter implements BroadcastFilter {

        final String msg;

        public Filter() {
            this.msg = "foo";
        }

        public Filter(String msg) {
            this.msg = msg;
        }

        @Override
        public BroadcastAction filter(String broadcasterId, Object originalMessage, Object message) {
            return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message + msg);
        }
    }

    public final static class AR implements AtmosphereHandler {

        public AtomicReference<StringBuffer> value = new AtomicReference<StringBuffer>(new StringBuffer());

        @Override
        public void onRequest(AtmosphereResource e) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent e) throws IOException {
            value.get().append(e.getMessage());
        }

        @Override
        public void destroy() {
        }
    }
}
