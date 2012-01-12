package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.util.List;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.DefaultBroadcaster;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOClosedException;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOPacket;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionFactory;
import org.atmosphere.protocol.socketio.SocketIOSessionOutbound;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOPacketImpl.PacketType;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class XHRTransport extends AbstractTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRTransport.class);
	
	private final int bufferSize;
	
	protected static final String SESSION_KEY = XHRTransport.class.getName() + ".Session";
	
	protected abstract class XHRSessionHelper implements SocketIOSessionOutbound {
		protected final SocketIOSession session;
		private volatile boolean is_open = false;
		private final boolean isConnectionPersistant;

		XHRSessionHelper(SocketIOSession session, boolean isConnectionPersistant) {
			this.session = session;
			this.isConnectionPersistant = isConnectionPersistant;
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
		public void sendMessage(List<SocketIOPacketImpl> messages) throws SocketIOException {
			if(messages!=null){
				for (SocketIOPacketImpl msg: messages) {
    				switch(msg.getFrameType()){
    					case MESSAGE:
    					case JSON:
    					case EVENT:
    					case ACK:
    					case ERROR:
    						msg.setPadding(messages.size()>1);
    						sendMessage(msg.toString());
    						break;
    					default:
    						logger.error("DEVRAIT PAS ARRIVER onStateChange SocketIOEvent msg = " + msg );
    				}
    			}
			}
		}
		
		@Override
		public void sendMessage(String message) throws SocketIOException {
			logger.error("Session[" + session.getSessionId() + "]: " + "sendMessage(String): " + message);
			
			synchronized (this) {
				if (is_open) {
					
					// on va chercher le resource
					AtmosphereResourceImpl resource = session.getAtmosphereResourceImpl();
					
					if (resource != null) {
						
						try {
							writeData(resource.getResponse(), message);
						} catch (Exception e) {
							e.printStackTrace();
							
							logger.error("calling from " + this.getClass().getName() + " : " + "sendMessage ON FORCE UN RESUME");
							try {
								finishSend(resource.getResponse());
							} catch (IOException ex) {
								ex.printStackTrace();
							}
							resource.resume();
							
							throw new SocketIOException(e);
						}
						if (!isConnectionPersistant) {
							try {
								finishSend(resource.getResponse());
							} catch (IOException e) {
								e.printStackTrace();
							}
							resource.resume();
						} else {
							logger.error("calling from " + this.getClass().getName() + " : " + "sendMessage");
							session.startHeartbeatTimer();
						}
					} else {
						/*
						String data = message;
						if (cache.putMessage(data, maxIdleTime) == false) {
							logger.error("calling from " + this.getClass().getName() + " : " + "On Disconnect sur sendMessage parce que resource==null");
							session.onDisconnect(DisconnectReason.TIMEOUT);
							abort();
							throw new SocketIOException();
						}
						*/
						logger.error("calling from " + this.getClass().getName() + " : " + "On Disconnect sur sendMessage parce que resource==null");
						throw new SocketIOException();
					}
				} else {
					logger.error("calling from " + this.getClass().getName() + " : " + "SocketIOClosedException sendMessage");
					throw new SocketIOClosedException();
				}
			}
			
		}

		@Override
		public void handle(HttpServletRequest request, final HttpServletResponse response, SocketIOSession session) throws IOException {
			if ("GET".equals(request.getMethod())) {
				synchronized (this) {
					AtmosphereResourceImpl resource = (AtmosphereResourceImpl)request.getAttribute(ApplicationConfig.ATMOSPHERE_RESOURCE);
					
					if (!is_open) {
						response.sendError(HttpServletResponse.SC_NOT_FOUND);
					} else {
						if (!isConnectionPersistant) {
							
							if(resource!=null){
								
								resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
								
								// pour le broadcast
								resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, session.getTransportHandler());
								
								session.setAtmosphereResourceImpl(resource);
								
								resource.addEventListener(new AtmosphereResourceEventListener() {
									
									@Override
									public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
									}
									
									@Override
									public void onSuspend(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
									}
									
									@Override
									public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
										if(event.isResumedOnTimeout()){
											try {
												event.getResource().write(response.getOutputStream(), new SocketIOPacketImpl(PacketType.NOOP).toString());
											} catch (IOException e) {
												e.printStackTrace();
											}
										}
									}
									
									@Override
									public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
									}
									
									@Override
									public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
									}
								});
								
								session.clearTimeoutTimer();
								request.setAttribute(SESSION_KEY, session);
								
								StringBuilder data = new StringBuilder();
								
								// on va regarder s'il y a des messages dans le BroadcastCache
								if(DefaultBroadcaster.class.isAssignableFrom(resource.getBroadcaster().getClass())){
									
									@SuppressWarnings("unchecked")
									List<String> cachedMessages = DefaultBroadcaster.class.cast(resource.getBroadcaster()).broadcasterCache.retrieveFromCache(resource);
									
									if(cachedMessages!=null){
										if (cachedMessages.size()> 1) {
											for (Object object : cachedMessages) {
												String msg = object.toString();
												data.append(SocketIOPacketImpl.SOCKETIO_MSG_DELIMITER).append(msg.length()).append(SocketIOPacketImpl.SOCKETIO_MSG_DELIMITER).append(msg);
											}
										} else if (cachedMessages.size()== 1){
											data.append(cachedMessages.get(0));
										}
									}
									
									// avons-nous du data a envoyer ?
									if(data.toString().length()>0){
										startSend(response);
										writeData(response, data.toString());
										finishSend(response);
										
										// on vient d'envoyer du data, donc on resume
										resource.resume();
									} else {
										resource.suspend(session.getRequestSuspendTime(), false);
									}
								} else {
									// on suspend donc la request
									
									resource.suspend(session.getRequestSuspendTime(), false);
									resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
									
									// pour le broadcast
									resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, session.getTransportHandler());
									
									session.setAtmosphereResourceImpl(resource);
								}
								
								
							} 
							
								
						} else {
							// ce cas n'est pas prevu pour le moment, mais ca serait pour le xhr-streaming
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
										//session.getAtmosphereResourceImpl().resume();
										writeData(response, SocketIOPacketImpl.POST_RESPONSE);
										
									} else {
										writeData(response, SocketIOPacketImpl.POST_RESPONSE);
									}
									
								}
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
				session.onDisconnect(DisconnectReason.DISCONNECT);
				abort();
			} else {
				if (!is_open) {
					session.onDisconnect(DisconnectReason.DISCONNECT);
					abort();
				} else {
					logger.error("calling from " + this.getClass().getName() + " : " + "onComplete");
					session.startTimeoutTimer();
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

		public void connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler) throws IOException {
			
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

			session.onShutdown();
		}
	}

	public XHRTransport(int bufferSize) {
		this.bufferSize = bufferSize;
	}

	/**
	 * This method should only be called within the context of an active HTTP request.
	 */
	protected abstract XHRSessionHelper createHelper(SocketIOSession session);

	
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {
		
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
	
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}
	
	@Override
	public void handle(AsynchronousProcessor processor, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {

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
