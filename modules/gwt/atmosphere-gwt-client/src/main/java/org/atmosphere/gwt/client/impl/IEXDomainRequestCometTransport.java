/*
 *
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2007-2008 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 *
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
