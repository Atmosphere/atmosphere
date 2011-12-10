package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;

import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class XHRPollingTransport extends XHRTransport {
	
	private static final Logger logger = LoggerFactory.getLogger(XHRPollingTransport.class);
	
	public static final String TRANSPORT_NAME = "xhr-polling";

	public XHRPollingTransport(int bufferSize, int maxIdleTime) {
		super(bufferSize, maxIdleTime);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

	protected XHRPollingSessionHelper createHelper(SocketIOSession session) {
		return new XHRPollingSessionHelper(session);
	}
	
	protected class XHRPollingSessionHelper extends XHRSessionHelper {

		XHRPollingSessionHelper(SocketIOSession session) {
			super(session, false);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			response.setContentType("text/plain; charset=UTF-8");
		}

		@Override
		protected void writeData(ServletResponse response, String data) throws IOException {
			
			logger.error("calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
			
			response.getOutputStream().print(data);
			response.flushBuffer();
			logger.error("WRITE SUCCESS calling from " + this.getClass().getName() + " : " + "writeData(string) = " + data);
		}

		protected void finishSend(ServletResponse response) throws IOException {};

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			startSend(response);
			//writeData(response, "1::"  + request.getRequestURI() + "/"+ request.getQueryString());
			writeData(response, "1::");
		}

	}

}
