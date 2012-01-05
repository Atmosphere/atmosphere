package org.atmosphere.protocol.socketio;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.websocket.WebSocket;


public class SocketIOWebSocketTest extends SocketIOTest {
	
	@Test(groups = {"standalone", "default_provider"})
    public void getSessionIDTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		WebSocketWrapper wrapper = new WebSocketWrapper();
    	wrapper.setListener(new WebSocketResponseListener(wrapper) {
			
			@Override
			public void notify(String message) {
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		// fait un connect et ca suspend
		WebSocket websocket = connectWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, wrapper).websocket;
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final CountDownLatch l = new CountDownLatch(2);
		
		WebSocketWrapper wrapper = new WebSocketWrapper();
    	wrapper.setListener(new WebSocketResponseListener(wrapper) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				l.countDown();
				if(l.getCount()==1){
					Assert.assertNotNull(message);
					Assert.assertEquals(message, "1::");
				} else if(l.getCount()==0){
					Assert.assertNotNull(message);
					Assert.assertEquals(message, "2::");
				} else {
					Assert.fail();
				}
			}
		});
		
		// fait un connect et ca suspend
		WebSocket websocket = connectWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, wrapper).websocket;
		
		if (!l.await(45, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// maintenant on fait login
		WebSocket websocket2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username, false).websocket;
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username2, true).websocket;
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectGetWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		disconnect("clientWebSocket1", client, GET_SESSION_URL+"websocket/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostWebSocketTest() throws Throwable {
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		final CountDownLatch l = new CountDownLatch(1);
		
		sendMessage(websocket, "0:::");
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastWebSocketTest() throws Throwable {
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		WebSocketWrapper webSocketWrapper1 = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test2_" + System.currentTimeMillis();
		
		System.err.println("");
		System.err.println("");
		System.err.println("");
		System.err.println("");
		System.err.println("");
		
		
		// maintenant on fait login
		WebSocketWrapper webSocketWrapper2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username2, true);
		
		
		final CountDownLatch lWebSocket1 = new CountDownLatch(1);
		final CountDownLatch lWebSocket2 = new CountDownLatch(1);
		
		webSocketWrapper1.setListener(new WebSocketResponseListener(webSocketWrapper1) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				lWebSocket1.countDown();
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
				
			}
		});
		
		webSocketWrapper2.setListener(new WebSocketResponseListener(webSocketWrapper2) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				lWebSocket2.countDown();
				log.info("clientWebSocket2 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}");
				
			}
		});
		
		System.err.println("");
		System.err.println("");
		System.err.println("");
		System.err.println("");
		System.err.println("");
		
		// client 1 va broadcaster un message que le client2 va recevoir
		sendMessage(webSocketWrapper1.websocket, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}");
		
		if (!lWebSocket1.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		if (!lWebSocket2.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
		client2.close();
		
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastDisconnectWebSocketTest() throws Throwable {
		Assert.fail();
		/*
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// maintenant on fait login
		login("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username2, true);
		
		// maintenant, on met en suspend les deux clients
		suspendWS("clientWebSocket1", client, GET_SESSION_URL+"websocket/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		suspendWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientWebSocket2 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "5:::{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}");
			}
		});
		
		// client 2 va recevoir un broadcast de disconnect du user1
		disconnect("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1);
		
		client.close();
		client2.close();
		*/
	}

}
