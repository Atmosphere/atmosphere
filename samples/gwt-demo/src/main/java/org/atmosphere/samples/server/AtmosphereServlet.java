
package org.atmosphere.samples.server;

import java.io.IOException;
import javax.servlet.ServletException;
import java.util.logging.Level;
import java.util.logging.Logger;
import javax.servlet.http.HttpSession;
import org.atmosphere.cpr.Broadcaster;
import org.atmosphere.cpr.BroadcasterFactory;
import org.atmosphere.gwt.server.GwtAtmosphereResource;
import org.atmosphere.gwt.server.GwtAtmosphereServlet;

/**
 *
 * @author p.havelaar
 */
public class AtmosphereServlet extends GwtAtmosphereServlet {

    @Override
    public void init() throws ServletException {
        Logger.getLogger("").setLevel(Level.INFO);
        Logger.getLogger("gwtcomettest").setLevel(Level.ALL);
        Logger.getLogger("").getHandlers()[0].setLevel(Level.ALL);
        logger.trace("Updated logging levels");
        super.init();
    }

    @Override
    protected Broadcaster getBroadcaster() {
        return BroadcasterFactory.getDefault().lookup(Broadcaster.class, "GWT_COMET");
    }    

    @Override
    public int doComet(GwtAtmosphereResource resource) throws ServletException, IOException {
        resource.getBroadcaster().setID("GWT_COMET");
        HttpSession session = resource.getAtmosphereResource().getRequest().getSession(false);
        if (session != null) {
            logger.debug("Got session with id: " + session.getId());
            logger.debug("Time attribute: " + session.getAttribute("time"));
        } else {
            logger.warn("No session");
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Url: " + resource.getAtmosphereResource().getRequest().getRequestURL()
                    + "?" + resource.getAtmosphereResource().getRequest().getQueryString());
        }
        return super.doComet(resource);
    }

    @Override
    public void cometTerminated(GwtAtmosphereResource cometResponse, boolean serverInitiated) {
        super.cometTerminated(cometResponse, serverInitiated);
        logger.debug("Comet disconnected");
    }

}
