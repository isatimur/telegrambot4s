package info.mukel.telegrambot4s.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import akka.stream.scaladsl.{Sink, Source}
import info.mukel.telegrambot4s.api.RequestHandler
import info.mukel.telegrambot4s.marshalling.AkkaHttpMarshalling
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiResponse}
import io.circe.Decoder
import slogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future}

class YetAnotherAkkaClient(token: String, telegramHost: String = "api.telegram.org")
                          (implicit system: ActorSystem, materializer: Materializer, ec: ExecutionContext)
  extends RequestHandler with StrictLogging {

  private val flow = Http().outgoingConnectionHttps(telegramHost)

  import AkkaHttpMarshalling._

  /** Spawns a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  def apply[R](request: ApiRequest[R])(implicit decR: Decoder[R]): Future[R] = {
    Source.fromFuture(toHttpRequest(request))
      .via(flow)
      .mapAsync(1)(toApiResponse[R])
      .runWith(Sink.head)
      .map(processApiResponse[R])
  }

  private def toHttpRequest[R](r: ApiRequest[R]): Future[HttpRequest] = {
    Marshal(r).to[RequestEntity]
      .map {
        re =>
          HttpRequest(HttpMethods.POST,
            Uri(path = Path(s"/bot$token/" + r.methodName)),
            entity = re)
      }
  }

  private def toApiResponse[R](httpResponse: HttpResponse)(implicit decR: Decoder[R]): Future[ApiResponse[R]] = {
    Unmarshal(httpResponse.entity).to[ApiResponse[R]]
  }
}