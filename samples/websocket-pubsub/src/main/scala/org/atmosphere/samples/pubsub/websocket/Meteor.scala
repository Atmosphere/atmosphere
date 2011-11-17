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
      m.suspend(-1, false)
    }
    else {
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