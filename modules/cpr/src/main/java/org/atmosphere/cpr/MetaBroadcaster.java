/*
 * Copyright 2014 Jeanfrancois Arcand
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
package org.atmosphere.cpr;

import org.atmosphere.inject.AtmosphereConfigAware;

import java.util.List;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Broadcast events to all or a subset of available {@link Broadcaster}s based on their {@link org.atmosphere.cpr.Broadcaster#getID()} value.
 * This class allows broadcasting events to a set of broadcasters that maps to some String like:
 * <blockquote><pre>
 *        // Broadcast the event to all Broadcaster ID starting with /hello
 *        broadcast("/hello", event)
 *        // Broadcast the event to all Broadcaster ID
 *        broaccast("/*", event);
 * </pre></blockquote>
 * The rule used is similar to path/URI mapping used by technology like Servlet, Jersey, etc.
 * <p/>
 * NOTE: Broadcasters' name must start with / in order to get retrieved by this class.
 * <p/>
 * This class is NOT thread safe.
 * <p/>
 * If you want to use MetaBroadcaster with Jersey or any framework, make sure all {@link org.atmosphere.cpr.Broadcaster#getID()}
 * starts with '/'. For example, with Jersey:
 * <blockquote><pre>
 *
 * @author Jeanfrancois Arcand
 * @Path(RestConstants.STREAMING + "/workspace{wid:/[0-9A-Z]+}")
 * public class JerseyPubSub {
 * @PathParam("wid") private Broadcaster topic;
 * </pre></blockquote>
 */
public interface MetaBroadcaster extends AtmosphereConfigAware {

    Future<List<Broadcaster>> broadcastTo(String broadcasterID, Object message);

    Future<List<Broadcaster>> scheduleTo(String broadcasterID, Object message, int time, TimeUnit unit);

    Future<List<Broadcaster>> delayTo(String broadcasterID, Object message, int time, TimeUnit unit);

    MetaBroadcaster addBroadcasterListener(BroadcasterListener b);

    MetaBroadcaster removeBroadcasterListener(BroadcasterListener b);

    MetaBroadcaster cache(DefaultMetaBroadcaster.MetaBroadcasterCache cache);

    void destroy();
}
