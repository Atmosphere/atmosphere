/*
* Copyright 2011 Jeanfrancois Arcand
*
* Licensed under the Apache License, Version 2.0 (the "License"); you may not
* use this file except in compliance with the License. You may obtain a copy of
* the License at
*
* http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
* WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
* License for the specific language governing permissions and limitations under
* the License.
*/
package org.atmosphere.gwt.client.impl;

/**
 * @author p.havelaar
 */
public interface XDomainRequestListener {

    /**
     * Raised when there is an error that prevents the completion of the cross-domain request.
     */
    public void onError(XDomainRequest request);

    /**
     * Raised when the object has been completely received from the server.
     *
     * @param responseText
     */
    public void onLoad(XDomainRequest request, String responseText);

    /**
     * Raised when the browser starts receiving data from the server.
     *
     * @param responseText partial data received
     */
    public void onProgress(XDomainRequest request, String responseText);

    /**
     * Raised when there is an error that prevents the completion of the request.
     */
    public void onTimeout(XDomainRequest request);
}
