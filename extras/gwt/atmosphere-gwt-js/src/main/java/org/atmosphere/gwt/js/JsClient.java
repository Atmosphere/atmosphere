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

package org.atmosphere.gwt.js;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.ScriptElement;

/**
 * @author p.havelaar
 */
public class JsClient implements EntryPoint {

    @Override
    public void onModuleLoad() {
        GWT.create(Client.class);
        GWT.create(Proxy.class);
        GWT.create(OnConnected.class);
        GWT.create(OnBeforeDisconnected.class);
        GWT.create(OnDisconnected.class);
        GWT.create(OnError.class);
        GWT.create(OnMessage.class);
        GWT.create(OnHeartbeat.class);
        GWT.create(OnRefresh.class);
        GWT.create(OnAfterRefresh.class);

        include(GWT.getModuleBaseURL() + "JSON.js");

        Scheduler.get().scheduleDeferred(new Scheduler.ScheduledCommand() {
            @Override
            public void execute() {
                onLoadImpl();
            }
        });
    }

    protected void include(String source) {
        ScriptElement el = Document.get().createScriptElement();
        el.setType("text/javascript");
        el.setSrc(source);
        Document.get().getBody().appendChild(el);
    }

    private native void onLoadImpl() /*-{
        if ($wnd.atmosphereOnLoad && typeof $wnd.atmosphereOnLoad == 'function') $wnd.atmosphereOnLoad();
    }-*/;
}
