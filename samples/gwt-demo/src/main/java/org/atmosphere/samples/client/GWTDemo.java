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
import com.google.gwt.user.client.rpc.AsyncCallback;
import com.google.gwt.user.client.rpc.StatusCodeException;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.RootPanel;
import org.atmosphere.gwt.client.AtmosphereClient;
import org.atmosphere.gwt.client.AtmosphereGWTSerializer;
import org.atmosphere.gwt.client.AtmosphereListener;

import java.io.Serializable;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * @author p.havelaar
 */
public class GWTDemo implements EntryPoint {

    static final Logger logger = Logger.getLogger(GWTDemo.class.getName());

    int count = 0;
    
    PollAsync polling = GWT.create(Poll.class);
    AtmosphereClient client;
    Button startButton;
    Button stopButton;
    
    @Override
    public void onModuleLoad() {

        Button button = new Button("Broadcast");
        button.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                client.broadcast(new Event(count++, "Send from client using broadcast"));
            }
        });

        Button post = new Button("Post");
        post.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                client.post(new Event(count++, "Send from client using post"));
            }
        });

        Button pollButton = new Button("Poll");
        pollButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                polling.pollDelayed(3000, new AsyncCallback<Event>() {

                    @Override
                    public void onFailure(Throwable caught) {
                        logger.log(Level.SEVERE, "Failed to poll", caught);
                    }

                    @Override
                    public void onSuccess(Event result) {
                        Info.display("Polling message received: " + result.getCode(), result.getData());
                    }
                });
            }
        });

        RootPanel.get("buttons").add(button);
        RootPanel.get("buttons").add(post);
        RootPanel.get("buttons").add(pollButton);
    
        initialize();
        startButton = new Button("Start");
        stopButton = new Button("Stop");
        startButton.setEnabled(false);
        startButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                toggleStartStop(true);
                client.start();
            }
        });
        stopButton.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                toggleStartStop(false);
                client.stop();
            }
        });
        RootPanel.get("buttons").add(startButton);
        RootPanel.get("buttons").add(stopButton);

    }
    
    void toggleStartStop(boolean clientConnected) {
        startButton.setEnabled(!clientConnected);
        stopButton.setEnabled(clientConnected);
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
        }

        @Override
        public void onHeartbeat() {
            logger.info("comet.heartbeat [" + client.getConnectionID() + "]");
        }

        @Override
        public void onRefresh() {
            logger.info("comet.refresh [" + client.getConnectionID() + "]");
        }

        @Override
        public void onAfterRefresh() {
            logger.info("comet.afterRefresh [" + client.getConnectionID() + "]");
        }

        @Override
        public void onMessage(List<?> messages) {
            StringBuilder result = new StringBuilder();
            for (Object obj : messages) {
                result.append(obj.toString()).append("<br/>");
            }
            logger.log(Level.INFO, "comet.message [" + client.getConnectionID() + "] " + result.toString());
            Info.display("[" + client.getConnectionID() + "] Received " + messages.size() + " messages", result.toString());
        }
    }

    ;

    public void initialize() {

        MyCometListener cometListener = new MyCometListener();

        AtmosphereGWTSerializer serializer = GWT.create(EventSerializer.class);
        // set a small length parameter to force refreshes
        // normally you should remove the length parameter
        client = new AtmosphereClient(GWT.getModuleBaseURL() + "gwtComet", serializer, cometListener);
        client.start();
    }

}
