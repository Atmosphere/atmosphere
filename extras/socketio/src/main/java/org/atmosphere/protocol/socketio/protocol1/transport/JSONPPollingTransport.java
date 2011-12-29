package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class JSONPPollingTransport extends XHRTransport {
	public static final String TRANSPORT_NAME = "jsonp-polling";
	
	private static final Logger logger = LoggerFactory.getLogger(JSONPPollingTransport.class);
	
	private long jsonpIndex = 0;

	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			/*
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.j["+ jsonpIndex +"](\"");
			*/
		}

		@Override
		protected void writeData(ServletResponse response, String data) throws IOException {
			//response.getOutputStream().print(data);
			logger.error("calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
			response.setContentType("text/javascript; charset=UTF-8");
			response.getOutputStream().print("io.j["+ jsonpIndex +"](\"" + data + "\");");
			
			logger.error("WRITE SUCCESS calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
		}

		protected void finishSend(ServletResponse response) throws IOException {
			//response.getOutputStream().print("\");");
			response.flushBuffer();
		}

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			
			/*  i=0   */
			
			if(request.getParameter("i")!=null){
				jsonpIndex = Integer.parseInt(request.getParameter("i"));
			} else {
				jsonpIndex = 0;
			}
			
			//startSend(response);
	    	
	    	writeData(response, "1::");
	    	
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, session.getSessionId()));
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + REQUEST_TIMEOUT));
		}
	}
	
	public JSONPPollingTransport(int bufferSize, int maxIdleTime) {
		super(bufferSize, maxIdleTime);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}
	

	protected XHRPollingSessionHelper createHelper(SocketIOSession session) {
		return new XHRPollingSessionHelper(session);
	}

	@Override
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, org.atmosphere.protocol.socketio.SocketIOSession.Factory sessionFactory) throws IOException {
		
		if(session==null){
			session = sessionFactory.createSession(resource, atmosphereHandler);
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SOCKETIO_SESSION_ID, session.getSessionId());
			
			// pour le broadcast
			resource.getRequest().setAttribute(SocketIOAtmosphereHandler.SocketIOSessionOutbound, atmosphereHandler);
		}
		
		XHRPollingSessionHelper handler = createHelper(session);
		handler.connect(resource, atmosphereHandler);
		return session;
	}
	
	@Override
	protected SocketIOSession connect(AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, org.atmosphere.protocol.socketio.SocketIOSession.Factory sessionFactory) throws IOException {
		return connect(null, resource, atmosphereHandler, sessionFactory);
	}
}
