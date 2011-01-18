package org.atmosphere.gwt.server;

import java.io.IOException;
import java.io.Serializable;
import java.util.List;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.atmosphere.cpr.AtmosphereResource;
import org.atmosphere.cpr.Broadcaster;

public interface GwtAtmosphereResource {
    public Broadcaster getBroadcaster();
    public HttpSession getSession();
    public HttpSession getSession(boolean create);
    public int getHeartBeatInterval();
    public void broadcast(Serializable message);
    public void broadcast(List<Serializable> messages);
    public AtmosphereResource<HttpServletRequest, HttpServletResponse> getAtmosphereResource();
    public HttpServletRequest getRequest();
    public boolean isAlive();
    public <T> void setAttribute(String name, T value);
    public <T> T getAttribute(String name);
    public int getConnectionID();
}
