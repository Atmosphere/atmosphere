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
package cometedgwt.auction.client;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import com.google.gwt.core.client.GWT;
import com.google.gwt.json.client.JSONException;
import com.google.gwt.json.client.JSONParser;
import com.google.gwt.json.client.JSONValue;
import com.google.gwt.user.client.DOM;
import com.google.gwt.user.client.Element;
import com.google.gwt.user.client.Timer;
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.ServiceDefTarget;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextArea;

/**
 * This class contains all the internals used by a GWT to interact with the streaming server.
 * 
 * Use it  
 * 
 * @author masini
 *
 */
public class StreamingServiceGWTClientImpl implements StreamingService {

    private int watchDogTimerTime = 100000;
    Map callbacks = new HashMap();
    private boolean keepAlive = false;
    private final String streamingServicePath = GWT.getModuleBaseURL() + "streamingServlet";
    private static StreamingService instance = null;
    private final StreamingServiceInternalGWTAsync service = (StreamingServiceInternalGWTAsync) GWT.create(StreamingServiceInternalGWT.class);
    private final Map waitingSubscriber = new HashMap();
    private final static AsyncCallback voidCallback = new AsyncCallback() {

        public void onFailure(Throwable caught) {
        }

        public void onSuccess(Object result) {
        }
    };
    private final AsyncCallback restartStreamingCallback = new AsyncCallback() {

        public void onFailure(Throwable caught) {
        }

        public void onSuccess(Object result) {
            restartStreamingFromIFrame();
            callback("restartStreaming", (String) result);
        }
    };
    /**
     * Receive hearthbeats from the streaming server. It sets keepAlive flag to true to prevent the Timer 
     * to restart the streaming and then call the client callback (maybe the client has to do something
     * with the heartbeat itself). 
     */
    private final AsyncCallback internalKeepAliveCallback = new AsyncCallback() {

        public void onFailure(Throwable caught) {
        }

        public void onSuccess(Object result) {

            alert("keepAlive");
            keepAlive = true;
            watchDogTimerTime = 10 * Integer.parseInt(result.toString());

            for (Iterator iter = waitingSubscriber.entrySet().iterator(); iter.hasNext();) {
                Entry callback = (Entry) iter.next();

                /*
                Take care, the Map implementation can be different from his Java counterpart.
                I think it uses object identity instead of object equality, so for instance two equals
                String done in different way can lend to two different keys in the map.
                Try this

                void testMap(Object key, Object value)
                {
                Map test = new HashMap();
                test.put(key.toString(),value);

                if(!test.containsKey("testKey"))
                {
                GWT.log("bug in map found !!!",null);
                }
                }

                If called this way:

                testMap("testKey", new Object());

                that code will print the warning row !!!!!
                Should be fixed.

                 */
                subScribeToEvent((String) callback.getKey(), (AsyncCallback) callback.getValue());

                iter.remove();
            }

            callback("keepAlive", "");
        }
    };

    /*
     *  Sharing StreamingService instance can be a problem with two Hosted browser that share the same GWT Shell,
     *  but not with real browser.
     */
    public static StreamingService getInstance() {
        if (instance == null) {
            instance = new StreamingServiceGWTClientImpl();
        }

        return instance;
    }

    private StreamingServiceGWTClientImpl() {

        callbacks.put("keepAliveInternal", internalKeepAliveCallback);
        callbacks.put("restartStreamingInternal", restartStreamingCallback);

        ((ServiceDefTarget) service).setServiceEntryPoint(GWT.getModuleBaseURL() + "streamingService");

        setUpNativeCode(this);

        restartStreamingFromIFrame();

        createWatchDogTimer();
    }

    /**
     * If we have a callback for the "event" (and we should if we subscribed) then let's call it.
     * 
     * @param event: contains the event we subscribed
     * @param data: datas that come from the server
     */
    private void callback(String topicName, String data) {
        keepAlive = true;

        alert("received callback for (" + topicName + "," + data + ")");

        if (callbacks.containsKey(topicName)) {
            AsyncCallback callback = (AsyncCallback) callbacks.get(topicName);

            try {
                Object dataToSend = data;

                if (data.startsWith("$JSONSTART$") && data.endsWith("$JSONEND$")) {
                    dataToSend = JSONParser.parse(data.substring("$JSONSTART$".length(), data.length() - "$JSONEND$".length()));
                }

                callback.onSuccess(dataToSend);
            } catch (JSONException e) {
                callback.onFailure(e);
            }
        } else {
            alert("received event for a not subscribed topic: '" + topicName + "'");
            alert("current topics are: " + callbacks.keySet());
        }
    }

