/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.samples.server;

import java.io.IOException;
import javax.servlet.ServletException;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.gwt.server.GwtAtmosphereResource;
import org.atmosphere.gwt.server.GwtAtmosphereServlet;

/**
 *
 * @author p.havelaar
 */
public class CometServlet extends GwtAtmosphereServlet {

    protected Broadcaster getBroadcaster() {
        return BroadcasterFactory.getDefault().lookup(Broadcaster.class, "GWT_JS_COMET");
    }

    public int doComet(GwtAtmosphereResource resource) throws ServletException, IOException {
        resource.getBroadcaster().setID("GWT_JS_COMET");
        return super.doComet(resource);
    }

}
