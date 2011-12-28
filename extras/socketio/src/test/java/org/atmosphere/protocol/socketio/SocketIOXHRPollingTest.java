package org.atmosphere.protocol.socketio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;


public class SocketIOXHRPollingTest extends SocketIOTest {
	
	@Test(groups = {"standalone", "default_provider"})
    public void getSessionIDTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// fait un connect
		connect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// fait un connect
		connect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		// maintenant on fait un suspend et attend un ping
		suspend("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "8::");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// maintenant on fait login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username, false);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectGetXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		disconnect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		sendMessage("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, "0:::" , new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("disconnect message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		// maintenant, on met en suspend les deux clients
		suspend("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientXHRPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		suspend("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastXHRPollingTest clientXHRPolling2 message received = " + message);
				Assert.assertNotNull(message);
				
				System.err.println("broadcastXHRPollingTest byte=" + message.getBytes());
				
				if(message.charAt(0)=='\ufffd'){
					System.err.println("Multi-message");
				}
				
				
				Assert.assertEquals(message, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}");
			}
		});
		
		// client 1 va broadcaster un message que le client2 va recevoir
		sendMessage("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}", new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastXHRPollingTest clientXHRPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastDisconnectXHRPollingTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		// maintenant, on met en suspend les deux clients
		suspend("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastDisconnectXHRPollingTest clientXHRPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		suspend("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastDisconnectXHRPollingTest clientXHRPolling2 message received = " + message);
				Assert.assertNotNull(message);
				
				// il est possible de recevoir d'autres messages, comme les users qui restent dans le chat
				Assert.assertTrue(message.contains("5:::{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}"));
			}
		});
		
		// client 2 va recevoir un broadcast de disconnect du user1
		disconnect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
		client2.close();
	}

}
