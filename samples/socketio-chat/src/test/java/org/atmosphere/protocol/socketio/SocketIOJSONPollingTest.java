/*
 * Copyright 2012 Sebastien Dionne
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
package org.atmosphere.protocol.socketio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOJSONPollingTest extends SocketIOTest {
	
	@Test(groups = {"standalone", "default_provider"})
    public void getSessionIDTest() throws Throwable {
		System.err.println("\n\nTEST getSessionIDTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST connectJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// connect
		connectJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST idleJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// connect
		connectJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		// suspend and waiting for a ping
		suspend("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"8::\");");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST loginJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1 +"?i=0", username, true);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST loginDuplicateUsernameJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		//login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// login
		login("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username, false);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST multipleLoginJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectGetJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST disconnectGetJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		disconnectJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST disconnectPostJSONPollingTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		sendMessage("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, "0:::" , new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("disconnect message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST broadcastJSONPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		// suspend both clients
		suspend("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientJSONPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		suspend("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientJSONPolling2 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}\");");
			}
		});
		
		// client 1 send a message that will be received by client 2
		sendMessage("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, "5:::{\\\"name\\\":\\\"user message\\\",\\\"args\\\":[\\\"message1 from " + username + "\\\"]}", new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientJSONPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastDisconnectJSONPollingTest() throws Throwable {
		System.err.println("\n\nTEST broadcastDisconnectJSONPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		//suspend both clients
		suspend("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientJSONPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		suspend("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientJSONPolling2 message received = " + message);
				Assert.assertNotNull(message);
				
				Assert.assertTrue(message.contains("io.j[0](\"5:::{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}"));
			}
		});
		
		// client 2 will received a disconnect notification from client 1
		disconnect("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
		client2.close();
	}

}
