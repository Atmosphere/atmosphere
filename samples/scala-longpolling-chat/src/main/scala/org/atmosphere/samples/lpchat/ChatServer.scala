package org.atmosphere.samples.lpchat;

import org.atmosphere.grizzly.AtmosphereSpadeServer;

object ChatServer {
  def main(args: Array[String]) {
    try {
      AtmosphereSpadeServer.build(args(0), "org.atmosphere.samples.lpchat").start();
    } catch {
      case ex : Exception => ex.printStackTrace;
    }
  }
}
