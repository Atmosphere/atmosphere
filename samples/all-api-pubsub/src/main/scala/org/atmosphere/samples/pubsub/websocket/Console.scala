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

import javax.ws.rs._
import org.slf4j.{LoggerFactory, Logger}
import org.atmosphere.websocket.WebSocketEventListener
import org.atmosphere.cpr.AtmosphereResourceEvent

@Path("/pubsub/{topic}")
@Produces(Array("text/html;charset=ISO-8859-1"))
class Console extends WebSocketEventListener {

  private final val logger: Logger = LoggerFactory.getLogger(classOf[Console])

  def onPreSuspend(event: AtmosphereResourceEvent): Unit = {
    logger.info("onSuspend(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onSuspend(event: AtmosphereResourceEvent): Unit = {
    logger.info("onSuspend(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onResume(event: AtmosphereResourceEvent): Unit = {
    logger.info("onResume(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onClose(event: AtmosphereResourceEvent): Unit = {
    logger.info("onClose(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onDisconnect(event: AtmosphereResourceEvent): Unit = {
    logger.info("onDisconnect(): {}:{}", event.getResource.getRequest.getRemoteAddr, event.getResource.getRequest.getRemotePort)
  }

  def onBroadcast(event: AtmosphereResourceEvent): Unit = {
    logger.info("onBroadcast(): {}", event.getMessage)
  }

  def onThrowable(event: AtmosphereResourceEvent): Unit = {
    logger.warn("onThrowable(): {}", event)
  }

  def onHandshake(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onHandshake(): {}", event)
  }

  def onMessage(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onMessage(): {}", event)
  }

  def onClose(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onClose(): {}", event)
  }

  def onControl(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onControl(): {}", event)
  }

  def onDisconnect(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onDisconnect(): {}", event)
  }

  def onConnect(event: WebSocketEventListener.WebSocketEvent[_]): Unit = {
    logger.info("onConnect(): {}", event)
  }
}