package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.BufferedReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import org.atmosphere.cpr.ApplicationConfig;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.jetty.continuation.Continuation;
import org.atmosphere.jetty.continuation.ContinuationListener;
import org.atmosphere.jetty.util.URIUtil;
import org.atmosphere.protocol.socketio.ConnectionState;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOException;
import org.atmosphere.protocol.socketio.SocketIOFrame;
import org.atmosphere.protocol.socketio.protocol1.transport.SocketIOClosedException;
import org.atmosphere.protocol.socketio.transport.DisconnectReason;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.Transport;
import org.atmosphere.protocol.socketio.transport.TransportBuffer;
import org.atmosphere.protocol.socketio.transport.SocketIOSession.SessionTransportHandler;
import org.atmosphere.jetty.util.IO;

public abstract class XHRTransport extends AbstractHttpTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRTransport.class);
	
	public static final String CONTINUATION_KEY = "org.atmosphere.protocol.socketio.transport.transport.XHRTransport.Continuation";
	private final int bufferSize;
	private final int maxIdleTime;

	protected abstract class XHRSessionHelper implements SessionTransportHandler, ContinuationListener {
		protected final SocketIOSession session;
		private final TransportBuffer buffer = new TransportBuffer(bufferSize);
		private volatile boolean is_open = false;
		private volatile Continuation continuation = null;
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
			//sendMessage(SocketIOFrame.TEXT_MESSAGE_TYPE, message);
			
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
							//Continuation cont = continuation;
							//continuation = null;
							//cont.complete();
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
		public void sendMessage(int messageType, String message) throws SocketIOException {
			synchronized (this) {
				logger.error("Session[" + session.getSessionId() + "]: " + "sendMessage(int, String): [" + messageType + "]: " + message);
				if (is_open && session.getConnectionState() == ConnectionState.CONNECTED) {
					sendMessage(new SocketIOFrame(SocketIOFrame.FrameType.DATA, messageType, message));
				} else {
					throw new SocketIOClosedException();
				}
			}
		}
		
		@Override
		public void sendMessage(SocketIOFrame frame) throws SocketIOException {
			logger.error("Session[" + session.getSessionId() + "]: " + "sendMessage(frame): [" + frame.getFrameType() + "]: " + frame.getData());
			
			sendMessage(frame.encode());
		}

		@Override
		public void handle(HttpServletRequest request, HttpServletResponse response, SocketIOSession session) throws IOException {
			if ("GET".equals(request.getMethod())) {
				synchronized (this) {
					if (!is_open && buffer.isEmpty()) {
						response.sendError(HttpServletResponse.SC_NOT_FOUND);
					} else {
						
						Continuation cont = (Continuation) request.getAttribute(CONTINUATION_KEY);
						if (continuation != null || cont != null) {
							if (continuation == cont) {
								continuation = null;
								finishSend(response);
							}
							if (cont != null) {
								request.removeAttribute(CONTINUATION_KEY);
							}
							return;
						}
						
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
								
								request.setAttribute(CONTINUATION_KEY, resource);
								startSend(response);
								
								//DEBUG
								//writeData(response, "2::");
								
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
							
							// REFAIRE LE PARSING
							
							List<SocketIOEvent> list = SocketIOEvent.parse(data);
							
							synchronized (session) {
								for (SocketIOEvent msg : list) {
									
									//DEBUG
									if(msg.getFrameType().equals(SocketIOEvent.FrameType.EVENT)){
										
										// on doit ecrire sur le request en suspend
										
										//session.getAtmosphereResourceImpl().getResponse().getOutputStream().print("6:::1+[false]");
										//session.getAtmosphereResourceImpl().getResponse().flushBuffer();
										
										//session.getAtmosphereResourceImpl().resume();
										
										
										// ici on doit envoyer le message au AtmosphereHandler
										// mais envoyer les code de retour SOCKET.IO ICI
										
										session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg.getData());
										
										// GROS DEBUG
										session.getAtmosphereResourceImpl().resume();
										
										System.out.println("terminer");
										
										writeData(response, "1");
										
									} else {
										//session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg);
										//session.onMessage(session.getAtmosphereResourceImpl(), session.getTransportHandler(), msg.getData());
										writeData(response, "1");
									}
									
									//writeData(response, "1");
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
					//DEBUG
					//writeData(response, "1");
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

		@Override
		public void onComplete(Continuation cont) {
			if (continuation != null && cont == continuation) {
				continuation = null;
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
		}

		@Override
		public void onTimeout(Continuation cont) {
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
			
			/*
			continuation = ContinuationSupport.getContinuation(request);
			continuation.addContinuationListener(this);
			if (isConnectionPersistant) {
				continuation.setTimeout(0);
			}
			*/
			
			customConnect(request, response);
			is_open = true;
			session.onConnect(resource, this);
			finishSend(response);
			
			if(isConnectionPersistant){
				resource.suspend();
			} 
			
			/*
			if (continuation != null) {
				if (isConnectionPersistant) {
					request.setAttribute(CONTINUATION_KEY, continuation);
					continuation.suspend(response);
				} else {
					continuation = null;
				}
			}
			*/
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
			if (continuation != null) {
				Continuation cont = continuation;
				continuation = null;
				if (cont.isSuspended()) {
					session.getAtmosphereResourceImpl().resume();
				}
			}
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
			// ceci ajoute le ChatAtmosphereHandler, mais ca ne permet pas encore de 
			// passer le AtmosphereResourceImpl resource
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
