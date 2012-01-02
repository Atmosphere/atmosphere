package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.util.Arrays;

import javax.servlet.ServletOutputStream;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.protocol.socketio.SocketIOAtmosphereHandler;
import org.atmosphere.protocol.socketio.SocketIOSession;
import org.atmosphere.protocol.socketio.SocketIOSessionFactory;
import org.codehaus.jackson.map.ObjectMapper;

public class HTMLFileTransport extends XHRTransport {
	public static final String TRANSPORT_NAME = "htmlfile";
	private static ObjectMapper mapper = new ObjectMapper();

	private class HTMLFileSessionHelper extends XHRSessionHelper {

		HTMLFileSessionHelper(SocketIOSession session) {
			super(session, true);
		}

		protected void startSend(HttpServletResponse response) throws IOException {
			response.setContentType("text/html");
			response.setHeader("Connection", "keep-alive");
			response.setHeader("Transfer-Encoding", "chunked");
			char[] spaces = new char[244];
			Arrays.fill(spaces, ' ');
			ServletOutputStream os = response.getOutputStream();
			os.print("<html><body>" + new String(spaces));
			response.flushBuffer();
		}
		
		protected void writeData(ServletResponse response, String data) throws IOException {
			response.getOutputStream().print("<script>parent.s._("+ mapper.writeValueAsString(data) +", document);</script>");
			response.flushBuffer();
		}

		protected void finishSend(ServletResponse response) throws IOException {};

		protected void customConnect(HttpServletRequest request,
				HttpServletResponse response) throws IOException {
			startSend(response);
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.SESSION_ID, 0, session.getSessionId()));
			//writeData(response, SocketIOFrame.encode(SocketIOFrame.FrameType.HEARTBEAT_INTERVAL, 0, "" + HEARTBEAT_DELAY));
		}
	}

	public HTMLFileTransport(int bufferSize, int maxIdleTime) {
		super(bufferSize, maxIdleTime);
	}

	@Override
	public String getName() {
		return TRANSPORT_NAME;
	}

	protected XHRSessionHelper createHelper(SocketIOSession session) {
		return new HTMLFileSessionHelper(session);
	}

	@Override
	protected SocketIOSession connect(SocketIOSession session, AtmosphereResourceImpl resource, SocketIOAtmosphereHandler<HttpServletRequest, HttpServletResponse> atmosphereHandler, SocketIOSessionFactory sessionFactory) throws IOException {
		// TODO Auto-generated method stub
		return null;
	}
}
