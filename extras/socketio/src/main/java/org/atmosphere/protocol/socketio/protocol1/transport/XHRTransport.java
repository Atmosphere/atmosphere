package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.jetty.util.IO;
import org.atmosphere.jetty.util.URIUtil;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;
import org.atmosphere.protocol.socketio.transport.TransportBuffer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class XHRTransport extends AbstractHttpTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRTransport.class);
	
	private final int bufferSize;
	private final int maxIdleTime;

	protected abstract class XHRSessionHelper implements SessionTransportHandler {
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
		public void sendMessage(String message) throws SocketIOException {
			logger.error("Session[" + session.getSessionId() + "]: " + "sendMessage(String): " + message);
			
			synchronized (this) {
				if (is_open) {
					
					boolean enabled=true;
					
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
							if (!buffer.isEmpty()) {
								List<String> messages = buffer.drainMessages(1);
								if (messages.size() > 0) {
									StringBuilder data = new StringBuilder();
									for (String msg : messages) {
										data.append(msg);
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
								}
							} else {
								session.clearTimeoutTimer();
								request.setAttribute(SESSION_KEY, session);
								response.setBufferSize(bufferSize);
								AtmosphereResourceImpl resource = (AtmosphereResourceImpl)request.getAttribute(ApplicationConfig.ATMOSPHERE_RESOURCE);
								
								if(resource!=null){
									//resource.suspend(REQUEST_TIMEOUT, false);
									resource.suspend(7*1000, false);
									
									resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
									
									// pour le broadcast
									resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SessionTransportHandler, session.getTransportHandler());
									
									session.setAtmosphereResourceImpl(resource);
									
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
					BufferedReader reader = request.getReader();
					if (size == 0) {
						response.sendError(HttpServletResponse.SC_BAD_REQUEST);
					} else { 
						String data = decodePostData(request.getContentType(), IO.toString(reader));
						if (data != null && data.length() > 0) {
							
							List<SocketIOEvent> list = SocketIOEvent.parse(data);
							
							synchronized (session) {
								for (SocketIOEvent msg : list) {
									
									if(msg.getFrameType().equals(SocketIOEvent.FrameType.EVENT)){
										
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

		protected String decodePostData(String contentType, String data) {
			if (contentType.startsWith("application/x-www-form-urlencoded")) {
				if (data.substring(0, 5).equals("data=")) {
					return URIUtil.decodePath(data.substring(5));
				} else {
					return "";
				}
			} else if (contentType.startsWith("text/plain")) {
				return data;
			} else {
				// TODO: Treat as text for now, maybe error in the future.
				return data;
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
		public void disconnectWhenEmpty() {
			disconnectWhenEmpty = true;
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

	
	@Override
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory sessionFactory) throws IOException {
		
		if(session==null){
			session = sessionFactory.createSession(resource, atmosphereHandler);
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
			// pour le broadcast
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SessionTransportHandler, atmosphereHandler);
		}
		
		XHRSessionHelper handler = createHelper(session);
		handler.connect(resource, atmosphereHandler);
		return session;
	}
	
	@Override
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler atmosphereHandler, org.atmosphere.protocol.socketio.transport.SocketIOSession.Factory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}

}
