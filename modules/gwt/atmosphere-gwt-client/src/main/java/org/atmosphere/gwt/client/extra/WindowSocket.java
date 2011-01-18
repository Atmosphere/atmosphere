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
