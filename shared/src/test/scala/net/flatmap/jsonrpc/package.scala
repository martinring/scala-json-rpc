package net.flatmap

import com.typesafe.config.ConfigFactory

/**
  * Created by martin on 09.11.16.
  */
package object jsonrpc {
  def testConfig = ConfigFactory.parseString(
    """
      |akka {
      |  loglevel = "INFO"
      |  log-dead-letters-during-shutdown = off
      |}
    """.stripMargin)
}