    /**
     * Setup the two Javascript method used for the callback from the server
     *
     * @param thisInstance: a trick, because I was unable to use this !!!
     */
    private native void setUpNativeCode(StreamingService thisInstance) /*-{
    $wnd.callback = function(topicName, data)
    {
    thisInstance.@cometedgwt.auction.client.StreamingServiceGWTClientImpl::callback(Ljava/lang/String;Ljava/lang/String;)(topicName,data);
    }
    }-*/;

    //thisInstance.@org.gwtcomet.client.StreamingServiceGWTClientImpl::callback(Ljava/lang/String;Ljava/lang/String;)(topicName,data);
    /**
     * A Timer that every 20s check
     * if everything is working.
     *
     */
    private void createWatchDogTimer() {
        Timer t = new Timer() {

            public void run() {
                if (!keepAlive) {
                    alert("the dog is angry !!! Awake streaming !!!");
                    restartStreamingFromIFrame();
                }

                keepAlive = false;
            }
        };
        t.scheduleRepeating(watchDogTimerTime);
    }

    /**
     * Uses DOM to create, if necessary, the iframe, then sets the src attribute to start
     * the streaming. It's important to clear the "old" iframe when restarting, or spurios
     * Javascript can send old event to the callback method
     */
    private void restartStreamingFromIFrame() {
        Element iframe = DOM.getElementById("__gwt_streamingFrame");

        if (iframe != null) {
            DOM.removeChild(RootPanel.getBodyElement(), iframe);
        }

        iframe = DOM.createIFrame();
        DOM.setAttribute(iframe, "id", "__gwt_streamingFrame");
        DOM.setStyleAttribute(iframe, "width", "0");
        DOM.setStyleAttribute(iframe, "height", "0");
        DOM.setStyleAttribute(iframe, "border", "0");

        DOM.appendChild(RootPanel.getBodyElement(), iframe);

        DOM.setAttribute(iframe, "src", streamingServicePath);
    }

    /* (non-Javadoc)
     * @see org.gwtcomet.client.StreamingService#sendMessage(java.lang.String, java.lang.String)
     */
    public void sendMessage(String topicName, String data) {
        service.sendMessage(topicName, data, voidCallback);
    }

    /* (non-Javadoc)
     * @see org.gwtcomet.client.StreamingService#sendMessage(java.lang.String, com.google.gwt.sample.json.client.JSONValue)
     */
    public void sendMessage(String topicName, JSONValue object) {
        sendMessage(topicName, "$JSONSTART$" + object.toString() + "$JSONEND$");
    }

    /* (non-Javadoc)
     * @see org.gwtcomet.client.StreamingService#subScribeToEvent(java.lang.String, com.google.gwt.user.client.rpc.AsyncCallback)
     */
    public void subScribeToEvent(String topicName, AsyncCallback callback) {
        if (keepAlive) {
            alert("Streaming is alive, subscribing to '" + topicName + "' with callback " + callback);
            service.subscribeToTopic(topicName, voidCallback);
            callbacks.put(topicName, callback);

            alert(callbacks.toString());
        } else {
            alert("Streaming is not alive, subscriber '" + topicName + "' is cached with callback " + callback + " until online");

            waitingSubscriber.put(topicName, callback);
        }
    }
    private final TextArea textArea = new TextArea();

    private void alert(String message) {
        if (GWT.isScript()) {
            RootPanel debugDiv = RootPanel.get("debug");
            if (debugDiv != null) {
//				if(debugDiv.getWidgetIndex(textArea)==-1)
//				{
//					textArea.setVisibleLines(30);
//					textArea.setWidth("100%");
//					textArea.setText("");
//					debugDiv.add(textArea);
//				}
//				
//				textArea.setText(textArea.getText()+"\n"+new Date()+"("+System.currentTimeMillis()+"):"+message);
            }
        } else {
            GWT.log(message, null);
        }
    }
}
