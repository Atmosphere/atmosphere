package org.atmosphere.samples.pubsub;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EventsLogger implements AtmosphereResourceEventListener {

    public EventsLogger() {
    }


    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onResume: " + event);
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onResume: " + event);
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onDisconnect: " + event);
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        System.out.println("onBroadcast: " + event);
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        event.throwable().printStackTrace(System.err);
    }
}