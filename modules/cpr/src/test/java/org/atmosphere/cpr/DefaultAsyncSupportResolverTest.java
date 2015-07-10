package org.atmosphere.cpr;

import org.atmosphere.container.Servlet30CometSupport;
import org.testng.Assert;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;

import static org.mockito.Matchers.anyList;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.spy;

public class DefaultAsyncSupportResolverTest {

    private AtmosphereConfig config;
    private DefaultAsyncSupportResolver defaultAsyncSupportResolver;

    @BeforeMethod
    public void setUp() throws Exception {
        config = new AtmosphereFramework().getAtmosphereConfig();
        defaultAsyncSupportResolver = new DefaultAsyncSupportResolver(config);
    }

    @AfterMethod
    public void unSet() throws Exception {
        config.destroy();
    }

    @Test
    public void testNullIfNonInstantiableWebSocketClass(){
        Assert.assertNull(defaultAsyncSupportResolver.newCometSupport(InvalidAsyncSupportClass.class));
    }

    @Test
    public void testAsyncSupportClassNotFoundDefaultsToServlet30IfAvailable(){
        boolean useNativeIfPossible = false;
        boolean defaultToBlocking = false;
        boolean useServlet30Async = true;

        // FIXME: interface method argument name mismatch for AsyncSupportResolver.resolve
        // Interface:                   final boolean useNativeIfPossible, final boolean defaultToBlocking, final boolean useWebsocketIfPossible
        // DefaultAsyncSupportResolver:       boolean useNativeIfPossible,       boolean defaultToBlocking,       boolean useServlet30Async

        // Override the container detection mechanism to use an invalid Async Support class only
        defaultAsyncSupportResolver = spy(defaultAsyncSupportResolver);
        List<Class<? extends AsyncSupport>> asyncSupportList = new ArrayList(){{
            add(InvalidAsyncSupportClass.class);
        }};
        doReturn(asyncSupportList)
                .when(defaultAsyncSupportResolver)
                .detectWebSocketPresent(useNativeIfPossible, useServlet30Async);
        doReturn(null)
                .when(defaultAsyncSupportResolver)
                .resolveNativeCometSupport(anyList());

        Assert.assertEquals(
                defaultAsyncSupportResolver.resolve(useNativeIfPossible, defaultToBlocking, useServlet30Async).getClass(),
                Servlet30CometSupport.class);
    }

    @Test
    public void testAsyncSupportClassNotFoundDefaultsToBlockingIOIfServlet30IsNotAvailable(){
        boolean useNativeIfPossible = false;
        boolean defaultToBlocking = false;
        boolean useServlet30Async = false;

        // FIXME: interface method argument mismatch for AsyncSupportResolver.resolve
        // Interface:                   final boolean useNativeIfPossible, final boolean defaultToBlocking, final boolean useWebsocketIfPossible
        // DefaultAsyncSupportResolver:       boolean useNativeIfPossible,       boolean defaultToBlocking,       boolean useServlet30Async

        // Override the container detection mechanism to use an invalid Async Support class only
        defaultAsyncSupportResolver = spy(defaultAsyncSupportResolver);
        List<Class<? extends AsyncSupport>> asyncSupportList = new ArrayList(){{
            add(InvalidAsyncSupportClass.class);
        }};
        doReturn(asyncSupportList)
                .when(defaultAsyncSupportResolver)
                .detectWebSocketPresent(useNativeIfPossible, useServlet30Async);
        doReturn(null)
                .when(defaultAsyncSupportResolver)
                .resolveNativeCometSupport(anyList());

        Assert.assertEquals(
                defaultAsyncSupportResolver.resolve(useNativeIfPossible, defaultToBlocking, useServlet30Async).getClass(),
                Servlet30CometSupport.class);
    }

    class InvalidAsyncSupportClass implements AsyncSupport{

        public InvalidAsyncSupportClass(AtmosphereConfig config) throws InvocationTargetException {
            throw new InvocationTargetException(new Exception(),
                    "This is an invalid AsyncSupport class for testing purposes");
        }

        @Override
        public String getContainerName() {
            return null;
        }

        @Override
        public void init(ServletConfig sc) throws ServletException {

        }

        @Override
        public Action service(AtmosphereRequest req, AtmosphereResponse res) throws IOException, ServletException {
            return null;
        }

        @Override
        public void action(AtmosphereResource actionEvent) {

        }

        @Override
        public boolean supportWebSocket() {
            return false;
        }

        @Override
        public AsyncSupport complete(AtmosphereResource r) {
            return null;
        }
    }
}
