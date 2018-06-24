package info.mukel.telegrambot4s.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import info.mukel.telegrambot4s.api.RequestHandler
import info.mukel.telegrambot4s.marshalling.AkkaHttpMarshalling
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiResponse}
import io.circe.Decoder
import slogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}

/** Akka-backed Telegram Bot API client
  * Provide transparent camelCase <-> underscore_case conversions during serialization/deserialization
  *
  * @param token Bot token
  */
class AkkaHttpClient(token: String, telegramHost: String = "api.telegram.org")(implicit system: ActorSystem, materializer: Materializer, ec: ExecutionContext) extends RequestHandler with StrictLogging {

  import AkkaHttpMarshalling._

  private val apiBaseUrl = s"https://$telegramHost/bot$token/"

  private val http = Http()

  private def toHttpRequest[R](r: ApiRequest[R]): Future[HttpRequest] = {
    Marshal(r).to[RequestEntity]
      .map {
        re =>
          HttpRequest(HttpMethods.POST, Uri(apiBaseUrl + r.methodName), entity = re)
      }
  }

  private def toApiResponse[R : Decoder](httpResponse: HttpResponse): Future[ApiResponse[R]] = {
    Unmarshal(httpResponse.entity)
      .to[ApiResponse[R]]
  }

  /** Spawns a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  def apply[R : Decoder](request: ApiRequest[R]): Future[R] = {
    toHttpRequest(request)
      .flatMap(http.singleRequest(_))
      .flatMap(toApiResponse[R])
      .map(processApiResponse[R])
  }
}
