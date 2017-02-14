package org.atmosphere.jersey.tests;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.Response;
import junit.framework.Assert;
import org.testng.annotations.Test;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.testng.Assert.fail;

/**
 * @author dkuffner
 */
public class JsonMessagesResourceTest extends BaseJettyTest {
    @Override
    String getUrlTarget(int port) {
        return "http://127.0.0.1:" + port + "/jsonMessages";
    }

    @Test(timeOut = 60000 * 10, enabled = true)
    public void testManyMessages() throws IOException, InterruptedException {
        logger.info("Running testConcurrentAndEmptyDestroyPolicy");

        AsyncHttpClient httpClient = new AsyncHttpClient();

        final AtomicBoolean run = new AtomicBoolean(true);

        while (run.get()) {
            final CountDownLatch suspended2 = new CountDownLatch(1);
            final AtomicReference<String> failedMessage = new AtomicReference<String>();

            httpClient.prepareGet(urlTarget).execute(new AsyncCompletionHandler<Response>() {
                @Override
                public Response onCompleted(Response response) throws Exception {
                    if (response.getResponseBody().equals("done")) {
                        run.set(false);
                    } else if (response.getResponseBody().contains("}{")) {
                        failedMessage.set(response.getResponseBody());
                    }
                    suspended2.countDown();
                    return response;
                }
            });

            Assert.assertTrue(suspended2.await(10, TimeUnit.MINUTES));

            String message = failedMessage.get();
            if (message != null) {
                fail("test fails because returned json is invalid, JSON contains '}{' : \n" + message);
            }
        }

        httpClient.close();
    }
}
