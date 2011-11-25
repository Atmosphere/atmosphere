package org.atmosphere.protocol.socketio;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.Map;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;

import org.atmosphere.cpr.AsynchronousProcessor;
import org.atmosphere.cpr.AtmosphereHandler;
import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.atmosphere.cpr.AtmosphereResourceImpl;
import org.atmosphere.cpr.AtmosphereServlet;
import org.atmosphere.cpr.FrameworkConfig;
import org.atmosphere.cpr.AtmosphereServlet.Action;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereConfig;
import org.atmosphere.cpr.AtmosphereServlet.AtmosphereHandlerWrapper;
import org.atmosphere.protocol.socketio.transport.SocketIOSession;
import org.atmosphere.protocol.socketio.transport.Transport;
import org.atmosphere.websocket.AtmosphereWebSocketFactory;
import org.eclipse.jetty.websocket.WebSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.atmosphere.cpr.ApplicationConfig.MAX_INACTIVE;
import static org.atmosphere.cpr.HeaderConfig.X_ATMOSPHERE_ERROR;

// on pourrait problablement juset avoir un interface : pourrait etre utile
// pour le destroy dans AtmosphereServlet
public class SocketIOCometSupport extends AsynchronousProcessor {
	
	private static final Logger logger = LoggerFactory.getLogger(SocketIOCometSupport.class);
	
	private AsynchronousProcessor containerWrapper = null;
	private SocketIOSessionManager sessionManager1 = null;
	
	private AtmosphereWebSocketFactory webSocketFactory = null;
	
	private Map<String, Transport> transports = new HashMap<String, Transport>();
	
	public static final String SOCKETIOEVENTLISTENER = "SOCKETIOEVENTLISTENER";
	
	public static final String BUFFER_SIZE_INIT_PARAM = "bufferSize";
	public static final String MAX_IDLE_TIME_INIT_PARAM = "maxIdleTime";
	public static final int BUFFER_SIZE_DEFAULT = 8192;
	public static final int MAX_IDLE_TIME_DEFAULT = 300*1000;
	
	
	public int bufferSize = BUFFER_SIZE_DEFAULT;
	public int maxIdleTime = MAX_IDLE_TIME_DEFAULT;
	
	private int heartbeatInterval = 15;
	private int timeout = 2500;
	
	public SocketIOCometSupport(AtmosphereConfig config, AsynchronousProcessor container) {
		super(config);
		containerWrapper = container;
		containerWrapper.setIProcessor(this);
		
		sessionManager1 = new org.atmosphere.protocol.socketio.protocol1.transport.SocketIOSessionManagerImpl();
		
		config.getServlet().setWebSocketProtocolClassName("org.atmosphere.protocol.socketio.SocketIOWebSocketProtocol");
		
		webSocketFactory = new AtmosphereWebSocketFactory(config);
	}
	
	@Override
	public Action service(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		
		return containerWrapper.service(request, response);
	}
	
	
	@Override
	public Action processAction(AsynchronousProcessor processor, HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		
		AtmosphereHandlerWrapper handlerWrapper = map(request);
        
		AtmosphereHandler atmosphereHandler = (AtmosphereHandler)request.getAttribute(FrameworkConfig.ATMOSPHERE_HANDLER);
		AtmosphereResourceImpl resource = (AtmosphereResourceImpl)request.getAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE);
		
		if (supportSession() && (resource==null)) {
            // Create the session needed to support the Resume
            // operation from disparate requests.
            HttpSession session = request.getSession(true);
            // Do not allow times out.
            if (session.getMaxInactiveInterval() == AsynchronousProcessor.DEFAULT_SESSION_TIMEOUT) {
                session.setMaxInactiveInterval(-1);
            }
        }
		
        request.setAttribute(FrameworkConfig.SUPPORT_SESSION, supportSession());

		if(resource==null || atmosphereHandler==null){
			handlerWrapper.broadcaster.getBroadcasterConfig().setAtmosphereConfig(config);
			
	        resource = new AtmosphereResourceImpl(config, handlerWrapper.broadcaster, request, response, this, handlerWrapper.atmosphereHandler);
	        atmosphereHandler = handlerWrapper.atmosphereHandler;
	        		
	        request.setAttribute(FrameworkConfig.ATMOSPHERE_RESOURCE, resource);
	        request.setAttribute(FrameworkConfig.ATMOSPHERE_HANDLER, atmosphereHandler);
	        
		} else {
			//handlerWrapper = resource.getAtmosphereHandler();
		}
		
