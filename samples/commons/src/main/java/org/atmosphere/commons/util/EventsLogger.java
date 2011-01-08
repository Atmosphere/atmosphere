package org.atmosphere.commons.util;

import org.atmosphere.cpr.AtmosphereResourceEvent;
import org.atmosphere.cpr.AtmosphereResourceEventListener;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public class EventsLogger implements AtmosphereResourceEventListener {

    private static final Logger logger = LoggerFactory.getLogger(EventsLogger.class);

    public EventsLogger() {
    }

    public void onSuspend(final AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event){
        logger.info("onSuspend(): {}", event);
    }

    public void onResume(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.info("onResume(): {}", event);
    }

    public void onDisconnect(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.info("onDisconnect(): {}", event);
    }

    public void onBroadcast(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.info("onBroadcast(): {}", event);
    }

    public void onThrowable(AtmosphereResourceEvent<HttpServletRequest, HttpServletResponse> event) {
        logger.warn("onThrowable(): {}", event);
    }
}
