package info.mukel.telegrambot4s.clients

import java.net.Proxy
import java.nio.file.Files
import java.util.UUID

import info.mukel.telegrambot4s.api.RequestHandler
import info.mukel.telegrambot4s.marshalling.{CaseConversions, CirceMarshaller}
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiRequestJson, ApiRequestMultipart, ApiResponse}
import info.mukel.telegrambot4s.models.InputFile
import io.circe.Decoder
import io.circe.parser.parse
import scalaj.http.{Http, HttpRequest, MultiPart}
import slogging.StrictLogging

import scala.concurrent.{ExecutionContext, Future, blocking}
import scala.util.{Failure, Success}

/** Scalaj-http Telegram Bot API client
  *
  * Provide transparent camelCase <-> underscore_case conversions during serialization/deserialization.
  *
  * The Scalaj-http supports the following InputFiles:
  *   InputFile.FileId
  *   InputFile.Contents
  *   InputFile.Path
  *
  * @param token Bot token
  */
class ScalajHttpClient(token: String, proxy: Proxy = Proxy.NO_PROXY, telegramHost: String = "api.telegram.org")
                      (implicit ec: ExecutionContext) extends RequestHandler with StrictLogging {

  import CirceMarshaller._

  val connectionTimeoutMs = 10000
  val readTimeoutMs = 50000

  private val apiBaseUrl = s"https://$telegramHost/bot$token/"

  protected def spawnRequest[R](r: HttpRequest)(implicit decR: Decoder[R]): Future[R] = {
    Future {
      blocking {
        r.timeout(connectionTimeoutMs, readTimeoutMs).proxy(proxy).asString
      }
    } map {
      x =>
        if (x.isSuccess)
          fromJson[ApiResponse[R]](x.body)
        else
          throw new Exception(s"Error ${x.code} on request")
    } map (t => processApiResponse[R](t))
  }

  def marshall[R](request: ApiRequest[R], url: String): HttpRequest = {
    request match {
      case r: ApiRequestJson[R] =>
        Http(url)
          .postData(CirceMarshaller.toJson(r))
          .header("Content-Type", "application/json")

      case r: ApiRequestMultipart[R] =>

        // InputFile.FileId must be encoded as query params.
        val (fileIds, files) = r.getFiles.partition {
          case (key, _: InputFile.FileId) => true
          case _ => false
        }

        val parts = files.map {
          case (camelKey, inputFile) =>
            val key = CaseConversions.snakenize(camelKey)
            inputFile match {
              case InputFile.FileId(id) =>
                throw new RuntimeException("InputFile.FileId cannot must be encoded as a query param")

              case InputFile.Contents(filename, contents) =>
                MultiPart(key, filename, "application/octet-stream", contents)

              case InputFile.Path(path) =>
                MultiPart(key, path.getFileName.toString(),
                  "application/octet-stream",
                  Files.newInputStream(path),
                  Files.size(path),
                  _ => ())

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

        val fileIdsParams = fileIds.map {
          case (key, inputFile: InputFile.FileId) =>
            CaseConversions.snakenize(key) -> inputFile.fileId
        }

        val params = fields.getOrElse(Map())

        Http(url).params(params ++ fileIdsParams).postMulti(parts: _*)
    }
  }

  /** Spawn a type-safe request.
    *
    * @param request
    * @tparam R Request's expected result type
    * @return The request result wrapped in a Future (async)
    */
  override def apply[R](request: ApiRequest[R])(implicit decR: Decoder[R]): Future[R] = {
    val url = apiBaseUrl + request.methodName
    val uuid = UUID.randomUUID()
    logger.debug(s"REQUEST $uuid $request")

    val scalajRequest = marshall(request, url)

    val f = spawnRequest[R](scalajRequest)
    f.onComplete {
      case Success(response) => logger.debug(s"RESPONSE $uuid $response")
      case Failure(e) => logger.debug(s"RESPONSE $uuid $e")
    }
    f
  }
}