        if(atmosphereHandler instanceof SocketIOAtmosphereHandler){
        	
        	// on trouve le transport
        	String path = request.getPathInfo();
        	if (path == null || path.length() == 0 || "/".equals(path)) {
        		response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
        		// TODO faut voir ce qu'on fait ici
        		return null;
        	}
        	
        	if (path.startsWith("/")){
        		path = path.substring(1);
        	}
        	
        	// A VOIR SI C'EST PAS JUSTE POUR UN GET
        	
        	String[] parts = path.split("/");
        	
        	String protocol = null;
        	String version = null;
        	
        	// ici on detecte la version du protocol.
        	if(parts.length==0){
        		return null;
        	} else if(parts.length==1){
        		
        		// est-ce la version du protocol ?
        		if(parts[0].length()==1){
        			version = parts[0];
        			//must be a digit
        			if(!Character.isDigit(version.charAt(0))){
        				version = null;
        			}
        		} else {
        			protocol = parts[0];
        		}
        		
        	} else {
        		// un ex  :[1, xhr-polling, 7589995670715459]
        		version = parts[0];
        		protocol = parts[1];
        		
        		//must be a digit
    			if(!Character.isDigit(version.charAt(0))){
    				version = null;
    				protocol = null;
    			}
    			
        		
        		
        	}
        	
        	if(protocol==null && version==null){
        		return null;
        	} else if (protocol==null && version!=null){
        		// nous avons un GET ou POST sans le protocol
        		//response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Missing SocketIO transport");
        		
        		response.setStatus(200);
        		
        		
        		SocketIOSession session = getSessionManager(version).createSession(resource, (SocketIOAtmosphereHandler)atmosphereHandler);
        		
        		//response.getWriter().print(session.getSessionId() + ":" + heartbeatInterval + ":" + timeout + ":websocket");
        		response.getWriter().print(session.getSessionId() + ":" + heartbeatInterval + ":" + timeout + ":websocket,flashsocket,htmlfile,xhr-polling,jsonp-polling");
        		
        		return resource.action();
        	} else if(protocol!=null && version==null){
        		version = "0";
        	}
        	
        	Transport transport = transports.get(protocol + "-" + version);
        	
        	if(transport!=null){
        		transport.handle(processor, resource, (SocketIOAtmosphereHandler)atmosphereHandler, getSessionManager(version));
        	} else {
        		logger.error("Protocol not supported : " + protocol);
        	}
        	
        } else {
	        try {
	        	// Ce n'est pas un Socket IO Handler
	            handlerWrapper.atmosphereHandler.onRequest(resource);
	            
	        } catch (IOException t) {
	            resource.onThrowable(t);
	            throw t;
	        }
        }

        if (resource.getAtmosphereResourceEvent().isSuspended()) {
        	request.setAttribute(MAX_INACTIVE, System.currentTimeMillis());
            aliveRequests.put(request, resource);
        }
        return resource.action();
	}
	
	private SocketIOSessionManager getSessionManager(String version){
		
		if(version.equals("1")){
			return sessionManager1;
		}
		
		return null;
	}
	
	@Override
	public void init(ServletConfig sc) throws ServletException {
		containerWrapper.init(sc);
		
		String str = sc.getInitParameter(BUFFER_SIZE_INIT_PARAM);
		int bufferSize = str==null ? BUFFER_SIZE_DEFAULT : Integer.parseInt(str);
		str = sc.getInitParameter(MAX_IDLE_TIME_INIT_PARAM);
		int maxIdleTime = str==null ? MAX_IDLE_TIME_DEFAULT : Integer.parseInt(str);
		
		// VERSION 1
		org.atmosphere.protocol.socketio.protocol1.transport.WebSocketTransport websocketTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.WebSocketTransport(bufferSize, maxIdleTime);
		org.atmosphere.protocol.socketio.protocol1.transport.FlashSocketTransport flashsocketTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.FlashSocketTransport(bufferSize, maxIdleTime);
		org.atmosphere.protocol.socketio.protocol1.transport.HTMLFileTransport htmlFileTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.HTMLFileTransport(bufferSize, maxIdleTime);
		org.atmosphere.protocol.socketio.protocol1.transport.XHRPollingTransport xhrPollingTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.XHRPollingTransport(bufferSize, maxIdleTime);
		org.atmosphere.protocol.socketio.protocol1.transport.JSONPPollingTransport jsonpPollingTransport1 = new org.atmosphere.protocol.socketio.protocol1.transport.JSONPPollingTransport(bufferSize, maxIdleTime);
		transports.put(websocketTransport1.getName()+ "-1", websocketTransport1);
		transports.put(flashsocketTransport1.getName()+ "-1", flashsocketTransport1);
		transports.put(htmlFileTransport1.getName()+ "-1", htmlFileTransport1);
		transports.put(xhrPollingTransport1.getName()+ "-1", xhrPollingTransport1);
		transports.put(jsonpPollingTransport1.getName()+ "-1", jsonpPollingTransport1);
		
		for (Transport t: transports.values()) {
			t.init(sc);
		}
		
		
	}

	@Override
	public boolean supportSession() {
		return containerWrapper.supportSession();
	}

	@Override
	public String getContainerName() {
		return containerWrapper.getContainerName();
	}

	@Override
	public Action suspended(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		return containerWrapper.suspended(request, response);
	}

	@Override
	public void action(AtmosphereResourceImpl r) {
		containerWrapper.action(r);
	}

	@Override
	public AtmosphereHandlerWrapper map(HttpServletRequest req) throws ServletException {
		return containerWrapper.map(req);
	}

	@Override
	public Action resumed(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		return containerWrapper.resumed(request, response);
	}

	@Override
	public Action timedout(HttpServletRequest request, HttpServletResponse response) throws IOException, ServletException {
		return containerWrapper.timedout(request, response);
	}

	@Override
	public Action cancelled(HttpServletRequest req, HttpServletResponse res) throws IOException, ServletException {
		return containerWrapper.cancelled(req, res);
	}

	@Override
	public boolean supportWebSocket() {
		return containerWrapper.supportWebSocket();
	}
	
	private void copy(InputStream in, OutputStream out) throws IOException {
		int bufferSize = 2*8192;
		
		byte buffer[] = new byte[bufferSize];
        int len=bufferSize;
        
        while (true)
        {
            len=in.read(buffer,0,bufferSize);
            if (len<0 )
                break;
            out.write(buffer,0,len);
        }
	}
	
	public WebSocketFactory getWebSocketFactory(){
    	return webSocketFactory.getFactory();
    }
	
}
