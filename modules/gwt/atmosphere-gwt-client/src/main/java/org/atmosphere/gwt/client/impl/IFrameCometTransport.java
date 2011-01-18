/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JsArrayString;
import com.google.gwt.dom.client.BodyElement;
import com.google.gwt.dom.client.IFrameElement;
import com.google.gwt.dom.client.Node;
import com.google.gwt.dom.client.NodeList;
import com.google.gwt.user.client.rpc.SerializationException;
import com.google.gwt.user.client.rpc.StatusCodeException;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereClientException;
import org.atmosphere.gwt.client.AtmosphereListener;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

/**
 *
 * @author p.havelaar
 */
public class IFrameCometTransport extends BaseCometTransport {

	private String domain;
	private IFrameElement iframe;
	private BodyElement body;
	private boolean connected;
	private boolean expectingDisconnection;
	
	@Override
	public void initiate(AtmosphereClient client, AtmosphereListener listener) {
		super.initiate(client, listener);

	}
	
	@Override
	public void connect(int connectionCount) {
        domain = getDomain(getDocumentDomain(), client.getUrl());
		
		StringBuilder html = new StringBuilder("<html>");
		if (domain != null) {
			html.append("<script>document.domain='").append(domain).append("'</script>");
		}
		html.append("<iframe src=''></iframe></html>");
		
		iframe = createIFrame(html.toString());
        
		expectingDisconnection = false;
		String url = getUrl(connectionCount);
		if (domain != null) {
			url += "&d=" + domain;
		}
		iframe.setSrc(url);
	}
	
	@Override
	public void disconnect() {
		// TODO this does not seem to close the connection immediately.
		expectingDisconnection = true;
        super.disconnect();
		iframe.setSrc("");
        // perhaps this will fix disconnecting properly
        destroyIFrame();
        iframe = null;
        body = null;
		if (connected) {
			onDisconnected();
		}
        connected = false;
	}
	
	protected IFrameElement createIFrame(String html) {
        return createIFrameImpl(this, html);
    }
    
    protected void destroyIFrame() {}
    
    private static native IFrameElement createIFrameImpl(IFrameCometTransport client, String domain) /*-{
		var div = document.createElement('div'),
            iframe = document.createElement("iframe"),
            span = document.createElement('span');
        iframe.id = '--endless-iframe-comet-transport';
        iframe.setAttribute('name', '--endless-iframe-comet-transport');
        iframe.name = '--endless-iframe-comet-transport';
        iframe.style.visibility = 'hidden';

        span.innerHTML += '<script>document.domain=' + domain + '</script>';

        div.id = '--endless-iframe-comet-transport-div';
        div.style.position = 'absolute';
        div.style.left = iframe.style.top = '0px';
        div.style.width = iframe.style.height = '1px';
        div.style.visibility = 'hidden';
        div.appendChild(span);
        div.appendChild(iframe);
        document.getElementsByTagName('body')[0].appendChild(div);

        window.m = $entry(function(message) {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onMessages(Lcom/google/gwt/core/client/JsArrayString;)(arguments);
        });
        window.c = $entry(function(heartbeat, connectionID) {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onConnected(II)(heartbeat, connectionID);
        });
        window.d = $entry(function() {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onDisconnected()();
        });
        window.e = $entry(function(statusCode, message) {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onError(ILjava/lang/String;)(statusCode, message);
        });
        window.h = $entry(function() {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onHeartbeat()();
        });
        window.r = $entry(function() {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onRefresh()();
        });
        // no $entry() because no user code is reachable
        window.t = function() {
                client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onTerminate()();
        };

        return iframe;
	}-*/;
    
	private native static String getDocumentDomain() /*-{
		return $doc.domain;
	}-*/;
	
	private native static String getDomain(String documentDomain, String url) /*-{
 		var urlParts = /(^https?:)?(\/\/(([^:\/\?#]+)(:(\d+))?))?([^\?#]*)/.exec(url);
        var urlDomain = urlParts[4];
        
        if (!urlDomain || documentDomain == urlDomain) {
        	return null;
        }
        
        var documentDomainParts = documentDomain.split('.');
        var urlDomainParts = urlDomain.split('.');
        
        var d = documentDomainParts.length - 1;
        var u = urlDomainParts.length - 1;
        var resultDomainParts = [];
        
        while (d >= 0 && u >= 0 && documentDomainParts[d] == urlDomainParts[u]) {
        		resultDomainParts.push(urlDomainParts[u]);
        		d--;
        		u--;
        }
        return resultDomainParts.reverse().join('.')
	}-*/;
	
	private void collect() {
        if (body != null) {
            NodeList<Node> childNodes = body.getChildNodes();
            if (childNodes.getLength() > 1) {
                body.removeChild(childNodes.getItem(0));
            }
        }
	}
	
	@SuppressWarnings("unused")
	private void onMessages(JsArrayString arguments) {
		collect();
		int length = arguments.length();
		List<Serializable> messages = new ArrayList<Serializable>(length);
		for (int i = 0; i < length; i++) {
			String message = arguments.get(i);
			switch (message.charAt(0)) {
			case ']':
				messages.add(message.substring(1));
				break;
			case '[':
			case 'R':
			case 'r':
			case 'f':
                try {
                    messages.add(parse(message));
                }
                catch (SerializationException e) {
                    listener.onError(e, true);
                }
				break;
			default:
				listener.onError(new AtmosphereClientException("Invalid message received: " + message), true);
			}
		}
		
		listener.onMessage(messages);
	}
	
	@SuppressWarnings("unused")
	private void onConnected(int heartbeat, int connectionID) {
		connected = true;
		body = iframe.getContentDocument().getBody();
		collect();
        this.connectionId = connectionID;
		listener.onConnected(heartbeat, connectionID);
	}
	
	private void onDisconnected() {
		connected = false;
		body = null;
		if (expectingDisconnection) {
			listener.onDisconnected();
		}
		else {
			listener.onError(new AtmosphereClientException("Unexpected disconnection"), false);
		}
	}
	
	@SuppressWarnings("unused")
	private void onError(int statusCode, String message) {
		listener.onError(new StatusCodeException(statusCode, message), false);
	}
	
	@SuppressWarnings("unused")
	private void onHeartbeat() {
        collect();
		listener.onHeartbeat();
	}
	
	@SuppressWarnings("unused")
	private void onRefresh() {
        collect();
		listener.onRefresh();
	}
	
	@SuppressWarnings("unused")
	private void onTerminate() {
        collect();
		disconnect();
	}
}
