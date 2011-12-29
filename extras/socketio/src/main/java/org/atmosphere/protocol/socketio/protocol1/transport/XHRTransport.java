package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOClosedException;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOPacket;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionOutbound;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.TransportBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class XHRTransport extends AbstractTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRTransport.class);
	
	private final int bufferSize;
	private final int maxIdleTime;
	
	/**
	 * This is a sane default based on the timeout values of various browsers.
	 */
	public static final long HTTP_REQUEST_TIMEOUT = 30 * 1000;

	/**
	 * The amount of time the session will wait before trying to send a ping. Using a value of half the HTTP_REQUEST_TIMEOUT should be good enough.
	 */
	public static final long HEARTBEAT_DELAY = HTTP_REQUEST_TIMEOUT / 2;

	/**
	 * This specifies how long to wait for a pong (ping response).
	 */
	public static final long HEARTBEAT_TIMEOUT = 10 * 1000;

	/**
	 * For non persistent connection transports, this is the amount of time to wait for messages before returning empty results.
	 */
	public static long REQUEST_TIMEOUT = 20 * 1000;

	protected static final String SESSION_KEY = "com.glines.socketio.server.AbstractHttpTransport.Session";
	
	

	protected abstract class XHRSessionHelper implements SocketIOSessionOutbound {
		protected final SocketIOSession session;
		private final TransportBuffer buffer = new TransportBuffer(bufferSize);
		private volatile boolean is_open = false;
		private final boolean isConnectionPersistant;
		private boolean disconnectWhenEmpty = false;

		XHRSessionHelper(SocketIOSession session, boolean isConnectionPersistant) {
			this.session = session;
			this.isConnectionPersistant = isConnectionPersistant;
			if (isConnectionPersistant) {
				session.setHeartbeat(HEARTBEAT_DELAY);
				session.setTimeout(HEARTBEAT_TIMEOUT);
			} else {
				session.setTimeout((HTTP_REQUEST_TIMEOUT - REQUEST_TIMEOUT) / 2);
			}
		}

		protected abstract void startSend(HttpServletResponse response) throws IOException;

		protected abstract void writeData(ServletResponse response, String data) throws IOException;

		protected abstract void finishSend(ServletResponse response) throws IOException;

		@Override
		public void disconnect() {
			synchronized (this) {
				session.onDisconnect(DisconnectReason.DISCONNECT);
				abort();
			}
		}

		@Override
		public void close() {
			synchronized (this) {
				session.startClose();
			}
		}

		@Override
		public ConnectionState getConnectionState() {
			return session.getConnectionState();
		}
		
		@Override
		public void sendMessage(SocketIOPacket packet) throws SocketIOException {
			if(packet!=null){
				sendMessage(packet.toString());
			}
		}
		
		@Override
		public void sendMessage(String message) throws SocketIOException {
			logger.error("Session[" + session.getSessionId() + "]: " + "sendMessage(String): " + message);
			
			synchronized (this) {
				if (is_open) {
					
					//DEBUG 
					boolean enabled=false;
					
					if(enabled) {
						String data = message;
						if (buffer.putMessage(data, maxIdleTime) == false) {
							logger.error("calling from " + this.getClass().getName() + " : " + "On Disconnect sur sendMessage");
							session.onDisconnect(DisconnectReason.TIMEOUT);
							abort();
							throw new SocketIOException();
						}
						return;
					}
					
					// on va chercher le resource
					AtmosphereResourceImpl resource = session.getAtmosphereResourceImpl();
					
					if (resource != null) {
						//List<String> messages = buffer.drainMessages(1);
						List<String> messages = new ArrayList<String>();
						messages.add(message);
						StringBuilder data = new StringBuilder();
						for (String msg : messages) {
							data.append(msg);
						}
						try {
							writeData(resource.getResponse(), data.toString());
						} catch (IOException e) {
							e.printStackTrace();
							throw new SocketIOException(e);
						}
						if (!isConnectionPersistant) {
							resource.resume();
						} else {
							logger.error("calling from " + this.getClass().getName() + " : " + "sendMessage");
							session.startHeartbeatTimer();
						}
					} else {
						String data = message;
						if (buffer.putMessage(data, maxIdleTime) == false) {
							logger.error("calling from " + this.getClass().getName() + " : " + "On Disconnect sur sendMessage");
							session.onDisconnect(DisconnectReason.TIMEOUT);
							abort();
							throw new SocketIOException();
						}
					}
				} else {
					logger.error("calling from " + this.getClass().getName() + " : " + "SocketIOClosedException sendMessage");
					throw new SocketIOClosedException();
				}
			}
			
		}

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException {
			if ("GET".equals(request.getMethod())) {
				synchronized (this) {
					if (!is_open && buffer.isEmpty()) {
						response.sendError(HttpServletResponse.SC_NOT_FOUND);
					} else {
						if (!isConnectionPersistant) {
							
							AtmosphereResourceImpl resource = (AtmosphereResourceImpl)request.getAttribute(ApplicationConfig.ATMOSPHERE_RESOURCE);
							
							if (!buffer.isEmpty() && resource!=null) {
								StringBuilder data = new StringBuilder();
								
								// on va regarder s'il y a des messages dans le BroadcastCache
								if(DefaultBroadcaster.class.isAssignableFrom(resource.getBroadcaster().getClass())){
									
									@SuppressWarnings("unchecked")
									List<String> cachedMessages = DefaultBroadcaster.class.cast(resource.getBroadcaster()).broadcasterCache.retrieveFromCache(resource);
									
									List<String> bufferMessages = buffer.drainMessages();
									
									if (cachedMessages.size() + bufferMessages.size() > 1) {
										for (String msg : bufferMessages) {
											data.append('\ufffd').append(msg.length()).append('\ufffd').append(msg);
										}
										for (String msg : cachedMessages) {
											data.append('\ufffd').append(msg.length()).append('\ufffd').append(msg);
										}
									} else {
										if(!bufferMessages.isEmpty()){
											data.append(bufferMessages.get(0));
										} else {
											data.append(cachedMessages.get(0));
										}
										
									}
									
								} else {
									List<String> bufferMessages = buffer.drainMessages();
									
									if (bufferMessages.size() > 1) {
										for (String msg : bufferMessages) {
											data.append('\ufffd').append(msg.length()).append('\ufffd').append(msg);
										}
									} else if (bufferMessages.size()==1){
										data.append(bufferMessages.get(0));
									}
								}
								
								if(data.toString().length()>0){
									startSend(response);
									writeData(response, data.toString());
									finishSend(response);
									if (!disconnectWhenEmpty) {
										logger.error("calling from " + this.getClass().getName() + " : " + "handle");
										session.startTimeoutTimer();
									} else {
										abort();
									}
								} else {
									startSend(response);
								}
								
							} else {
								
								session.clearTimeoutTimer();
								request.setAttribute(SESSION_KEY, session);
								response.setBufferSize(bufferSize);
								
								if(resource!=null){
									
									// on va regarder s'il y a des messages dans le BroadcastCache
									if(DefaultBroadcaster.class.isAssignableFrom(resource.getBroadcaster().getClass())){
										
										@SuppressWarnings("unchecked")
										List<String> listMessages = DefaultBroadcaster.class.cast(resource.getBroadcaster()).broadcasterCache.retrieveFromCache(resource);
										
										if(!listMessages.isEmpty()){
											StringBuilder data = new StringBuilder();
											
											if(listMessages.size()>1){
												for (String msg : listMessages) {
													data.append('\ufffd').append(msg.length()).append('\ufffd').append(msg);
												}
											} else {
												data.append(listMessages.get(0));
											}
											startSend(response);
											writeData(response, data.toString());
											finishSend(response);
											if (!disconnectWhenEmpty) {
												logger.error("calling from " + this.getClass().getName() + " : " + "handle");
												session.startTimeoutTimer();
											} else {
												abort();
											}
										} else {
											resource.suspend(REQUEST_TIMEOUT, false);
											resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
											
											// pour le broadcast
											resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, session.getTransportHandler());
											
											session.setAtmosphereResourceImpl(resource);
										}
									} else {
										resource.suspend(REQUEST_TIMEOUT, false);
										resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
										
										// pour le broadcast
										resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, session.getTransportHandler());
										
										session.setAtmosphereResourceImpl(resource);
									}
									
								}
								
								startSend(response);
								
							}
						} else {
							response.sendError(HttpServletResponse.SC_NOT_FOUND);
						}
						
					}
				}
			} else if ("POST".equals(request.getMethod())) {
				if (is_open) {
					int size = request.getContentLength();
					if (size == 0) {
						response.sendError(HttpServletResponse.SC_BAD_REQUEST);
					} else {
						String data = (String)request.getAttribute(POST_MESSAGE_RECEIVED);
						if(data==null){
							data = decodePostData(request.getContentType(), extractString(request.getReader()));
						}
						if (data != null && data.length() > 0) {
							
							List<SocketIOPacketImpl> list = SocketIOPacketImpl.parse(data);
							
							synchronized (session) {
								for (SocketIOPacketImpl msg : list) {
									
									if(msg.getFrameType().equals(SocketIOPacketImpl.PacketType.EVENT)){
										
										session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg.getData());
										session.getAtmosphereResourceImpl().resume();
										
										writeData(response, "1");
										
									} else {
										writeData(response, "1");
									}
									
								}
							}
						}
						// Ensure that the disconnectWhenEmpty flag is obeyed in the case where
						// it is set during a POST.
						synchronized (this) {
							if (disconnectWhenEmpty && buffer.isEmpty()) {
								if (session.getConnectionState() == ConnectionState.CLOSING) {
									session.onDisconnect(DisconnectReason.CLOSED);
								}
								abort();
							}
						}
					}
				}
			} else {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}

		}

		public void onComplete() {
			if (isConnectionPersistant) {
				is_open = false;
				if (!disconnectWhenEmpty) {
					session.onDisconnect(DisconnectReason.DISCONNECT);
				}
				abort();
			} else {
				if (!is_open && buffer.isEmpty() && !disconnectWhenEmpty) {
					session.onDisconnect(DisconnectReason.DISCONNECT);
					abort();
				} else {
					if (disconnectWhenEmpty) {
						abort();
					} else {
						logger.error("calling from " + this.getClass().getName() + " : " + "onComplete");
						session.startTimeoutTimer();
					}
				}
			}
		}

		public void onTimeout() {
			/*
			if (continuation != null && cont == continuation) {
				continuation = null;
				if (isConnectionPersistant) {
					is_open = false;
					session.onDisconnect(DisconnectReason.TIMEOUT);
					abort();
				} else {
					if (!is_open && buffer.isEmpty()) {
						session.onDisconnect(DisconnectReason.DISCONNECT);
						abort();
					} else {
						try {
							finishSend(cont.getServletResponse());
						} catch (IOException e) {
							session.onDisconnect(DisconnectReason.DISCONNECT);
							abort();
						}
					}
					logger.error("calling from " + this.getClass().getName() + " : " + "onTimeout");
					session.startTimeoutTimer();
				}
			}
			*/
		}

		protected abstract void customConnect(HttpServletRequest request, HttpServletResponse response) throws IOException;

		public void connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler) throws IOException {
			
			HttpServletRequest request = resource.getRequest();
			HttpServletResponse response = resource.getResponse();
			
			request.setAttribute(SESSION_KEY, session);
			response.setBufferSize(bufferSize);
			
			customConnect(request, response);
			is_open = true;
			session.onConnect(resource, this);
			finishSend(response);
			
			if(isConnectionPersistant){
				resource.suspend();
			} 
			
		}

		@Override
		public void abort() {
			logger.error("calling from " + this.getClass().getName() + " : " + "abort");
			session.clearHeartbeatTimer();
			session.clearTimeoutTimer();
			is_open = false;
			session.getAtmosphereResourceImpl().resume();

			buffer.setListener(new TransportBuffer.BufferListener() {
				@Override
				public boolean onMessages(List<String> messages) {
					return false;
				}

				@Override
				public boolean onMessage(String message) {
					return false;
				}
			});
			buffer.clear();
			session.onShutdown();
		}
	}

	public XHRTransport(int bufferSize, int maxIdleTime) {
		this.bufferSize = bufferSize;
		this.maxIdleTime = maxIdleTime;
	}

	/**
	 * This method should only be called within the context of an active HTTP request.
	 */
	protected abstract XHRSessionHelper createHelper(SocketIOSession session);

	
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.SocketIOSession.Factory sessionFactory) throws IOException {
		
		if(session==null){
			session = sessionFactory.createSession(resource, atmosphereHandler);
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
			// pour le broadcast
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, atmosphereHandler);
		}
		
		XHRSessionHelper handler = createHelper(session);
		handler.connect(resource, atmosphereHandler);
		return session;
	}
	
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.SocketIOSession.Factory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}
	
	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, SocketIOSession.Factory sessionFactory) throws IOException {

		HttpServletRequest request = resource.getRequest();
		HttpServletResponse response = resource.getResponse();
		
		Object obj = request.getAttribute(SESSION_KEY);
		SocketIOSession session = null;
		String sessionId = null;
		if (obj != null) {
			session = (SocketIOSession) obj;
		} else {
			sessionId = extractSessionId(request);
			if (sessionId != null && sessionId.length() > 0) {
				session = sessionFactory.getSession(sessionId);
			}
		}
		
		boolean isDisconnectRequest = isDisconnectRequest(request);
		
		if (session != null) {
			SocketIOSessionOutbound handler = session.getTransportHandler();
			if (handler != null) {
				if(!isDisconnectRequest){
					handler.handle(request, response, session);
				} else {
					handler.disconnect();
					response.setStatus(200);
				}
			} else {
				if(!isDisconnectRequest){
					// on fait un connect
					session = connect(session, resource, atmosphereHandler, sessionFactory);
					if (session == null) {
						response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
					}
				} else {
					response.setStatus(200);
				}
			}
		} else if (sessionId != null && sessionId.length() > 0) {
			logger.error("Session NULL but not sessionId : Soit un mauvais sessionID ou il y a eu un DISCONNECT");
			response.sendError(HttpServletResponse.SC_BAD_REQUEST);
		} else {
			if ("GET".equals(request.getMethod())) {
				session = connect(resource, atmosphereHandler, sessionFactory);
				if (session == null) {
					response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
				}
			} else {
				response.sendError(HttpServletResponse.SC_BAD_REQUEST);
			}
		}
	}

}
