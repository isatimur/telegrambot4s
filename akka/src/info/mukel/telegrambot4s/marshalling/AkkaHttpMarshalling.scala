package info.mukel.telegrambot4s.marshalling

import akka.http.scaladsl.marshalling.{Marshaller, Marshalling, ToEntityMarshaller}
import akka.http.scaladsl.model.{ContentTypes, HttpEntity, MediaTypes, Multipart}
import akka.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}
import info.mukel.telegrambot4s.methods.{ApiRequest, ApiRequestJson, ApiRequestMultipart}
import info.mukel.telegrambot4s.models.{AkkaInputFile, InputFile}
import io.circe.Decoder


object AkkaHttpMarshalling extends CirceEncoders with CirceDecoders {

  implicit def camelCaseJsonUnmarshaller[R](implicit decR: Decoder[R]): FromEntityUnmarshaller[R] = {
    Unmarshaller
      .stringUnmarshaller
      .forContentTypes(ContentTypes.`application/json`)
      .map(CirceMarshaller.fromJson[R])
  }

  implicit def underscore_case_marshaller[R]: ToEntityMarshaller[ApiRequest[R]] = {
    Marshaller.strict {
      // JSON-only request
      case r: ApiRequestJson[R] =>
        Marshalling.Opaque(() => HttpEntity(ContentTypes.`application/json`, CirceMarshaller.toJson[ApiRequestJson[R]](r)))

      // Request with file payload
      case r: ApiRequestMultipart[R] =>
        val files = r.getFiles
        val parts = files.map {
          case (camelKey, inputFile) =>
            val key = CaseConversions.snakenize(camelKey)
            inputFile match {
              case InputFile.FileId(id) => Multipart.FormData.BodyPart(key, HttpEntity(id))
              case InputFile.Contents(filename, contents) =>
                Multipart.FormData.BodyPart(key, HttpEntity(ContentTypes.`application/octet-stream`, contents),
                  Map("filename" -> filename))
              case InputFile.Path(path) =>
                Multipart.FormData.BodyPart.fromPath(key, MediaTypes.`application/octet-stream`, path)
              case AkkaInputFile.ByteString(filename, bytes) =>
                Multipart.FormData.BodyPart(key, HttpEntity(MediaTypes.`application/octet-stream`, bytes),
                  Map("filename" -> filename))

              case other =>
                throw new RuntimeException(s"InputFile $other not supported")
            }
        }

        val fields = io.circe.parser.parse(CirceMarshaller.toJson(r)).fold(throw _, _.asObject.map {
          _.toMap.mapValues {
            json =>
              json.asString.getOrElse(CirceMarshaller.printer.pretty(json))
          }
        })

        val params = fields.getOrElse(Map())
        val paramParts = params.map { case (key, value) => Multipart.FormData.BodyPart(key, HttpEntity(value)) }

        Marshalling.Opaque(() => Multipart.FormData((parts ++ paramParts): _*).toEntity())
    }
  }
}
