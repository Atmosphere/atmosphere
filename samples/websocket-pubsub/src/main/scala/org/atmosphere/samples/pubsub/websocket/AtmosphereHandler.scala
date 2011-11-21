package org.atmosphere.samples.pubsub.websocket

import javax.servlet.http.{HttpServletResponse, HttpServletRequest}
import org.atmosphere.handler.AbstractReflectorAtmosphereHandler
import org.atmosphere.cpr._

class AtmosphereHandler extends AbstractReflectorAtmosphereHandler {
  def onRequest(r: AtmosphereResource[HttpServletRequest, HttpServletResponse]): Unit = {
    var req: HttpServletRequest = r.getRequest
    var res: HttpServletResponse = r.getResponse
    var method: String = req.getMethod

    if ("GET".equalsIgnoreCase(method)) {
      r.addEventListener(new Console)
      res.setContentType("text/html;charset=ISO-8859-1")
      var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
      r.setBroadcaster(b)

      if (req.getHeader(HeaderConfig.X_ATMOSPHERE_TRANSPORT).equalsIgnoreCase(HeaderConfig.LONG_POLLING_TRANSPORT)) {
        req.setAttribute(ApplicationConfig.RESUME_ON_BROADCAST, true)
        r.suspend(-1, false)
      }
      else {
        r.suspend(-1)
      }
    }
    else if ("POST".equalsIgnoreCase(method)) {
      var b: Broadcaster = lookupBroadcaster(req.getPathInfo)
      var message: String = req.getReader.readLine

      if (message != null && message.indexOf("message") != -1) {
        b.broadcast(message.substring("message=".length))
      }
    }
  }

  def destroy: Unit = {
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
