package org.atmosphere.protocol.socketio.protocol1.transport;

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.io.Writer;
import java.net.URLDecoder;
import java.util.List;

import javax.servlet.ServletConfig;
import javax.servlet.http.HttpServletRequest;

import org.atmosphere.protocol.socketio.transport.Transport;
import org.atmosphere.util.uri.UriComponent;

public abstract class AbstractTransport implements Transport {
	
	public static final String POST_MESSAGE_RECEIVED = "POST_MESSAGE_RECEIVED";
	
	protected String extractSessionId(HttpServletRequest request) {
		String path = request.getPathInfo();

		if (path != null && path.length() > 0 && !"/".equals(path)) {
			if (path.startsWith("/"))
				path = path.substring(1);
			String[] parts = path.split("/");
			if (parts.length >= 2) {

				// on doit valider que le path est le meme que dans le URI
				String requestURI = request.getRequestURI();

				String protocol = parts[1];

				parts = requestURI.substring(requestURI.indexOf(protocol)).split("/");
				if (parts.length >= 2) {
					return parts[1] == null ? null
							: (parts[1].length() == 0 ? null : parts[1]);
				} else {
					return null;
				}
			}
		}
		return null;
	}

	public static String extractString(Reader reader) {

		String output = null;

		try {
			Writer writer = new StringWriter();

			char[] buffer = new char[1024];
			int n;
			while ((n = reader.read(buffer)) != -1) {
				writer.write(buffer, 0, n);
			}
			
			output = writer.toString();
			
		} catch (Exception e) {
		} 
		
		return output;

	}

	@Override
	public void init(ServletConfig config) {

	}

	@Override
	public void destroy() {

	}
	
	protected String decodePostData(String contentType, String data) {
		if(contentType==null){
			return data;
		} else if (contentType.startsWith("application/x-www-form-urlencoded")) {
			if (data.length()>2 && data.substring(0, 2).startsWith("d=")){
				String extractedData = data.substring(3);
				try {
						extractedData = URLDecoder.decode(extractedData, "UTF-8");
						if(extractedData!=null && extractedData.length()>2){
							// on trim les "" et remplace les \" par "
							if(extractedData.charAt(0)=='\"' && extractedData.charAt(extractedData.length()-1)=='\"'){
								
								extractedData = extractedData.substring(1,extractedData.length()-1).replaceAll("\\\\\"", "\""); 
							}
						}

				} catch (UnsupportedEncodingException e) {
					// TODO Auto-generated catch block
					e.printStackTrace();
				}
				return extractedData;
			} else {
				return data;
			}
		} else if (contentType.startsWith("text/plain")) {
			return data;
		} else {
			// TODO: Treat as text for now, maybe error in the future.
			return data;
		}
	}
	
	protected boolean isDisconnectRequest(HttpServletRequest request){
		// on commence par detecter si c'est un DISCONNECT
		// si c'est le cas, il faut terminer la connection en cours
		if ("GET".equals(request.getMethod())) {
			
			if(request.getParameterMap().containsKey("disconnect")){
				return true;
			}
			
		} else if ("POST".equals(request.getMethod())) {
			try {
				String data = decodePostData(request.getContentType(), extractString(request.getReader()));
				request.setAttribute(POST_MESSAGE_RECEIVED, data);
				if (data != null && data.length() > 0) {
					List<SocketIOEvent> list = SocketIOEvent.parse(data);
					if(!list.isEmpty()){
						if(SocketIOEvent.FrameType.DISCONNECT.equals(list.get(0).getFrameType())){
							return true;
						}
					}
					
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			
		}
		
		return false;
	}
}
