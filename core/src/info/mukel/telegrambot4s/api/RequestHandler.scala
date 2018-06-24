package info.mukel.telegrambot4s.api

import info.mukel.telegrambot4s.methods.{ApiRequest, ApiResponse}
import io.circe.Decoder
import slogging.StrictLogging

import scala.concurrent.Future

trait RequestHandler extends StrictLogging {

  /** Spawns a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  def apply[R : Decoder](request: ApiRequest[R]): Future[R]

  protected def processApiResponse[R](response: ApiResponse[R]) : R = response match {
    case ApiResponse(true, Some(result), _, _, _) =>
      result
    case ApiResponse(false, _, description, Some(errorCode), parameters) =>
      throw TelegramApiException(description.getOrElse("Unexpected/invalid/empty response"), errorCode, None, parameters)
    case other =>
      throw new RuntimeException(s"Unexpected API response: $other")
  }
}
