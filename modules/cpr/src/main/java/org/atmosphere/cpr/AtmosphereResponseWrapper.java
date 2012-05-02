package org.atmosphere.cpr;

import javax.servlet.http.HttpServletResponseWrapper;

public class AtmosphereResponseWrapper extends HttpServletResponseWrapper {
    /**
     * Constructs a response adaptor wrapping the given response.
     *
     * @param response response to wrap
     * @throws IllegalArgumentException if the response is null
     */
    public AtmosphereResponseWrapper(AtmosphereResponse response) {
        super(response);
    }
}
