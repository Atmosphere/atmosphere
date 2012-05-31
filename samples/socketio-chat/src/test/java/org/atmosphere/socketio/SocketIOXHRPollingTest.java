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

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public class SocketIOXHRPollingTest extends SocketIOTest {
	
	@Test(groups = {"standalone", "default_provider"})
    public void getSessionIDTest() throws Throwable {
		System.err.println("\n\nTEST getSessionIDTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		client.close();
		
		Assert.assertNotNull(sessionid1);
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void connectXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST connectXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// connect
		connect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void idleXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST idleXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		// connect
		connect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		//suspend and waiting for a ping
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
		System.err.println("\n\nTEST loginXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void loginDuplicateUsernameXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST loginDuplicateUsernameXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		// login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username, false);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void multipleLoginXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST multipleLoginXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectGetXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST disconnectGetXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		disconnect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void disconnectPostXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST disconnectPostXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final CountDownLatch l = new CountDownLatch(1);
		
		sendMessage("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, "0:::" , new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("disconnect message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, SocketIOPacketImpl.POST_RESPONSE);
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		client.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastXHRPollingTest() throws Throwable {
		System.err.println("\n\nTEST broadcastXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		// suspend both clients
		suspend("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("clientXHRPolling1 message received = " + message);
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
							
							break;
						default:
							try {
								newSuspendConnection("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, this);
							} catch (Throwable e) {
								e.printStackTrace();
							}
							
					}
					
				}
			}
		});
		
		final CountDownLatch latchGet = new CountDownLatch(1);
		
		suspend("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastXHRPollingTest clientXHRPolling2 message received = " + message);
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
								latchGet.countDown();
							} 
							break;
						default:
							
					}
					
				}
				
				if(latchGet.getCount()>0){
					try {
						newSuspendConnection("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, this);
					} catch (Throwable e) {
						e.printStackTrace();
					}
				}
				
			}
		});
		
		// client 1 send a message that will be received by client 2
		sendMessage("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, "5:::{\"name\":\"user message\",\"args\":[\"message1 from " + username + "\"]}", new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("broadcastXHRPollingTest clientXHRPolling1 message received = " + message);
				Assert.assertNotNull(message);
				Assert.assertEquals(message, SocketIOPacketImpl.POST_RESPONSE);
			}
		});
		
		if (!latchGet.await(45, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out broadcastXHRPollingTest");
        }
		
		client.close();
		client2.close();
	}
	
	@Test(groups = {"standalone", "default_provider"})
    public void broadcastDisconnectXHRPollingTest() throws Throwable {
		
		System.err.println("\n\nTEST broadcastDisconnectXHRPollingTest\n\n");
		
		final AsyncHttpClient client = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid1 = getSessionID(client, GET_SESSION_URL);
		
		final String username = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1, username, true);
		
		final AsyncHttpClient client2 = getAsyncHttpClient(new AsyncHttpClientConfig.Builder().setFollowRedirects(true).build());
		
		final String sessionid2 = getSessionID(client, GET_SESSION_URL);
		
		final String username2 = "test_" + System.currentTimeMillis();
		
		// login
		login("clientXHRPolling2", client2, GET_SESSION_URL+"xhr-polling/" + sessionid2, username2, true);
		
		// suspend both clients
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
				
				Assert.assertTrue(message.contains("5:::{\"name\":\"announcement\",\"args\":[\"" + username + " disconnected\"]}"));
			}
		});
		
		// client 2 will received a disconnect notification from client 1
		disconnect("clientXHRPolling1", client, GET_SESSION_URL+"xhr-polling/" + sessionid1);
		
		client.close();
		client2.close();
	}

}
