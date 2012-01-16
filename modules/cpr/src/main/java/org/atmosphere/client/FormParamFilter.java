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
package org.atmosphere.client;

import org.atmosphere.cpr.BroadcastFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple Form param filter that remove the first part of the request body.
 *
 * @author Jeanfrancois Arcand
 */
public class FormParamFilter implements BroadcastFilter {

    private static final Logger logger = LoggerFactory.getLogger(FormParamFilter.class);

    @Override
    public BroadcastAction filter(Object originalMessage, Object message) {

        if ((message instanceof String) && ((String) message).contains("=")) {
            try {
                message = message.toString().split("=")[1];
            } catch (ArrayIndexOutOfBoundsException ex) {
                // Don't fail, just log it.
                logger.warn("failed to split form param: " + message, ex);
            }
        }
        return new BroadcastAction(BroadcastAction.ACTION.CONTINUE, message);
    }

}
