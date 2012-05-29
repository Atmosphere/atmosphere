/*
* Copyright 2012 Jeanfrancois Arcand
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
package org.atmosphere.gwt.shared;

public enum SerialMode {
    RPC,
    /**
     * At the moment DE_RPC does not work for client.post and client.broadcast
     *
     * @see <a href='http://code.google.com/intl/nl-NL/webtoolkit/doc/latest/DevGuideServerCommunication.html#DevGuideDeRPC'>GWT devguide</a>
     */
    DE_RPC,
    JSON,
    PLAIN, // plain text
}
