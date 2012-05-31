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
package org.atmosphere.socketio;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.atmosphere.socketio.protocol1.transport.SocketIOPacketImpl;
import org.testng.Assert;
import org.testng.annotations.Test;

import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.websocket.WebSocket;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOWebSocketTest extends SocketIOTest {
	
	@Test(groups = {"standalone", "default_provider"})
    public void getSessionIDTest() throws Throwable {
		System.err.println("\n\nTEST getSessionIDTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST connectWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		WebSocketWrapper wrapper = new WebSocketWrapper();
    	wrapper.setListener(new WebSocketResponseListener(wrapper) {
			
			@Override
			public void notify(String message) {
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				
				List<SocketIOPacketImpl> messages = null;
				try {
					messages = SocketIOPacketImpl.parse(message);
				} catch (SocketIOException e1) {
					e1.printStackTrace();
				}
				
				if(messages==null || messages.isEmpty()){
					return;
				}
				
				for (SocketIOPacketImpl msg : messages) {
					
					String data = msg.toString();
					switch(msg.getFrameType()){
						case CONNECT : 
							Assert.assertEquals(message, "1::");
							l.countDown();
							break;
						default:
							
					}
					
				}
			}
		});
		
		//connect and suspend
		WebSocket websocket = connectWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, wrapper).websocket;
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST idleWebSocketTest\n\n");
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
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				
				List<SocketIOPacketImpl> messages = null;
				try {
					messages = SocketIOPacketImpl.parse(message);
				} catch (SocketIOException e1) {
					e1.printStackTrace();
				}
				
				if(messages==null || messages.isEmpty()){
					return;
				}
				
				for (SocketIOPacketImpl msg : messages) {
					
					String data = msg.toString();
					switch(msg.getFrameType()){
						case CONNECT : 
							Assert.assertEquals(message, "1::");
							l.countDown();
							break;
						case HEARTBEAT : 
							Assert.assertEquals(message, "2::");
							l.countDown();
						default:
							
					}
					
				}
			}
		});
		
		// connect and suspend
		WebSocket websocket = connectWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, wrapper).websocket;
		
		if (!l.await(45, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST loginWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST loginDuplicateUsernameWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// login
		WebSocket websocket2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username, false).websocket;
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST multipleLoginWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		WebSocket websocket = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true).websocket;
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		WebSocket websocket2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username2, true).websocket;
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST disconnectPostWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		final CountDownLatch l = new CountDownLatch(1);
		
		// login
		WebSocketWrapper webSocketWrapper1 = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true);
		
		webSocketWrapper1.setListener(new WebSocketResponseListener(webSocketWrapper1) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				
				List<SocketIOPacketImpl> messages = null;
				try {
					messages = SocketIOPacketImpl.parse(message);
				} catch (SocketIOException e1) {
					e1.printStackTrace();
				}
				
				if(messages==null || messages.isEmpty()){
					return;
				}
				
				for (SocketIOPacketImpl msg : messages) {
					
					String data = msg.toString();
					switch(msg.getFrameType()){
						case CONNECT : 
							Assert.assertEquals(message, "1::");
							l.countDown();
							break;
						default:
							
					}
					
				}
				
			}
		});
		
		sendMessage(webSocketWrapper1.websocket, "0:::");
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST broadcastWebSocketTest\n\n");
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		WebSocketWrapper webSocketWrapper1 = loginWS("clientWebSocket1", client, WS_GET_SESSION_URL+"websocket/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test2_" + System.currentTimeMillis();
		
		// login
		WebSocketWrapper webSocketWrapper2 = loginWS("clientWebSocket2", client2, WS_GET_SESSION_URL+"websocket/" + sessionid2, username2, true);
		
		
		final CountDownLatch lWebSocket2 = new CountDownLatch(1);
		
		webSocketWrapper1.setListener(new WebSocketResponseListener(webSocketWrapper1) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				log.info("clientWebSocket1 message received = " + message);
				Assert.assertNotNull(message);
				
				List<SocketIOPacketImpl> messages = null;
				try {
					messages = SocketIOPacketImpl.parse(message);
				} catch (SocketIOException e1) {
					e1.printStackTrace();
				}
				
				if(messages==null || messages.isEmpty()){
					return;
				}
				
				for (SocketIOPacketImpl msg : messages) {
					
					String data = msg.toString();
					switch(msg.getFrameType()){
						case CONNECT : 
							Assert.assertEquals(message, "1::");
							break;
						default:
							
					}
					
				}
				
			}
		});
		
		webSocketWrapper2.setListener(new WebSocketResponseListener(webSocketWrapper2) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				log.info("clientWebSocket2 message received = " + message);
				Assert.assertNotNull(message);
				
				List<SocketIOPacketImpl> messages = null;
				try {
					messages = SocketIOPacketImpl.parse(message);
				} catch (SocketIOException e1) {
					e1.printStackTrace();
				}
				
				if(messages==null || messages.isEmpty()){
					return;
				}
				
				for (SocketIOPacketImpl msg : messages) {
					
					String data = msg.toString();
					switch(msg.getFrameType()){
						case EVENT : 
							// message from another user
							if(data.contains("5:::{\"name\":\"user message\",\"args\":[\"") && data.contains("message1 from " + username + "\"]}")){
								lWebSocket2.countDown();
							} 
							break;
						default:
							
					}
					
				}
				
			}
		});
		
		// client 1 send a message that will be received by client 2
		sendMessage(webSocketWrapper1.websocket, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}");
		
		if (!lWebSocket2.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out WS 2");
        }
		
		client.close();
		client2.close();
		
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastDisconnectWebSocketTest() throws Throwable {
		System.err.println("\n\nTEST broadcastDisconnectWebSocketTest\n\n");
		Assert.fail();
	}

}
