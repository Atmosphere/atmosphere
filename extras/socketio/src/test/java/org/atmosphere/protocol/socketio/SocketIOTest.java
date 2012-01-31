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

import java.net.URLEncoder;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testng.Assert;

import com.ning.http.client.AsyncCompletionHandler;
import com.ning.http.client.AsyncHttpClient;
import com.ning.http.client.AsyncHttpClientConfig;
import com.ning.http.client.HttpResponseBodyPart;
import com.ning.http.client.Response;
import com.ning.http.client.providers.grizzly.GrizzlyAsyncHttpProvider;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public abstract class SocketIOTest {
	protected final Logger log = LoggerFactory.getLogger(SocketIOTest.class);
	
	public static final String GET_SESSION_URL = "http://localhost:8080/atmosphere-socketio-chat/ChatAtmosphereHandler/1/";
	public static final String WS_GET_SESSION_URL = "ws://localhost:8080/atmosphere-socketio-chat/ChatAtmosphereHandler/1/";
	
	//public static final String GET_SESSION_URL = "http://192.168.10.144:3000/socket.io/1/";
	//public static final String WS_GET_SESSION_URL = "ws://192.168.10.144:3000/socket.io/1/";
	
	public final static int TIMEOUT = 5;
	
	public static class AsyncCompletionHandlerAdapter extends AsyncCompletionHandler<Response> {
        public Runnable runnable;

        @Override
        public Response onCompleted(Response response) throws Exception {
            return response;
        }

        /* @Override */
        public void onThrowable(Throwable t) {
            t.printStackTrace();
            Assert.fail("Unexpected exception: " + t.getMessage(), t);
        }

    }
	
    public static AsyncHttpClient getAsyncHttpClient(AsyncHttpClientConfig config) {
        if (config == null) {
            config = new AsyncHttpClientConfig.Builder().build();
        }
        return new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
    }
    
    
    public static String getSessionID(AsyncHttpClient client, String url) throws Throwable {
    	
    	final CountDownLatch l = new CountDownLatch(1);
    	final AtomicReference<String> sessionid = new AtomicReference<String>();
    	
    	// on va chercher une sessionID
		client.prepareGet(url).execute(new AsyncCompletionHandlerAdapter() {

            @Override
            public Response onCompleted(Response response) throws Exception {
                try {
                	String body = response.getResponseBody();
                	String array[] = body.split(":");
                	sessionid.set(array[0]);
                } finally {
                	l.countDown();
                }
                return response;
            }
        }).get();
		
		if (!l.await(TIMEOUT, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		return sessionid.get();
    }
    
    public static void suspend(final String name, final AsyncHttpClient client, final String url, final ResponseListener listener) throws Throwable {
    	
    	System.err.println("Name = " + name + " go in suspend mode");
    	
    	Thread t = new Thread() {
            public void run() {
                try {
                	// 
            		client.prepareGet(url).execute(new AsyncCompletionHandlerAdapter() {

            			@Override
            			public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived( HttpResponseBodyPart content) throws Exception {
            				try {
                            	String body = new String(content.getBodyPartBytes());
                            	System.err.println("suspend Name = " + name + " onBodyPartReceived=" + body);
                            	
                            	if(listener!=null){
                            		listener.notify(body);
                            	}
                            	
                            } finally {
                            }
            				return super.onBodyPartReceived(content);
            			}
            			
                        @Override
                        public Response onCompleted(Response response) throws Exception {
                            try {
                            	String body = response.getResponseBody();
                            	System.err.println("suspend Name = " + name + " Body=" + body);
                            	if(listener!=null){
                            		listener.notify(body);
                            	}
                            } finally {
                            }
                            return response;
                        }
                    }).get();
                	
                } catch (InterruptedException e1) {
                } catch (Exception e) {
                	e.printStackTrace();
                	Assert.fail();
                }
            }
        };
        
        t.start();
    	
    }
    
    public static WebSocketWrapper connectWS(final String name, final AsyncHttpClient client, String url, final WebSocketWrapper wrapper) throws Throwable {
    	
    	// on ouvre une connection en WebSocket
        WebSocket websocket = client.prepareGet(url).execute(new WebSocketUpgradeHandler.Builder().addWebSocketListener(new WebSocketTextListener() {
			
			@Override
			public void onOpen(WebSocket websocket) {
				// TODO Auto-generated method stub
				
			}
			
			@Override
			public void onError(Throwable t) {
				t.printStackTrace();
			}
			
			@Override
			public void onClose(WebSocket websocket) {
				System.err.println("WS onClose");
				if(wrapper.listener!=null){
					wrapper.listener.onClose();
             	}
			}
			
			@Override
			public void onMessage(String message) {
				System.err.println("WS onMessage = " + new String(message));
				if(wrapper.listener!=null){
					wrapper.listener.notify(message);
             	}
				
			}

			public void onFragment(String arg0, boolean arg1) {
				// TODO Auto-generated method stub
				System.err.println("onFragment=" + arg0);
				
			}
		}).build()).get();
        
        wrapper.websocket = websocket;
        
		return wrapper;
    }
    
    public static void sendMessage(WebSocket websocket, String message) throws Throwable {
    	
    	System.err.println("Ws Sending message = " + message);
    	
    	websocket.sendMessage(message.getBytes());
    }
    
    public static void sendMessage(final String name, final AsyncHttpClient client, final String url, final String message, final ResponseListener listener) throws Throwable {
    	
    	System.err.println("Name=" + name + " HXR-Polling Sending message = " + message);
    	
    	Thread t = new Thread() {
            public void run() {
                try {
					client.preparePost(url).setBody(message).execute(new AsyncCompletionHandlerAdapter() {
			
						@Override
						public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived( HttpResponseBodyPart content) throws Exception {
							try {
			                	String body = new String(content.getBodyPartBytes());
			                	System.err.println("sendMessage Name = " + name + " onBodyPartReceived=" + body);
			                	if(listener!=null){
			                		listener.notify(body);
			                	}
			                	
			                } finally {
			                }
							return super.onBodyPartReceived(content);
						}
						
			            @Override
			            public Response onCompleted(Response response) throws Exception {
			                try {
			                	String body = response.getResponseBody();
			                	System.err.println("sendMessage Name = " + name + " Body=" + body);
			                	if(listener!=null){
			                		listener.notify(body);
			                	}
			                } finally {
			                }
			                return response;
			            }
			        }).get();
					
                } catch (InterruptedException e1) {
                } catch (Exception e) {
                	e.printStackTrace();
                	Assert.fail();
                }
            }
        };
        
        t.start();
		
    }
    
    protected void connect(String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("Connect message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    	
    }
    
    protected void connectJSONP(String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("Connect message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    	
    }
    
    protected void disconnect(String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url+ "?disconnect", new ResponseListener() {
			
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
    	
    }
    
    protected void login(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique) throws Throwable{
    	final CountDownLatch latchGet = new CountDownLatch(1);
    	final CountDownLatch latchGet2 = new CountDownLatch(1);
    	final CountDownLatch latchPost = new CountDownLatch(1);
    	
    	// fait un connect
    	connect(name, client, url);
    	
    	//suspend une connection
    	suspend(name, client, url, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("GET login message received = " + message);
				latchGet.countDown();
				Assert.assertNotNull(message);
				
				//System.err.println("byte=" + message.getBytes());
				
				if(message.charAt(0)==(byte)SocketIOPacketImpl.SOCKETIO_MSG_DELIMITER){
					System.err.println("Multi-message");
				}
				
				if(usernameUnique){
					Assert.assertEquals(message, "6:::1+[false]");
				} else {
					Assert.assertEquals(message, "6:::1+[true]");
				}
				
				// on doit faire une nouvelle connection pour obtenir la suite
				//suspend une connection
		    	try {
					suspend(name, client, url, new ResponseListener() {
						@Override
						public void notify(String message) {
							log.info("GET login message received = " + message);
							latchGet2.countDown();
							Assert.assertTrue(message.startsWith("5:::{\"name\":\"nicknames\",\"args\":[{"));
							Assert.assertTrue(message.contains("\"" + username + "\":\"" + username + "\""));
						} 
					});
				} catch (Throwable e) {
					e.printStackTrace();
					Assert.fail();
				}
			} 
		});
    	
    	// maintenant on fait login
		sendMessage(name, client, url, "5:1+::{\"name\":\"nickname\",\"args\":[\"" + username + "\"]}", new ResponseListener() {
			
			boolean found = false;
			
			@Override
			public void notify(String message) {
				log.info("POST login message received = " + message);
				latchPost.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1");
			}
		});
		
		if (!latchGet.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		if (!latchGet2.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		if (!latchPost.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    }
    
    protected WebSocketWrapper loginWS(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique) throws Throwable{
    	final CountDownLatch l = new CountDownLatch(3);
    	
    	WebSocketWrapper wrapper = new WebSocketWrapper();
    	wrapper.setListener(new WebSocketResponseListener(wrapper) {
			
    		boolean isLogged = false;
    		
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				l.countDown();
				if(l.getCount()==2){
					Assert.assertNotNull(message);
					Assert.assertEquals(message, "1::");
					// on fait le login
					try {
						sendMessage(wrapper.websocket, "5:1+::{\"name\":\"nickname\",\"args\":[\"" + username + "\"]}");
					} catch (Throwable e) {
						e.printStackTrace();
					}
					
				} else if(l.getCount()==1){
					if(usernameUnique){
						Assert.assertEquals(message, "6:::1+[false]");
					} else {
						Assert.assertEquals(message, "6:::1+[true]");
					}
				} else if(l.getCount()==0){
					/*
					if(!isLogged){
						Assert.assertTrue(message.startsWith("5:::{\"args\":"));
						Assert.assertTrue(message.contains(username + " connected"));
						
						isLogged = true;
					} else {
						Assert.assertTrue(message.startsWith("5:::{\"name\":"));
					}
					*/
				}  else {
					Assert.fail();
				}
				
			}
		});
    	
    	// fait un connect et ca suspend
    	wrapper = connectWS(name, client, url, wrapper);
		
	
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		return wrapper;
    }
    
    protected void loginJSONP(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique) throws Throwable{
    	final CountDownLatch latchGet = new CountDownLatch(1);
    	final CountDownLatch latchGet2 = new CountDownLatch(1);
    	final CountDownLatch latchPost = new CountDownLatch(1);
    	
    	// fait un connect
    	connectJSONP(name, client, url);
    	
    	//suspend une connection
    	suspend(name, client, url, new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("GET login message received = " + message);
				latchGet.countDown();
				Assert.assertNotNull(message);
				if(usernameUnique){
					Assert.assertEquals(message, "io.j[0](6:::1+[false]);");
				} else {
					Assert.assertEquals(message, "io.j[0](6:::1+[true]);");
				}
				
				// on doit faire une nouvelle connection pour obtenir la suite
				//suspend une connection
		    	try {
					suspend(name, client, url, new ResponseListener() {
						@Override
						public void notify(String message) {
							log.info("GET login message received = " + message);
							latchGet2.countDown();
							Assert.assertTrue(message.startsWith("io.j[0](5:::{\"name\":\"nicknames\",\"args\":[{"));
							Assert.assertTrue(message.contains("\"" + username + "\":\"" + username + "\""));
						} 
					});
				} catch (Throwable e) {
					e.printStackTrace();
					Assert.fail();
				}
			} 
		});
    	
    	// maintenant on fait login
    	String message = "\"5:1+::{\\\"name\\\":\\\"nickname\\\",\\\"args\\\":[\\\"" + username + "\\\"]}\"";
		sendMessage(name, client, url, "d=" + URLEncoder.encode(message,"UTF-8"), new ResponseListener() {
			
			boolean found = false;
			
			@Override
			public void notify(String message) {
				log.info("POST login message received = " + message);
				latchPost.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1");
			}
		});
		
		if (!latchGet.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		if (!latchGet2.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
		
		if (!latchPost.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    }

}
