import java.time.Instant

import info.mukel.telegrambot4s.Implicits._
import info.mukel.telegrambot4s.api.Polling
import info.mukel.telegrambot4s.api.declarative.{Callbacks, InlineQueries}
import info.mukel.telegrambot4s.methods._
import info.mukel.telegrambot4s.models.UpdateType.Filters._
import info.mukel.telegrambot4s.models._

import scala.concurrent.duration._
import info.mukel.telegrambot4s.marshalling.CirceMarshaller._

/**
  * Send self-destructing messages.
  * Ported from: https://github.com/Pitasi/selfdestructbot
  */
class SelfDestructBot(token: String) extends ExampleBot(token)
  with Polling
  with InlineQueries
  with Callbacks {

  val timeouts = Seq(3, 5, 10, 30)

  override def allowedUpdates = InlineUpdates ++ CallbackUpdates

  def buildResult(timeout: Int, msg: String): InlineQueryResult = {
    InlineQueryResultArticle(s"$timeout", s"$timeout seconds",
      inputMessageContent = InputTextMessageContent(msg),
      description = s"Message will be deleted in $timeout seconds",
      replyMarkup = InlineKeyboardMarkup.singleButton(button(now)))
  }

  def now = Instant.now().getEpochSecond

  def button(timeLeft: Long) = InlineKeyboardButton.callbackData("⏳ left?", "" + timeLeft)

  onCallbackQuery {
    implicit cbq =>
      val left = cbq.data.map(_.toLong - now).getOrElse(-1L)
      ackCallback(s"$left seconds remaining.", cacheTime = 0)
  }

  onChosenInlineResult { implicit result =>
    val delay = result.resultId.toInt
    request(EditMessageReplyMarkup(
      inlineMessageId = result.inlineMessageId,
      replyMarkup = InlineKeyboardMarkup.singleButton(button(now + delay))))

    Utils.after(delay.seconds) {
      request(EditMessageText(
        text = "⌛ Expired",
        inlineMessageId = result.inlineMessageId))
    }
  }

  onInlineQuery { implicit q =>
    val results = if (q.query.isEmpty)
      Seq.empty
    else
      timeouts.map(buildResult(_, q.query))
    answerInlineQuery(results, 5)
  }
}
