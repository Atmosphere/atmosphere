/*
 * Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.samples.client;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.core.client.Scheduler.ScheduledCommand;
import com.google.gwt.dom.client.Document;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.user.client.Cookies;
import com.google.gwt.user.client.rpc.StatusCodeException;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.RootPanel;
import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.AtmosphereListener;

import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.atmosphere.gwt.client.extra.AtmosphereProxy;
import org.atmosphere.gwt.client.extra.Window;
import org.atmosphere.gwt.client.extra.WindowFeatures;

/**
 * @author p.havelaar
 */
public class GWTDemo implements EntryPoint {

    static final Logger logger = Logger.getLogger(GWTDemo.class.getName());

    int count = 0;
    
    Button startButton;
    Button stopButton;
    AtmosphereProxy proxy;

    @Override
    public void onModuleLoad() {

        Button broadcast = new Button("Broadcast");
        broadcast.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                proxy.broadcast(event(count++, "Send from client using broadcast"));
            }
        });

        Button post = new Button("Post");
        post.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                proxy.post(event(count++, "Send from client using post"));
            }
        });

        Button localBroadcast = new Button("Local Broadcast");
        localBroadcast.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                proxy.localBroadcast(event(count++, "Send from client using local broadcast"));
            }
        });

        Button newWindow = new Button("Create window");
        newWindow.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                Scheduler.get().scheduleDeferred(new ScheduledCommand() {
                    @Override
                    public void execute() {
                        Window.current().open(Document.get().getURL(), "_blank", 
                                new WindowFeatures().setStatus(true).setResizable(true));
                    }
                });
            }
        });


        RootPanel.get("buttons").add(broadcast);
        RootPanel.get("buttons").add(post);
        RootPanel.get("buttons").add(localBroadcast);
        RootPanel.get("buttons").add(newWindow);
    
        initialize();

    }

    public void initialize() {

        MyCometListener cometListener = new MyCometListener();

        AtmosphereGWTSerializer serializer = GWT.create(EventSerializer.class);
        // set a small length parameter to force refreshes
        // normally you should remove the length parameter
        proxy = new AtmosphereProxy(GWT.getModuleBaseURL() + "gwtComet", serializer, cometListener);
    }
    
    Event event(long count, String message) {
        Event bean = new Event();
        bean.setCode(count);
        bean.setData(message);
        return bean;
    }
    
    void toggleStartStop(boolean clientConnected) {
//        startButton.setEnabled(!clientConnected);
//        stopButton.setEnabled(clientConnected);
    }

    public static void displayCookies() {
        StringBuilder cookies = new StringBuilder();
        for (String name : Cookies.getCookieNames()) {
            String value = Cookies.getCookie(name);
            cookies.append(name).append(" = ").append(value).append("<br/>");
        }
        Info.display("Cookies", cookies.toString());        
    }

    private class MyCometListener implements AtmosphereListener {

        @Override
        public void onConnected(int heartbeat, int connectionID) {
            logger.info("comet.connected [" + heartbeat + ", " + connectionID + "]");
            displayCookies();
            toggleStartStop(true);
        }

        @Override
        public void onBeforeDisconnected() {
            logger.log(Level.INFO, "comet.beforeDisconnected");
        }

        @Override
        public void onDisconnected() {
            logger.info("comet.disconnected");
            toggleStartStop(false);
        }

        @Override
        public void onError(Throwable exception, boolean connected) {
            int statuscode = -1;
            if (exception instanceof StatusCodeException) {
                statuscode = ((StatusCodeException) exception).getStatusCode();
            }
            logger.log(Level.SEVERE, "comet.error [connected=" + connected + "] (" + statuscode + ")", exception);
            toggleStartStop(connected);
        }

        @Override
        public void onHeartbeat() {
            logger.info("comet.heartbeat");
        }

        @Override
        public void onRefresh() {
            logger.info("comet.refresh");
        }

        @Override
        public void onAfterRefresh() {
            logger.info("comet.afterRefresh");
        }

        @Override
        public void onMessage(List messages) {
            StringBuilder result = new StringBuilder();
            for (Object obj : messages) {
                result.append(obj.toString()).append("<br/>");
            }
            logger.log(Level.INFO, "comet.message : " + result.toString().replace("<br/>", "\n"));
            Info.display("Received " + messages.size() + " messages", result.toString());
        }
    }

}
