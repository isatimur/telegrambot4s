package info.mukel.telegrambot4s.clients

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.Uri.Path
import akka.http.scaladsl.model._
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.QueueOfferResult.Enqueued
import akka.stream.scaladsl.{Keep, Sink, Source}
import akka.stream.{Materializer, OverflowStrategy}
import info.mukel.telegrambot4s.api.{RequestHandler, TelegramApiException}
import info.mukel.telegrambot4s.marshalling.AkkaHttpMarshalling
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiResponse}
import io.circe.Decoder
import slogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.{Failure, Success, Try}

class SourceQueueClient(token: String, telegramHost: String = "api.telegram.org", queueSize: Int = 1024)
                      (implicit system: ActorSystem, materializer: Materializer, ec: ExecutionContext)
  extends RequestHandler with StrictLogging {

  import AkkaHttpMarshalling._

  private val availableProcessors = Runtime.getRuntime.availableProcessors()

  private lazy val pool = Http().cachedHostConnectionPoolHttps[Promise[HttpResponse]](host = telegramHost)

  private lazy val queue = Source.queue[(ApiRequest[_], Promise[HttpResponse])](queueSize, OverflowStrategy.dropNew)
    .mapAsync(availableProcessors){ case (r, p) => toHttpRequest(r) map { (_ -> p)} }
    .via(pool)
    .toMat(Sink.foreach[(Try[HttpResponse], Promise[HttpResponse])]({
      case ((Success(resp), p)) => p.success(resp)
      case ((Failure(e), p)) => p.failure(e)
    }))(Keep.left)
    .run()

  /** Spawns a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  def apply[R : Decoder](request: ApiRequest[R]): Future[R] = {
    val promise = Promise[HttpResponse]

    val response = queue.synchronized {
      queue.offer((request, promise)).flatMap {
        case Enqueued => promise.future.flatMap(r => toApiResponse[R](r))
        case _ => Future.failed(new RuntimeException("Failed to send request, pending queue is full."))
      }
    }

    response.map(processApiResponse[R])
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