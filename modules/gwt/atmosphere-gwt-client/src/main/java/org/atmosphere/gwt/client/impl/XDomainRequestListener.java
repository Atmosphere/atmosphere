/*
 * To change this template, choose Tools | Templates
 * and open the template in the editor.
 */

package org.atmosphere.gwt.client.impl;

/**
 *
 * @author p.havelaar
 */
public interface XDomainRequestListener {

    /**
     * Raised when there is an error that prevents the completion of the cross-domain request.
     */
    public void onError(XDomainRequest request);
    /**
     * Raised when the object has been completely received from the server.
     * @param responseText 
     */
    public void onLoad(XDomainRequest request, String responseText);
    /**
     * Raised when the browser starts receiving data from the server.
     * @param responseText partial data received
     */
    public void onProgress(XDomainRequest request, String responseText);
    /**
     * Raised when there is an error that prevents the completion of the request.
     */
    public void onTimeout(XDomainRequest request);
}
