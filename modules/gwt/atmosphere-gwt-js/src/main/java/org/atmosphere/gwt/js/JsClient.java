/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.js;

import com.google.gwt.core.client.EntryPoint;
import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.Scheduler;
import com.google.gwt.dom.client.Document;
import com.google.gwt.dom.client.ScriptElement;

/**
 *
 * @author p.havelaar
 */
public class JsClient implements EntryPoint {

    @Override
    public void onModuleLoad() {
        GWT.create(Client.class);
        GWT.create(OnConnected.class);
        GWT.create(OnBeforeDisconnected.class);
        GWT.create(OnDisconnected.class);
        GWT.create(OnError.class);
        GWT.create(OnMessage.class);
        GWT.create(OnHeartbeat.class);

        include(GWT.getModuleBaseURL()+"JSON.js");
        
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
