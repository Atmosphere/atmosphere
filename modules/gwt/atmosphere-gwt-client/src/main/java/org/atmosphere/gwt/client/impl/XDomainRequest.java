
/*
 * Copyright 2011 Jeanfrancois Arcand
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.atmosphere.gwt.client.impl;

import com.google.gwt.core.client.JavaScriptObject;

/**
 * References: 
 * - http://blogs.msdn.com/b/ieinternals/archive/2010/04/06/comet-streaming-in-internet-explorer-with-xmlhttprequest-and-xdomainrequest.aspx
 * - http://msdn.microsoft.com/en-us/library/cc288060(VS.85).aspx
 * - http://blogs.msdn.com/b/ieinternals/archive/2010/05/13/xdomainrequest-restrictions-limitations-and-workarounds.aspx
 *
 * @author p.havelaar
 */
public class XDomainRequest extends JavaScriptObject {

    public static native XDomainRequest create() /*-{
       // XDomainRequest object does not play well with GWT JavaScriptObject so store in local variable
       var me = new Object();
       me.request = new XDomainRequest();
       return me;
    }-*/;
    
    public native static boolean isSupported() /*-{
       if ($wnd.XDomainRequest) {
           return true;
       } else {
           return false;
       }
    }-*/;
    
    public final native void setListener(XDomainRequestListener listener) /*-{
        var self = this;
        this.request.onload = function() {
            listener.@org.atmosphere.gwt.client.impl.XDomainRequestListener::onLoad(Lorg/atmosphere/gwt/client/impl/XDomainRequest;Ljava/lang/String;)(self,self.request.responseText);
        };
        this.request.onprogress = function() {
            listener.@org.atmosphere.gwt.client.impl.XDomainRequestListener::onProgress(Lorg/atmosphere/gwt/client/impl/XDomainRequest;Ljava/lang/String;)(self,self.request.responseText);
        };
        this.request.ontimeout = function() {
            listener.@org.atmosphere.gwt.client.impl.XDomainRequestListener::onTimeout(Lorg/atmosphere/gwt/client/impl/XDomainRequest;)(self);
        };
        this.request.onerror = function() {
            listener.@org.atmosphere.gwt.client.impl.XDomainRequestListener::onError(Lorg/atmosphere/gwt/client/impl/XDomainRequest;)(self);
        };
    }-*/;

    
    public final native void clearListener() /*-{
        var self = this;
        $wnd.setTimeout(function() {
          // Using a function literal here leaks memory on ie6
          // Using the same function object kills HtmlUnit
          self.request.onload = new Function();
          self.request.onprogress = new Function();
          self.request.ontimeout = new Function();
          self.request.onerror = new Function();
        }, 0);
    }-*/;


    public final native String getContentType() /*-{
        return this.request.contentType;
    }-*/;
    
    /**
     * 
     * @return the body of the response returned by the server.
     */
    public final native String getResponseText() /*-{
        return this.request.responseText;
    }-*/;
    
    /**
     * set the timeout in milliseconds
     * @param timeout 
     */
    public final native void setTimeout(int timeout) /*-{
        this.request.timeout = timeout;
    }-*/;
    
    public final native int getTimeout() /*-{
        return this.request.timeout;
    }-*/;
    
    /**
     * The abort method terminates a pending send.
     */
    public final native void abort() /*-{
        this.request.abort();
    }-*/;
    
    /**
     * Creates a connection with a domain's server.
     * @param url 
     */
    public final native void openGET(String url) /*-{
        this.request.open("GET", url);
    }-*/;
            
    /**
     * Creates a connection with a domain's server.
     * @param url 
     */
    public final native void openPOST(String url) /*-{
        this.request.open("POST", url);
    }-*/;
    
    /**
     * Transmits a empty string to the server for processing.
     */
    public final native void send() /*-{
        this.request.send();
    }-*/;
    
    /**
     * Transmits a data string to the server for processing.
     * @param data 
     */
    public final native void send(String data) /*-{
        this.request.send(data);
    }-*/;
    
    protected XDomainRequest() {
    }

}
