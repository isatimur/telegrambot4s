package info.mukel.telegrambot4s.api

import info.mukel.telegrambot4s.api.declarative.{CommandImplicits, Commands}
import info.mukel.telegrambot4s.marshalling.CirceMarshaller
import info.mukel.telegrambot4s.methods.{ApiRequest, GetMe}
import info.mukel.telegrambot4s.models.{Message, User}
import io.circe.Decoder
import org.scalamock.scalatest.MockFactory
import org.scalatest.FlatSpec

import scala.concurrent.Future

class CommandsSuite extends FlatSpec with MockFactory with TestUtils with CommandImplicits {

  import CirceMarshaller._

  trait Fixture {
    val handler = mockFunction[Message, Unit]
    val handlerHello = mockFunction[Message, Unit]
    val handlerHelloWorld = mockFunction[Message, Unit]
    val handlerRespect = mockFunction[Message, Unit]

    val bot = new TestBot with GlobalExecutionContext with Commands {
      // Bot name = "TestBot".
      override lazy val client = new RequestHandler {
        override def apply[R](request: ApiRequest[R])(implicit decR: Decoder[R]): Future[R] = request match {
          case GetMe => Future.successful({
            val jsonUser = toJson[User](User(123, false, "FirstName", username = Some("TestBot")))
            fromJson[User](jsonUser)(userDecoder)
          })
        }
      }

      onCommand("/hello")(handlerHello)
      onCommand("/helloWorld")(handlerHelloWorld)
      onCommand("/respect" & respectRecipient)(handlerRespect)
    }

    bot.run()
  }

  behavior of "Commands"

  it should "ignore non-declared commands" in new Fixture {
    handlerHello.expects(*).never()
    handlerHelloWorld.expects(*).never()
    bot.receiveMessage(textMessage("/cocou"))
  }

  it should "match string command" in new Fixture {
    handler.expects(*).once()
    bot.onCommand("/cmd")(handler)
    bot.receiveMessage(textMessage("/cmd"))
  }

  it should "match String command sequence" in new Fixture {
    handler.expects(*).twice()
    bot.onCommand("/a" | "/b")(handler)
    bot.receiveMessage(textMessage("/a"))
    bot.receiveMessage(textMessage("/b"))
    bot.receiveMessage(textMessage("/c"))
  }

  it should "match Symbol command" in new Fixture {
    handler.expects(*).once()
    bot.onCommand('cmd)(handler)
    bot.receiveMessage(textMessage("/cmd"))
  }

  it should "match Symbol command sequence" in new Fixture {
    handler.expects(*).twice()
    bot.onCommand('a | 'b)(handler)
    bot.receiveMessage(textMessage("/a"))
    bot.receiveMessage(textMessage("/b"))
    bot.receiveMessage(textMessage("/c"))
  }

  it should "support @sender suffix" in new Fixture {
    val m = textMessage("  /hello@TestBot  ")
    handlerHello.expects(m).once()
    bot.receiveMessage(m)
  }

  it should "ignore case in @sender" in new Fixture {
    val args = Seq("arg1", "arg2")
    val m = textMessage("  /respect@testbot  " + args.mkString(" "))
    handlerRespect.expects(m).once()
    bot.receiveMessage(m)
  }

  it should "accept any recipient if respectRecipient is not used" in new Fixture {
    val args = Seq("arg1", "arg2")
    val m = textMessage("  /hello@otherbot  " + args.mkString(" "))
    handlerHello.expects(m).once()
    bot.receiveMessage(m)
  }

  it should "ignore empty @sender" in new Fixture {
    val m = textMessage("  /hello@ ")
    handlerHello.expects(m).once()
    bot.receiveMessage(m)
  }

  it should "ignore different @sender" in new Fixture {
    val m = textMessage("  /respect@OtherBot  ")
    handlerHello.expects(m).never()
    handlerHelloWorld.expects(*).never()
    handlerRespect.expects(*).never()
    bot.receiveMessage(m)
  }

  it should "support commands without '/' suffix" in new Fixture {
    val commandHandler = mockFunction[Message, Unit]
    commandHandler.expects(*).twice()
    bot.onCommand("command" | "/another")(commandHandler)
    bot.receiveMessage(textMessage("command"))
    bot.receiveMessage(textMessage("another"))
    bot.receiveMessage(textMessage("/command"))
    bot.receiveMessage(textMessage("/another"))
    bot.receiveMessage(textMessage("/pepe"))
  }

  "using helper" should "execute actions on match" in new Fixture {
    val textHandler = mockFunction[String, Unit]
    textHandler.expects("123").once()
    bot.using(_.text)(textHandler)(textMessage("123"))
  }

  it should "ignore unmatched using statements" in new Fixture {
    bot.using(_.from)(user => fail())(textMessage("123"))
  }

  "withArgs" should "pass arguments" in new Fixture {
    val argsHandler = mockFunction[Seq[String], Unit]
    argsHandler.expects(Seq("arg1", "arg2")).once()
    bot.withArgs(argsHandler)(textMessage("  /cmd   arg1  arg2  "))
  }
}
