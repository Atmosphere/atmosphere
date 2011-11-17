package org.atmosphere.samples.pubsub.websocket

import javax.ws.rs._
import org.slf4j.{LoggerFactory, Logger}
import org.atmosphere.websocket.WebSocketEventListener
import javax.servlet.http.{HttpServletRequest, HttpServletResponse}
import org.atmosphere.cpr.AtmosphereResourceEvent

@Path("/pubsub/{topic}")
@Produces(Array("text/html;charset=ISO-8859-1"))
class Console extends WebSocketEventListener {

  private final val logger: Logger = LoggerFactory.getLogger(classOf[Console])

  def onSuspend(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]): Unit = {
    logger.info("onSuspend(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onResume(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]): Unit = {
    logger.info("onResume(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onDisconnect(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]): Unit = {
    logger.info("onDisconnect(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onBroadcast(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]): Unit = {
    logger.info("onBroadcast(): {}", event.getMessage)
  }

  def onThrowable(event: AtmosphereResourceEvent[HttpServletRequest, HttpServletResponse]): Unit = {
    logger.warn("onThrowable(): {}", event)
  }

  def onHandshake(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onHandshake(): {}", event)
  }

  def onMessage(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onMessage(): {}", event)
  }

  def onClose(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onClose(): {}", event)
  }

  def onControl(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onControl(): {}", event)
  }

  def onDisconnect(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onDisconnect(): {}", event)
  }

  def onConnect(event: WebSocketEventListener.WebSocketEvent): Unit = {
    logger.info("onConnect(): {}", event)
  }
}