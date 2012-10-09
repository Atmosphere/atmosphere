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

import javax.servlet.http.{HttpServletResponse, HttpServletRequest, HttpServlet}
import org.atmosphere.cpr._

class Meteor extends HttpServlet {

  override def doGet(req: HttpServletRequest, res: HttpServletResponse): Unit = {
    var m:  org.atmosphere.cpr.Meteor = org.atmosphere.cpr.Meteor.build(req)
    m.addListener(new Console)

    res.setContentType("text/html;charset=ISO-8859-1")
    var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
    m.setBroadcaster(b)

    if (req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
      req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, true)
      m.suspend(-1)
    }
  }

  override def doPost(req: HttpServletRequest, res: HttpServletResponse): Unit = {
    var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
    var message: String = req.getReader.readLine
    if (message != null && message.indexOf("message") != -1) {
      b.broadcast(message.substring("message=".length))
    }
  }

  private[pubsub] def lookupBroadcaster(pathInfo: String): Broadcaster = {
    var decodedPath: Array[String] = pathInfo.split("/")
    var b: Broadcaster = BroadcasterFactory.getDefault.lookup(decodedPath(decodedPath.length - 1), true)
    return b
  }

}