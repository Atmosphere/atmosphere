package org.atmosphere.samples.pubsub.websocket

import java.io.Serializable
import org.atmosphere.websocket.{WebSocket}
import org.atmosphere.cpr.{AtmosphereRequest}
import org.atmosphere.websocket.protocol.SimpleHttpProtocol

class DevoxxWebSocketProtocol extends SimpleHttpProtocol with Serializable {

  override def onOpen(webSocket: WebSocket): Unit = {
    super.onOpen(webSocket)
    webSocket.resource().suspend(-1)
  }

  override def onMessage(webSocket: WebSocket, message: String): AtmosphereRequest = {
    if (message.startsWith("message=devoxx:")) {
      webSocket.write(message.substring("message=".length()))
      null
    } else super.onMessage(webSocket, message)
  }
}