/*
* Copyright 2017 Async-IO.org
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
package org.atmosphere.runtime;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Simple adapter listener to be used to track {@link BroadcasterLifeCyclePolicy} events.
 *
 * @author Jeanfrancois Arcand
 */
public class BroadcasterLifeCyclePolicyListenerAdapter implements BroadcasterLifeCyclePolicyListener {

    private final Logger logger = LoggerFactory.getLogger(BroadcasterLifeCyclePolicyListenerAdapter.class);

    @Override
    public void onEmpty() {
        logger.trace("onEmpty");
    }

    @Override
    public void onIdle() {
        logger.trace("onIdle");
    }

    @Override
    public void onDestroy() {
        logger.trace("onDestroy");
    }
}
