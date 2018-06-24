import scala.concurrent.Await
import scala.concurrent.duration.Duration

object Main {
  def main(args: Array[String]): Unit = {
    val bot = new SelfHosted2048Bot("211113992:AAFS7q_RH7kdHlXZ35oKIShEP2LRtjWK93g", "https://4bb3c300.ngrok.io")
    val eol = bot.run()
    scala.io.StdIn.readLine()
    bot.shutdown()
    Await.result(eol, Duration.Inf)
  }
}
