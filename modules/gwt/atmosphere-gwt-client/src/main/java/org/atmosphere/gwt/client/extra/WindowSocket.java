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
package org.atmosphere.gwt.client.extra;

import com.google.gwt.core.client.JavaScriptObject;
import com.google.gwt.event.shared.EventBus;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.GwtEvent.Type;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.SimpleEventBus;
import com.google.gwt.user.client.Timer;
/**
 *
 * @author p.havelaar
 */
public class WindowSocket {

    public static class MessageEvent extends GwtEvent<MessageHandler> {

        private static Type TYPE;
        private String message;

        public static Type getType() {
            return TYPE != null ? TYPE : (TYPE = new Type());
        }

        public MessageEvent(String message) {
            this.message = message;
        }
        @Override
        public Type<MessageHandler> getAssociatedType() {
            return getType();
        }

        @Override
        protected void dispatch(MessageHandler handler) {
            handler.onMessage(message);
        }

    }

    public static interface MessageHandler extends EventHandler {
        public void onMessage(String message);
    }

    public static boolean exists(Window w, String socketName) {
        return getSocket(w, socketName) != null;
    }

    public static void post(Window w, String socketName, String message) {
        SocketImpl s = getSocket(w, socketName);
        if (s != null) {
            s.post(message);
        }
    }

    public void bind(String name) {
        unbind();
        Window w = Window.current();
        socket = getSocket(w, name);
        if (socket == null) {
            Sockets sockets = w.getObject("sockets");
            if (sockets == null) {
                sockets = Sockets.create();
                w.set("sockets", sockets);
            }
            socket = SocketImpl.create(name);
            sockets.set(socket, name);
        }
        queueTimer.scheduleRepeating(250);
    }

    public void unbind() {
        if (socket != null) {
            Sockets sockets = Window.current().getObject("sockets");
            sockets.remove(socket);
            queueTimer.cancel();
            // run once to empty queue
            queueTimer.run();
            socket = null;
        }
    }

    public HandlerRegistration addHandler(MessageHandler handler) {
        return listeners.addHandler(MessageEvent.getType(), handler);
    }

    private static SocketImpl getSocket(Window w, String name) {
        Sockets sockets = w.getObject("sockets");
        if (sockets == null) {
            return null;
        }
        return sockets.get(name);
    }

    private SocketImpl socket;
    private EventBus listeners = new SimpleEventBus();
    private Timer queueTimer = new Timer() {
        @Override
        public void run() {
            if (socket != null) {
                String m;
                while ((m = socket.poll()) != null) {
                    listeners.fireEvent(new MessageEvent(m));
                }
            }
        }
    };

    private final static class SocketImpl extends JavaScriptObject {
        private static native SocketImpl create(String xname) /*-{
            return {
               messages: null,
               last: null,
               name: xname
            };
        }-*/;

        public native void post(String message) /*-{
            var head = this.messages;
            var msg = { };
            msg.next = null;
            msg.value = message;
            this.last = msg;
            if (head != null) {
                head.next = msg;
            } else {
                this.messages = msg;
            }
        }-*/;

        public native String poll() /*-{
            if (this.messages == null) {
                return null;
            }
            var msg = this.messages;
            this.messages = msg.next;
            if (this.messages == null) {
                this.last = null;
            }
            return msg.value;
        }-*/;

        protected SocketImpl() {
        }
    }

    private final static class Sockets extends JavaScriptObject {
        public static native Sockets create() /*-{
            return {
                sockets: {}
            };
        }-*/;

        public native SocketImpl set(SocketImpl socket, String name) /*-{
            var t = this.sockets[name];
            this.sockets[name] = socket;
            return t;
        }-*/;

        public native SocketImpl get(String name) /*-{
            return this.sockets[name];
        }-*/;

        public native void remove(SocketImpl socket) /*-{
            this.sockets[socket.name] = null;
        }-*/;

        protected Sockets() {

        }
    }

}
