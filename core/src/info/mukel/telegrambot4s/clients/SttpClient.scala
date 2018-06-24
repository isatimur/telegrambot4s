package info.mukel.telegrambot4s.clients

import java.util.UUID

import com.softwaremill.sttp._
import info.mukel.telegrambot4s.api.RequestHandler
import info.mukel.telegrambot4s.marshalling.{CaseConversions, CirceMarshaller}
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiRequestJson, ApiRequestMultipart, ApiResponse}
import info.mukel.telegrambot4s.models.InputFile
import io.circe.parser.parse
import io.circe.{Decoder, Encoder}
import slogging.StrictLogging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/** Sttp HTTP client.
  * Supports browsers via sttp's FetchBackend.
  *
  * @param token Bot token
  */
class SttpClient(token: String, telegramHost: String = "api.telegram.org")
                (implicit backend: SttpBackend[Future, Nothing], executionContext: ExecutionContext)
  extends RequestHandler with StrictLogging {

  import info.mukel.telegrambot4s.marshalling.CirceMarshaller._

  val readTimeout: Duration = 50.seconds

  private implicit def circeBodySerializer[B : Encoder]: BodySerializer[B] =
    b => StringBody(toJson[B](b), "utf-8", Some(MediaTypes.Json))

  private def asJson[B : Decoder]: ResponseAs[B, Nothing] =
    asString("utf-8").map(s => fromJson[B](s))

  private val apiBaseUrl = s"https://$telegramHost/bot$token/"

  private def marshall[R](request: ApiRequest[R], url: String): RequestT[Id, String, Nothing] = {
    request match {
      case r: ApiRequestJson[R] =>
        sttp.post(uri"$url").body(r)

      case r: ApiRequestMultipart[R] =>
        val files = r.getFiles

        val parts = files.map {
          case (camelKey, inputFile) =>
            val key = CaseConversions.snakenize(camelKey)
            inputFile match {
              case InputFile.FileId(id) => multipart(key, id)
              case InputFile.Contents(filename, contents) => multipart(key, contents).fileName(filename)
              case InputFile.Path(path) => multipartFile(key, path)
              case other =>
                throw new RuntimeException(s"InputFile $other not supported")
            }
        }

        val fields = parse(CirceMarshaller.toJson(r)).fold(throw _, _.asObject.map {
          _.toMap.mapValues {
            json =>
              json.asString.getOrElse(CirceMarshaller.printer.pretty(json))
          }
        })

        val params = fields.getOrElse(Map())

        sttp.post(uri"$url?$params").multipartBody(parts)
    }
  }

  protected def spawnRequest[R : Decoder](r: RequestT[Id, String, Nothing]): Future[R] = {
    val response = r
      .readTimeout(readTimeout)
      .response(asJson[ApiResponse[R]])
      .send[Future]()

    response
      .map(_.unsafeBody)
      .map(processApiResponse[R])
  }

  /** Spawns a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  override def apply[R : Decoder](request: ApiRequest[R]): Future[R] = {
    val url = apiBaseUrl + request.methodName
    val uuid = UUID.randomUUID()
    logger.trace("REQUEST {} {}", uuid, request)
    val req = marshall(request, url)
    val f = spawnRequest[R](req)
    f.onComplete {
      case Success(response) => logger.trace("RESPONSE {} {}", uuid, response)
      case Failure(e) => logger.error("RESPONSE {} {}", uuid, e)
    }
    f
  }
}
