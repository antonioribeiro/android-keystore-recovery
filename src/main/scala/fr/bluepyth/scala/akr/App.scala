/**
 * Android Keystore Recovery
 * Copyright (C) 2013 Romain Sertelon
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package fr.bluepyth.scala.akr

import akka.actor.ActorSystem
import akka.actor.PoisonPill
import akka.actor.Props
import akka.routing._
import akka.util.duration._

import java.io.FileInputStream

import fr.bluepyth.scala.akr.cli.AKRConfig
import fr.bluepyth.scala.akr.actor._
import fr.bluepyth.scala.akr.jks.JKSUtils
import fr.bluepyth.scala.akr.message._
import fr.bluepyth.scala.akr.generator.SimplePasswordGenerator

import scopt.immutable.OptionParser

/**
 * @author BluePyth
 */
object App {

  def main(args: Array[String]) {

    val parser = new OptionParser[AKRConfig]("akr", "1.0") {
      def options = Seq(
        opt("f", "from", "Starts brute force at given password") { (v: String, c: AKRConfig) => c.copy(from = Some(v)) },
        opt("t", "to", "brute force will stop at given password") { (v: String, c: AKRConfig) => c.copy(to = Some(v)) },
        intOpt("l", "min-length", "Starts brute force with a password of given length") { (v: Int, c: AKRConfig) => c.copy(passwordLengthStart = Some(v)) },
        arg("keystore", "The keystore that will be bruteforced") { (v: String, c: AKRConfig) => c.copy(keystore = Some(v)) })
    }

    parser.parse(args, AKRConfig()) map { config =>
      startBruteForce(config)
    } getOrElse {
      // arguments are bad, usage message will have been displayed
    }
  }

  def startBruteForce(c: AKRConfig) = {

    val inJKSUtils = new FileInputStream(c.keystore.get)
    implicit val jksUtils = new JKSUtils(inJKSUtils, new Array[Char](1))
    inJKSUtils.close

    val system = ActorSystem("bruteforce")

    val loggerActor = system.actorOf(Props[LoggerActor], "logger")
    
    val smallestMailboxRouter =
    system.actorOf(Props(new TryPasswordActor(c.keystore.get, loggerActor)).withRouter(SmallestMailboxRouter(Runtime.getRuntime.availableProcessors * 75)), "router")
    
    val passwordGenerator = new SimplePasswordGenerator(c.from, c.to, c.passwordLengthStart)

    loggerActor ! StartingBruteForce("Starting Brute force of keystore located at " + c.keystore.get)

    while (passwordGenerator.hasNext && !system.isTerminated) {
      smallestMailboxRouter ! Password(passwordGenerator.next)
    }

    smallestMailboxRouter ! Broadcast(PoisonPill)

    while (!smallestMailboxRouter.isTerminated) {}

    loggerActor ! PoisonPill
  }

}