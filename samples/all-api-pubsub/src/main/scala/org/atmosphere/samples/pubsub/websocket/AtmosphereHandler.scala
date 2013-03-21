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
package org.atmosphere.samples.pubsub.websocket

import org.atmosphere.handler.AbstractReflectorAtmosphereHandler
import org.atmosphere.cpr._

class AtmosphereHandler extends AbstractReflectorAtmosphereHandler {
  def onRequest(r: AtmosphereResource): Unit = {
    var req: AtmosphereRequest = r.getRequest
    var res: AtmosphereResponse = r.getResponse
    var method: String = req.getMethod

    if ("GET".equalsIgnoreCase(method)) {
      r.addEventListener(new Console)
      res.setContentType("text/html;charset=ISO-8859-1")
      var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
      r.setBroadcaster(b).suspend(-1)
    }
    else if ("POST".equalsIgnoreCase(method)) {
      var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
      var message: String = req.getReader.readLine

      if (message != null && message.indexOf("message") != -1) {
        b.broadcast(message.substring("message=".length))
      }
    }
  }

  /**
   * Retrieve the {@link Broadcaster} based on the request's path info.
   *
   * @param pathInfo
   * @return the {@link Broadcaster} based on the request's path info.
   */
  private[pubsub] def lookupBroadcaster(pathInfo: String): Broadcaster = {
    var decodedPath: Array[String] = pathInfo.split("/")
    var b: Broadcaster = BroadcasterFactory.getDefault.lookup(decodedPath(decodedPath.length - 1), true)
    return b
  }
}
