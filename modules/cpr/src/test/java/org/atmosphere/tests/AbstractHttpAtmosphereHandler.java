package org.atmosphere.tests;

import org.atmosphere.cpr.AtmosphereHandler;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public abstract class AbstractHttpAtmosphereHandler implements AtmosphereHandler<HttpServletRequest, HttpServletResponse> {

    @Override
    public void destroy() {
    }
}
