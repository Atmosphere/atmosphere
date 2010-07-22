package org.atmosphere.tests;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import org.apache.log4j.BasicConfigurator;
import org.atmosphere.container.JettyCometSupport;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.cpr.Meteor;
import org.atmosphere.cpr.MeteorServlet;
import org.mortbay.jetty.Server;
import org.mortbay.jetty.servlet.Context;
import org.mortbay.jetty.servlet.ServletHolder;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.ServerSocket;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertFalse;
import static org.testng.Assert.assertNotNull;
import static org.testng.Assert.assertTrue;
import static org.testng.Assert.fail;

public class BroadcasterScopeTest {

    protected MeteorServlet atmoServlet;
    protected final static String ROOT = "/*";
    protected String urlTarget;
    protected Server server;
    protected Context root;
    private static CountDownLatch servletLatch;
    private static final AtomicReference<String> broadcasterId = new AtomicReference<String>();

    public static class TestHelper {

        public static int getEnvVariable(final String varName, int defaultValue) {
            if (null == varName) {
                return defaultValue;
            }
            String varValue = System.getenv(varName);
            if (null != varValue) {
                try {
                    return Integer.parseInt(varValue);
                } catch (NumberFormatException e) {
                    // will return default value bellow
                }
            }
            return defaultValue;
        }
    }

    protected int findFreePort() throws IOException {
        ServerSocket socket = null;

        try {
            socket = new ServerSocket(0);

            return socket.getLocalPort();
        }
        finally {
            if (socket != null) {
                socket.close();
            }
        }
    }

    public static class Meteor1 extends HttpServlet {
        @Override
        public void doGet(HttpServletRequest req, HttpServletResponse res) throws IOException {
            final Meteor m = Meteor.build(req);
            m.getBroadcaster().setScope(Broadcaster.SCOPE.REQUEST);
            req.getSession().setAttribute("meteor", m);
            
            m.suspend(5000, false);
            broadcasterId.set(m.getBroadcaster().getID());

            res.getOutputStream().write("resume".getBytes());
            m.addListener(new AtmosphereResourceEventListener(){

                @Override
                public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event){
                }

                @Override
                public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
                }

                @Override
                public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
                }

                @Override
                public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
                    event.getResource().getRequest().setAttribute(AtmosphereServlet.RESUME_ON_BROADCAST, "true");
                }
            });

            if (servletLatch != null) {
                servletLatch.countDown();
            }
        }
    }

    @BeforeMethod(alwaysRun = true)
    public void startServer() throws Exception {

        int port = BaseTest.TestHelper.getEnvVariable("ATMOSPHERE_HTTP_PORT", findFreePort());
        urlTarget = "http://127.0.0.1:" + port + "/invoke";

        server = new Server(port);
        root = new Context(server, "/", Context.SESSIONS);
        atmoServlet = new MeteorServlet();
        atmoServlet.addInitParameter("org.atmosphere.servlet", Meteor1.class.getName());
        configureCometSupport();
        root.addServlet(new ServletHolder(atmoServlet), ROOT);
        server.start();
    }

    public void configureCometSupport() {
        atmoServlet.setCometSupport(new JettyCometSupport(atmoServlet.getAtmosphereConfig()));
    }

    @AfterMethod(alwaysRun = true)
    public void unsetAtmosphereHandler() throws Exception {
        atmoServlet.destroy();
        BasicConfigurator.resetConfiguration();
        server.stop();
        server = null;
    }

    @Test(timeOut = 60000)
    public void testBroadcasterScope() {
        System.out.println("Running testBroadcasterScope");
        final CountDownLatch latch = new CountDownLatch(1);
        final CountDownLatch latch2 = new CountDownLatch(1);

        servletLatch = new CountDownLatch(1);
        AsyncHttpClient c = new AsyncHttpClient();
        try {
            long currentTime = System.currentTimeMillis();
            final AtomicReference<Response> r = new AtomicReference();
            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    r.set(response);
                    latch.countDown();
                    return response;
                }
            });

            servletLatch.await();

            String id = broadcasterId.get();

            Broadcaster b = BroadcasterFactory.getDefault().lookup(DefaultBroadcaster.class, id);
            assertNotNull(b);
            b.broadcast("resume").get();

            try {
                latch.await();
            } catch (InterruptedException e) {
                fail(e.getMessage());
                return;
            }

            long time = System.currentTimeMillis() - currentTime;
            if (time < 5000) {
                assertTrue(true);
            } else {
                assertFalse(false);
            }
            assertNotNull(r.get());
            assertEquals(r.get().getStatusCode(), 200);
            String resume = r.get().getResponseBody();
            assertEquals(resume, "resumeresume");

            c.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Response>() {

                @Override
                public Response onCompleted(Response response) throws Exception {
                    r.set(response);
                    latch2.countDown();
                    return response;
                }
            });

            try {
                latch2.await();
            } catch (InterruptedException e) {
                fail(e.getMessage());
                return;
            }

            assertFalse(id.equals(broadcasterId.get()));
        } catch (Exception e) {
            e.printStackTrace();
            fail(e.getMessage());
        }
        c.close();
    }

}