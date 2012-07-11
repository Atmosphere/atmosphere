package org.atmosphere.gwt.js;

import com.google.gwt.core.client.GWT;
import com.google.gwt.core.client.JavaScriptObject;
import org.atmosphere.gwt.client.extra.AtmosphereProxy;
import org.timepedia.exporter.client.Export;
import org.timepedia.exporter.client.ExportPackage;

/**
 *
 * @author p.havelaar
 */
@ExportPackage("atmosphere")
public class Proxy extends JsUserInterface {
    private JsSerializer serializer = GWT.create(JsSerializer.class);
    
    @Export
    public Proxy(String url) {
        impl = new AtmosphereProxy(url, serializer, listener);
    }
    
    @Export
    public void localBroadcast(JavaScriptObject message) {
        ((AtmosphereProxy)impl).localBroadcast(message);
    }

}
