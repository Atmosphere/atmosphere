/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JavaScriptException;
import com.google.gwt.http.client.RequestException;
import com.google.gwt.http.client.Response;
import com.google.gwt.user.client.Cookies;
import com.google.gwt.user.client.rpc.StatusCodeException;
import org.atmosphere.gwt.client.TimeoutException;

/**
 *
 * @author p.havelaar
 */
public class IEXDomainRequestCometTransport extends StreamingProtocolTransport {

    @Override
    public void connect(int connectionCount) {
        init();
        try {
            transportRequest = XDomainRequest.create();
            transportRequest.setListener(xDomainRequestListener);
            transportRequest.openGET(getUrl(connectionCount));
            transportRequest.send();
            
        } catch (JavaScriptException ex) {
            if (transportRequest != null) {
                transportRequest.abort();
                transportRequest = null;
            }
            listener.onError(new RequestException(ex.getMessage()), false);
        }
    }

	@Override
	public void disconnect() {
		aborted = true;
		expectingDisconnection = true;
        super.disconnect();
		if (transportRequest != null) {
            transportRequest.clearListener();
            transportRequest.abort();
            transportRequest = null;
		}
        listener.onDisconnected();
	}

    /**
     * add a session cookie to the url
     * @param connectionCount
     * @return 
     */
    @Override
    public String getUrl(int connectionCount) {
        String url = super.getUrl(connectionCount);
        // Detect if we have a session in a cookie and pass it on the url, because XDomainRequest does not
        // send cookies
        if (url.toLowerCase().contains(";jsessionid") == false) {
            String sessionid = Cookies.getCookie("JSESSIONID");
            if (sessionid != null) {
                String parm = ";jsessionid=" + sessionid;
                int p = url.indexOf('?');
                if (p > 0) {
                    return url.substring(0, p) + parm + url.substring(p);
                } else {
                    return url + parm;
                }
            }
        }
        if (!url.toUpperCase().contains("PHPSESSID")) {
            String sessionid = Cookies.getCookie("PHPSESSID");
            if (sessionid != null) {
                int p = url.indexOf('?');
                String param = "PHPSESSID=" + sessionid;
                if (p > 0) {
                    return url.substring(0, p + 1) + param + "&" + url.substring(p + 1);
                } else {
                    return url + "?" + param;
                }
            }
        }

        return url;
    }
    
    private XDomainRequest transportRequest;
    private XDomainRequestListener xDomainRequestListener = new XDomainRequestListener() {

        @Override
        public void onError(XDomainRequest request) {
            if (isCurrent(request)) {
                expectingDisconnection = true;
                listener.onError(new StatusCodeException(Response.SC_INTERNAL_SERVER_ERROR, ""), true);
                transportRequest = null;
            }
        }

        @Override
        public void onLoad(XDomainRequest request, String responseText) {
            request.clearListener();
            if (isCurrent(request)) {
                transportRequest = null;
                if (!aborted) {
                    onReceiving(Response.SC_OK, responseText, false);
                }
            }
        }

        @Override
        public void onProgress(XDomainRequest request, String responseText) {
            if (!aborted && isCurrent(request)) {
                onReceiving(Response.SC_OK, responseText, true);
            } else {
                request.clearListener();
                request.abort();
                if (isCurrent(request)) {
                    transportRequest = null;
                }
            }
        }

        @Override
        public void onTimeout(XDomainRequest request) {
            if (isCurrent(request)) {
                if (!expectingDisconnection) {
                    listener.onError(
                            new TimeoutException("Unexpected connection timeout", request.getTimeout())
                            , false);
                }
            }
        }
        
        public boolean isCurrent(XDomainRequest request) {
            return request == transportRequest;
        }
    };
}
