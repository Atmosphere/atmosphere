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

import com.google.gwt.event.logical.shared.CloseEvent;
import com.google.gwt.event.logical.shared.CloseHandler;
import com.google.gwt.event.shared.EventHandler;
import com.google.gwt.event.shared.GwtEvent;
import com.google.gwt.event.shared.HandlerRegistration;
import com.google.gwt.event.shared.SimpleEventBus;
import com.google.gwt.user.client.Window;
import com.google.gwt.user.client.Window.ClosingEvent;

/**
 * @author p.havelaar
 * @deprecated LoadRegister is deprecated for GWT 2.4.0 and above. Use the GWT 
 * provided Window class methods addCloseHandler and addWindowClosingHandler to
 * register unload event handlers.
 */
@Deprecated
public class LoadRegister {

    public static class BeforeUnloadEvent extends GwtEvent<BeforeUnloadHandler> {
        private static Type TYPE;

        public static Type<BeforeUnloadHandler> getType() {
            return TYPE != null ? TYPE : (TYPE = new Type());
        }

        @Override
        public Type<BeforeUnloadHandler> getAssociatedType() {
            return getType();
        }

        @Override
        protected void dispatch(BeforeUnloadHandler handler) {
            handler.onBeforeUnload(this);
        }

        protected BeforeUnloadEvent() {
        }

    }

    protected static final BeforeUnloadEvent beforeUnloadEvent = new BeforeUnloadEvent();

    public static class UnloadEvent extends GwtEvent<UnloadHandler> {
        private static Type TYPE;

        public static Type<UnloadHandler> getType() {
            return TYPE != null ? TYPE : (TYPE = new Type());
        }

        @Override
        public Type<UnloadHandler> getAssociatedType() {
            return getType();
        }

        @Override
        protected void dispatch(UnloadHandler handler) {
            handler.onUnload(this);
        }

        protected UnloadEvent() {
        }
    }

    protected static final UnloadEvent unloadEvent = new UnloadEvent();

    public interface BeforeUnloadHandler extends EventHandler {
        public void onBeforeUnload(BeforeUnloadEvent event);
    }

    public interface UnloadHandler extends EventHandler {
        public void onUnload(UnloadEvent event);
    }

    public static HandlerRegistration addBeforeUnloadHandler(final BeforeUnloadHandler handler) {
        maybeInit();
        log("register BeforeUnloadHandler");
        BeforeUnloadHandler wrapper = new BeforeUnloadHandler() {

            @Override
            public void onBeforeUnload(BeforeUnloadEvent event) {
                log("execute BeforeUnloadHandler");
                handler.onBeforeUnload(event);
            }
        };
        
        return eventBus.addHandler(BeforeUnloadEvent.getType(), wrapper);
    }

    public static HandlerRegistration addUnloadHandler(final UnloadHandler handler) {
        maybeInit();
        log("register UnloadHandler");
        UnloadHandler wrapper = new UnloadHandler() {
            @Override
            public void onUnload(UnloadEvent event) {
                log("execute UnloadHandler");
                handler.onUnload(event);
            }
        };
        return eventBus.addHandler(UnloadEvent.getType(), wrapper);
    }

    private static void maybeInit() {
        if (!initialized) {
            initWindowHandlers();
            initialized = true;
        }
    }

    static String onBeforeUnload() {
        log("LoadRegister onBeforeUnload");
        eventBus.fireEvent(beforeUnloadEvent);
        return null;
    }

    static void onUnload() {
        log("LoadRegister onUnload");
        eventBus.fireEvent(unloadEvent);
    }
    
    private static native void log(String s) /*-{
      if ($wnd.console) {
        $wnd.console.log(s);
      }
    }-*/;
    

    private static void initWindowHandlers() {
        Window.addWindowClosingHandler(new Window.ClosingHandler() {
            @Override
            public void onWindowClosing(ClosingEvent event) {
                onBeforeUnload();
            }
        });
        Window.addCloseHandler(new CloseHandler<Window>() {
            @Override
            public void onClose(CloseEvent<Window> event) {
                onUnload();
            }
        });
    }

    private static SimpleEventBus eventBus = new SimpleEventBus();
    private static boolean initialized = false;
}
