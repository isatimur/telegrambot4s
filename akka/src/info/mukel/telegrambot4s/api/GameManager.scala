package info.mukel.telegrambot4s.api

import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Base64

import akka.http.scaladsl.model.StatusCodes
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.{Directive1, Route}
import info.mukel.telegrambot4s.api.Marshalling._
import info.mukel.telegrambot4s.marshalling.CirceMarshaller
import info.mukel.telegrambot4s.methods.{GetGameHighScores, SetGameScore}
import info.mukel.telegrambot4s.models.{CallbackQuery, ChatId, User}
import io.circe.generic.extras.semiauto._
import io.circe.generic.semiauto.deriveDecoder
import io.circe.{Decoder, Encoder}

import scala.util.{Failure, Success}

/**
  * Provides basic endpoints to manage game's scoring.
  *   setScore
  *   getScore
  *
  * Game data is shared with the game, base64-encoded in the URL.
  * This offer NO security at all, all data is passed au-clair.
  * Altering the scores is trivial.
  *
  * The "secure" implementations I've seen so far are based on security
  * though obscurity, all of them can be reverse-engineered to alter the scores.
  *
  * If you happen to have an idea of how to make it secure, please open an issue,
  * or even better, submit a PR with your approach.
  */
trait GameManager extends WebRoutes {
  _: BotBase with BotExecutionContext with AkkaImplicits =>

  import info.mukel.telegrambot4s.marshalling.AkkaHttpMarshalling._

  private def extractPayload: Directive1[Payload] = {
    headerValueByName('Referer).map { referer: String =>
      val parts = referer.split("\\?payload=")
      val encodedPayload = URLDecoder.decode(parts(1), "UTF-8")
      Payload.base64Decode(encodedPayload)
    }
  }

  val gameManagerRoute: Route = {
    (post &
      path("games" / "api" / "setScore") &
      parameter("score".as[Long]) &
      extractPayload) { (score, payload) =>

      onComplete(request(
        SetGameScore(payload.user.id,
          score,
          chatId = payload.chatId,
          messageId = payload.messageId,
          inlineMessageId = payload.inlineMessageId))) {

        case Success(value) => complete(StatusCodes.OK)
        case Failure(ex) =>
          complete((StatusCodes.InternalServerError,
            s"An error occurred: ${ex.getMessage}"))
      }
    } ~
      (get &
        path("games" / "api" / "getScores") &
        extractPayload) { payload =>
        onComplete(request(payload.toGetGameHighScores)) {
          case Success(scores) => complete(CirceMarshaller.toJson(scores))
          case Failure(ex) =>
            complete((StatusCodes.InternalServerError,
              s"An error occurred: ${ex.getMessage}"))
        }
      }
  }
}

/**
  * Data shared with the game.
  */
case class Payload(
                    user            : User,
                    chatId          : Option[ChatId] = None,
                    messageId       : Option[Int] = None,
                    inlineMessageId : Option[String] = None,
                    gameManagerHost : String,
                    gameShortName   : String) {

  def toGetGameHighScores = GetGameHighScores(user.id, chatId, messageId, inlineMessageId)

  def base64Encode: String = {
    val payloadJson = CirceMarshaller.toJson[Payload](this)
    val encodedPayload = Base64.getEncoder.encodeToString(
      payloadJson.getBytes(StandardCharsets.UTF_8))

    encodedPayload
  }
}

object Payload {

  def base64Decode(encodedPayload: String): Payload = {
    val base64payload = URLDecoder.decode(encodedPayload, "UTF-8")
    val jsonPayload = new String(Base64.getDecoder.decode(base64payload),
      StandardCharsets.UTF_8)
    val payload = CirceMarshaller.fromJson[Payload](jsonPayload)

    payload
  }

  def forCallbackQuery(gameManagerHost: String)(implicit cbq: CallbackQuery): Payload = {
    Payload(
      cbq.from,
      cbq.message.map(_.source),
      cbq.message.map(_.messageId),
      cbq.inlineMessageId,
      gameManagerHost,
      cbq.gameShortName.get) // throws if not a game callback
  }
}

object Marshalling extends  {
  import info.mukel.telegrambot4s.marshalling.CirceMarshaller._
  implicit val payloadEncoder: Encoder[Payload] = deriveEncoder[Payload]
  implicit val payloadDecoder: Decoder[Payload] = deriveDecoder[Payload]
}