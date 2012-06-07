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
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.Element;
import com.google.gwt.event.dom.client.ChangeEvent;
import com.google.gwt.event.dom.client.ChangeHandler;
import com.google.gwt.event.dom.client.ClickEvent;
import com.google.gwt.event.dom.client.ClickHandler;
import com.google.gwt.event.dom.client.KeyCodes;
import com.google.gwt.event.dom.client.KeyDownEvent;
import com.google.gwt.event.dom.client.KeyDownHandler;
import com.google.gwt.i18n.client.DateTimeFormat;
import com.google.gwt.logging.client.HasWidgetsLogHandler;
import com.google.gwt.user.client.rpc.StatusCodeException;
import com.google.gwt.user.client.ui.Button;
import com.google.gwt.user.client.ui.HTML;
import com.google.gwt.user.client.ui.HTMLPanel;
import com.google.gwt.user.client.ui.Label;
import com.google.gwt.user.client.ui.ListBox;
import com.google.gwt.user.client.ui.RootPanel;
import com.google.gwt.user.client.ui.TextBox;
import com.google.gwt.user.client.ui.Widget;
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
    static final String LABEL_ENTER_ROOM = "Type your name to enter the room";
    static final String LABEL_TYPE_MESSAGE = "Type a message to send to the room";
    static final String MESSAGE_JOINED_ROOM = "&lt;joined the room&gt;";
    static final String MESSAGE_LEFT_ROOM = "&lt;left the room&gt;";
    static final String MESSAGE_ROOM_CONNECTED = "[connected to room]";
    static final String MESSAGE_ROOM_DISCONNECTED = "[disconnected from room]";
    static final String MESSAGE_ROOM_ERROR = "Error: ";
    static final String COLOR_SYSTEM_MESSAGE = "grey";
    static final String COLOR_MESSAGE_SELF = "green";
    static final String COLOR_MESSAGE_OTHERS = "red";

    int count = 0;
    
    AtmosphereClient client;
    MyCometListener cometListener = new MyCometListener();
    AtmosphereGWTSerializer serializer = GWT.create(EventSerializer.class);
    String author;
    
    Label label;
    TextBox input;
    Element chat;
    String room="room1";
    
    @Override
    public void onModuleLoad() {
        
        final ListBox roomSelect = new ListBox();
        roomSelect.addItem("Room 1", "room1");
        roomSelect.addItem("Room 2", "room2");
        roomSelect.addItem("Room 3", "room3");
        roomSelect.addItem("Room 4", "room4");
        roomSelect.setSelectedIndex(0);
        roomSelect.addChangeHandler(new ChangeHandler() {
            @Override
            public void onChange(ChangeEvent event) {
                String room = roomSelect.getValue(roomSelect.getSelectedIndex());
                changeRoom(room);
            }
        });
        RootPanel.get("room").add(roomSelect);
        
        chat = Document.get().getElementById("chat");
        
        label = new Label(LABEL_ENTER_ROOM);
        RootPanel.get("label").add(label);
        
        input = new TextBox();
        input.addKeyDownHandler(new KeyDownHandler() {
            @Override
            public void onKeyDown(KeyDownEvent event) {
                if (event.getNativeKeyCode() == KeyCodes.KEY_ENTER) {
                    sendMessage(input.getValue());
                    input.setText("");
                }
            }
        });
        RootPanel.get("input").add(input);

        Button send = new Button("Send");
        send.addClickHandler(new ClickHandler() {
            @Override
            public void onClick(ClickEvent event) {
                sendMessage(input.getValue());
                input.setText("");
            }
        });
        RootPanel.get("send").add(send);
        
        HTMLPanel logPanel = new HTMLPanel("") {
            @Override
            public void add(Widget widget) {
                super.add(widget);
                widget.getElement().scrollIntoView();
            }
        };
        RootPanel.get("logger").add(logPanel);
        Logger.getLogger("").addHandler(new HasWidgetsLogHandler(logPanel));
    
        changeRoom(room);
    }
    
    void sendMessage(String message) {
        if (author == null) {
            author = message;
            client.broadcast(new Event(author, MESSAGE_JOINED_ROOM));
            label.setText(LABEL_TYPE_MESSAGE);
        } else {
            client.broadcast(new Event(author, message));
        }
    }
    
    String getUrl() {
        return GWT.getModuleBaseURL() + "gwtComet/" + room;
    }
    
    void changeRoom(final String newRoom) {
        if (client != null) {
            if (author != null) {
                client.broadcast(new Event(author, MESSAGE_LEFT_ROOM));
            }
            client.stop();
            client = null;
        }
        author = null;
        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
            @Override
            public void execute() {
                room = newRoom;
                client = new AtmosphereClient(getUrl(), serializer, cometListener);
                clearChat();
                label.setText(LABEL_ENTER_ROOM);
                client.start();
            }
        });
    }
    
    void clearChat() {
        chat.setInnerHTML("");
    }
    
    void addChatLine(String line, String color) {
        HTML newLine = new HTML(line);
        newLine.getElement().getStyle().setColor(color);
        chat.appendChild(newLine.getElement());
        newLine.getElement().scrollIntoView();
    }
    
    private class MyCometListener implements AtmosphereListener {
        
        DateTimeFormat timeFormat = DateTimeFormat.getFormat(DateTimeFormat.PredefinedFormat.TIME_MEDIUM);

        @Override
        public void onConnected(int heartbeat, int connectionID) {
            logger.info("comet.connected [" + heartbeat + ", " + connectionID + "]");
            addChatLine(MESSAGE_ROOM_CONNECTED, COLOR_SYSTEM_MESSAGE);
        }

        @Override
        public void onBeforeDisconnected() {
            logger.log(Level.INFO, "comet.beforeDisconnected");
            if (author != null) {
                client.broadcast(new Event(author, MESSAGE_LEFT_ROOM));
            }
        }

        @Override
        public void onDisconnected() {
            logger.info("comet.disconnected");
            addChatLine(MESSAGE_ROOM_DISCONNECTED, COLOR_SYSTEM_MESSAGE);
        }

        @Override
        public void onError(Throwable exception, boolean connected) {
            int statuscode = -1;
            if (exception instanceof StatusCodeException) {
                statuscode = ((StatusCodeException) exception).getStatusCode();
            }
            logger.log(Level.SEVERE, "comet.error [connected=" + connected + "] (" + statuscode + ")", exception);
            addChatLine(MESSAGE_ROOM_ERROR + exception.getMessage(), COLOR_SYSTEM_MESSAGE);
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
            for (Object obj : messages) {
                if (obj instanceof Event) {
                    Event e = (Event)obj;
                    String line = timeFormat.format(e.getTime())
                            + " <b>" + e.getAuthor() + "</b> " + e.getMessage();
                    if (e.getAuthor().equals(author)) {
                        addChatLine(line, COLOR_MESSAGE_SELF);
                    } else {
                        addChatLine(line, COLOR_MESSAGE_OTHERS);
                    }
                }
            }
        }
    }

}
