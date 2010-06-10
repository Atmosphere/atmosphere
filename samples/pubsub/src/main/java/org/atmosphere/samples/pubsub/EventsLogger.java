package org.atmosphere.samples.pubsub;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EventsLogger implements AtmosphereResourceEventListener {

    public EventsLogger() {
    }

    @Override
    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event){
        System.out.println("onResume: " + event);
    }
    @Override
    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onResume: " + event);
    }
    @Override
    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onDisconnect: " + event);
    }
    @Override
    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onBroadcast: " + event);
    }
}