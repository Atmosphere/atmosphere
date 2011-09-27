package org.atmosphere.tests;

import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.mockito.Mockito.mock;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertTrue;

public class AtmosphereServletTest {

    @Test
    public void destroy() throws Exception {
        Broadcaster broadcaster = mock(Broadcaster.class);

        AtmosphereServlet servlet = new AtmosphereServlet();
        Handler handler = new Handler();
        Handler handler2 = new Handler();

        assertFalse(handler.isDestroyed());
        assertFalse(handler2.isDestroyed());

        servlet.addAtmosphereHandler("/test", handler, broadcaster);
        servlet.addAtmosphereHandler("/test2", handler2, broadcaster);

        servlet.destroy();

        assertTrue(handler.isDestroyed());
        assertTrue(handler2.isDestroyed());
    }

    private static class Handler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

        private final AtomicBoolean destroyed = new AtomicBoolean(false);

        @Override
        public void destroy() {
            destroyed.set(true);
        }

        @Override
        public void onRequest(AtmosphereResource<HttpServletRequest, HttpServletResponse> resource) throws IOException {
        }

        @Override
        public void onStateChange(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) throws IOException {
        }

        public boolean isDestroyed() {
            return destroyed.get();
        }
    }
}
