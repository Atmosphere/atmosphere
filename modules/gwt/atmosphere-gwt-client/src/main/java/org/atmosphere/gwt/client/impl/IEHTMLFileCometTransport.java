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
/*
 * Copyright 2009 Richard Zschech.
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


import com.google.gwt.dom.client.IFrameElement;

/**
 * This class uses IE's ActiveX "htmlfile" with an embedded iframe to stream events.
 * http://cometdaily.com/2007/11/18/ie-activexhtmlfile-transport-part-ii/
 * 
 * The main issue with this implementation is that we can't detect initial connection errors. A connection timer is
 * setup to detect this.
 * 
 * Another issues is that the memory required for the iframe constantly grows so the server occasionally disconnects the
 * client who then reestablishes the connection with an empty iframe. To alleviate the issue the client removes script
 * tags as the messages in them have been processed.
 * 
 * The protocol for this transport is a stream of <script> tags with function calls to this transports callbacks as
 * follows:
 * 
 * c(heartbeat) A connection message with the heartbeat duration as an integer
 * 
 * e(error) An error message with the error code
 * 
 * h() A heartbeat message
 * 
 * r() A refresh message
 * 
 * m(string...) string and gwt serialized object messages
 * 
 * string and gwt serialized object messages are Java Script escaped
 * 
 * @author Richard Zschech
 */
public class IEHTMLFileCometTransport extends IFrameCometTransport {
	
    private JavaScriptObject htmlFileWrapper;

    @Override
    protected IFrameElement createIFrame(String html) {
        return createIFrameImpl(this, html);
    }

    @Override
    protected void destroyIFrame() {
        destroyHtmlFile();
    }
	
    
	private static native IFrameElement createIFrameImpl(IFrameCometTransport client, String html) /*-{
		var htmlfile = new ActiveXObject("htmlfile");
		htmlfile.open();
		htmlfile.write(html);
		htmlfile.close();
		
		htmlfile.parentWindow.m = $entry(function(message) {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onMessages(Lcom/google/gwt/core/client/JsArrayString;)(arguments);
		});
		htmlfile.parentWindow.c = $entry(function(heartbeat, connectionID) {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onConnected(II)(heartbeat, connectionID);
		});
		htmlfile.parentWindow.d = $entry(function() {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onDisconnected()();
		});
		htmlfile.parentWindow.e = $entry(function(statusCode, message) {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onError(ILjava/lang/String;)(statusCode, message);
		});
		htmlfile.parentWindow.h = $entry(function() {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onHeartbeat()();
		});
		htmlfile.parentWindow.r = $entry(function() {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onRefresh()();
		});
		// no $entry() because no user code is reachable
		htmlfile.parentWindow.t = function() {
			client.@org.atmosphere.gwt.client.impl.IFrameCometTransport::onTerminate()();
		};
      
        // hold a reference to prevent garbage collection
        var ob = new Object();
        ob.ref = htmlfile;
        client.@org.atmosphere.gwt.client.impl.IEHTMLFileCometTransport::htmlFileWrapper = ob;
		
		return htmlfile.documentElement.getElementsByTagName("iframe").item(0);
	}-*/;
    
    private native void destroyHtmlFile() /*-{
        var htmlfile = this.@org.atmosphere.gwt.client.impl.IEHTMLFileCometTransport::htmlFileWrapper.ref;
        this.@org.atmosphere.gwt.client.impl.IEHTMLFileCometTransport::htmlFileWrapper.ref = null;
        htmlfile.parentWindow.m = new Function();
        htmlfile.parentWindow.c = new Function();
        htmlfile.parentWindow.d = new Function();
        htmlfile.parentWindow.e = new Function();
        htmlfile.parentWindow.h = new Function();
        htmlfile.parentWindow.r = new Function();
        htmlfile.parentWindow.t = new Function();
        htmlfile = null;
        CollectGarbage();
    }-*/;
    
}
