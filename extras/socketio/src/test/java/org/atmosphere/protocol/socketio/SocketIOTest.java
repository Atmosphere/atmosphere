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
import java.nio.charset.Charset;
import java.util.List;
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
import com.ning.http.client.providers.netty.NettyAsyncHttpProvider;
import com.ning.http.client.websocket.WebSocket;
import com.ning.http.client.websocket.WebSocketTextListener;
import com.ning.http.client.websocket.WebSocketUpgradeHandler;

/**
 * 
 * @author Sebastien Dionne  : sebastien.dionne@gmail.com
 *
 */
public abstract class SocketIOTest {
	protected static final Logger log = LoggerFactory.getLogger(SocketIOTest.class);
	
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
        //return new AsyncHttpClient(new GrizzlyAsyncHttpProvider(config), config);
        return new AsyncHttpClient(new NettyAsyncHttpProvider(config), config);
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
    	
    	//System.err.println("Name = " + name + " go in suspend mode");
    	log.debug(name + " go in suspend mode");
    	
    	Thread t = new Thread() {
            public void run() {
                try {
                	// 
            		client.prepareGet(url).execute(new AsyncCompletionHandlerAdapter() {

            			@Override
            			public com.ning.http.client.AsyncHandler.STATE onBodyPartReceived( HttpResponseBodyPart content) throws Exception {
            				/*
            				try {
                            	String body = new String(content.getBodyPartBytes());
                            	System.err.println("suspend Name = " + name + " onBodyPartReceived=" + body);
                            	
                            	if(listener!=null){
                            		listener.notify(body);
                            	} 
                            	
                            } finally {
                            }
                            */
            				return super.onBodyPartReceived(content);
            			}
            			
                        @Override
                        public Response onCompleted(Response response) throws Exception {
                            
                        	try {
                        		String body = new String(response.getResponseBodyAsBytes(), "UTF-8");
                            	//System.err.println("suspend Name = " + name + " Body=" + body);
                        		log.debug(name + " Suspend onCompleted Body=" + body);
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
    	
    	//System.err.println("Name=" + name + " HXR-Polling Sending message = " + message);
    	log.debug(name + " HXR-Polling Sending message = " + message);
    	
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
    
    protected void connect(final String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("Connect [" + name + "] message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out Connect from user=[" + name + "]");
        }
    	
    }
    
    protected void connectJSONP(final String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url, new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("Connect [" + name + "] message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "io.j[0](\"1::\");");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    	
    }
    
    protected void disconnect(final String name, final AsyncHttpClient client, final String url) throws Throwable {
    	final CountDownLatch l = new CountDownLatch(1);
		
		// fait un connect
		suspend(name, client, url+ "?disconnect", new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("disconnect [" + name + "] message received = " + message);
				l.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1::");
			}
		});
		
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out");
        }
    	
    }
    
    private void newSuspendConnection(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique, ResponseListener responseListener) throws Throwable {
    	//suspend une connection
    	suspend(name, client, url, responseListener);
    }
    
    protected void newSuspendConnection(final String name, final AsyncHttpClient client, final String url, ResponseListener responseListener) throws Throwable {
    	
    	System.err.println("newSuspendConnection name [" + name + "]");
    	//suspend une connection
    	suspend(name, client, url, responseListener);
    }
    
    protected void login(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique) throws Throwable {
    	final CountDownLatch latchGet = new CountDownLatch(2);
    	//final CountDownLatch latchGet2 = new CountDownLatch(1);
    	final CountDownLatch latchPost = new CountDownLatch(1);
    	
    	// fait un connect
    	connect(name, client, url);
    	
    	final ResponseListener responseListener = new ResponseListener() {
			@Override
			public void notify(String message) {
				log.info("GET login [" + name + "] CountDown=" + latchGet.getCount() + "  message received = " + message);
				
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
						case ACK : 
							// LOGIN
							if(data.contains("6:::1+")){
								latchGet.countDown();
								
								if(usernameUnique){
									//Assert.assertEquals(message, "6:::1+[false]");
									Assert.assertTrue(data.contains("6:::1+[false]"));
								} else {
									//Assert.assertEquals(message, "6:::1+[true]");
									Assert.assertTrue(data.contains("6:::1+[true]"));
									// on doit faire ceci, car pour un duplicate username, on n'envoie pas la liste des users 
									latchGet.countDown();
								}
							} 
							break;
						case HEARTBEAT :
						case DISCONNECT : 
							break;
						case EVENT : 
							// usernames
							if(data.contains("5:::{\"name\":\"nicknames\",\"args\":[{")){
								latchGet.countDown();
								// la liste des users connectes
								Assert.assertTrue(data.contains("5:::{\"name\":\"nicknames\",\"args\":[{"));
								//Assert.assertTrue(message.contains("\"" + username + "\":\"" + username + "\""));
								Assert.assertTrue(data.contains("\"" + username + "\""));
							} 
							break;
						default:
							
					}
					
				}
				
				log.info("FINISH GET login [" + name + "] CountDown=" + latchGet.getCount());
				
				if(latchGet.getCount()>0){
					try {
						newSuspendConnection(name, client, url, username,usernameUnique, this);
					} catch (Throwable e) {
						e.printStackTrace();
					}
				}
				
			} 
		};
    	
    	//suspend une connection
    	suspend(name, client, url, responseListener);
    	
    	// maintenant on fait login
		sendMessage(name, client, url, "5:1+::{\"name\":\"nickname\",\"args\":[\"" + username + "\"]}", new ResponseListener() {
			
			@Override
			public void notify(String message) {
				log.info("POST login [" + name + "] message received = " + message);
				latchPost.countDown();
				Assert.assertNotNull(message);
				Assert.assertEquals(message, "1");
			}
		});
		
		if (!latchGet.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out Login 1 from user=[" + name + "]");
        }
		/*
		if (!latchGet2.await(30, TimeUnit.SECONDS)) {
            
			throw new RuntimeException("Timeout out Login 2 from user=[" + name + "]");
        }
        */
		
		if (!latchPost.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out Login 3 from user=[" + name + "]");
        }
    }
    
    protected WebSocketWrapper loginWS(final String name, final AsyncHttpClient client, final String url, final String username, final boolean usernameUnique) throws Throwable{
    	final CountDownLatch l = new CountDownLatch(2);
    	
    	WebSocketWrapper wrapper = new WebSocketWrapper();
    	wrapper.setListener(new WebSocketResponseListener(wrapper) {
			
			@Override
			public void onClose(){
				System.err.println("onClose called");
			}
			
			@Override
			public void notify(String message) {
				log.info("WS login [" + name + "] CountDown=" + l.getCount() + "  message received = " + message);
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
						case ACK : 
							// LOGIN
							if(data.contains("6:::1+")){
								l.countDown();
								
								if(usernameUnique){
									//Assert.assertEquals(message, "6:::1+[false]");
									Assert.assertTrue(data.contains("6:::1+[false]"));
								} else {
									//Assert.assertEquals(message, "6:::1+[true]");
									Assert.assertTrue(data.contains("6:::1+[true]"));
									// on doit faire ceci, car pour un duplicate username, on n'envoie pas la liste des users 
									l.countDown();
								}
							} 
							break;
						case HEARTBEAT :
						case DISCONNECT : 
							break;
						case EVENT : 
							// usernames
							if(data.contains("5:::{\"name\":\"nicknames\",\"args\":[{")){
								l.countDown();
								// la liste des users connectes
								Assert.assertTrue(data.contains("5:::{\"name\":\"nicknames\",\"args\":[{"));
								//Assert.assertTrue(message.contains("\"" + username + "\":\"" + username + "\""));
								Assert.assertTrue(data.contains("\"" + username + "\""));
							} 
							break;
						default:
							
					}
					
				}
				
			}
		});
    	
    	// fait un connect et ca suspend
    	wrapper = connectWS(name, client, url, wrapper);
		
    	// maintenant on fait login
    	sendMessage(wrapper.websocket, "5:1+::{\"name\":\"nickname\",\"args\":[\"" + username + "\"]}");
    			
    	
		if (!l.await(30, TimeUnit.SECONDS)) {
            throw new RuntimeException("Timeout out Login WS for user=[" + username + "]");
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
				log.info("GET login [" + name + "] message received = " + message);
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
							log.info("GET login [" + name + "] message received = " + message);
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
				log.info("POST login [" + name + "] message received = " + message);
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
