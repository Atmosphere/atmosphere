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
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// fait un connect
		connectJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// fait un connect
		connectJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		// maintenant on fait un suspend et attend un ping
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
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1 +"?i=0", username, true);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// maintenant on fait login
		login("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username, false);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectGetJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		disconnect("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostJSONPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
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
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		// maintenant, on met en suspend les deux clients
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
		
		// client 1 va broadcaster un message que le client2 va recevoir
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
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		loginJSONP("clientJSONPolling2", client2, GET_SESSION_URL+"jsonp-polling/" + sessionid2, username2, true);
		
		// maintenant, on met en suspend les deux clients
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
				
				// il est possible de recevoir d'autres messages, comme les users qui restent dans le chat
				Assert.assertTrue(message.contains("io.j[0](\"5:::{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}"));
			}
		});
		
		// client 2 va recevoir un broadcast de disconnect du user1
		disconnect("clientJSONPolling1", client, GET_SESSION_URL+"jsonp-polling/" + sessionid1);
		
		client.close();
		client2.close();
	}

}
