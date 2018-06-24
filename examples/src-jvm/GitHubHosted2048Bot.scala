import akka.http.scaladsl.model.Uri
import akka.http.scaladsl.model.Uri.{Path, Query}
import akka.http.scaladsl.model.headers.{HttpOrigin, HttpOriginRange}
import akka.http.scaladsl.server.Directives._
import akka.http.scaladsl.server.Route
import ch.megard.akka.http.cors.scaladsl.CorsDirectives.cors
import ch.megard.akka.http.cors.scaladsl.settings.CorsSettings
import info.mukel.telegrambot4s.api.declarative.{Callbacks, Commands}
import info.mukel.telegrambot4s.api.{GameManager, Payload, Polling}
import info.mukel.telegrambot4s.marshalling.CirceMarshaller._
import info.mukel.telegrambot4s.methods.SendGame

/**
  * 2048 hosted on GitHub Pages.
  *
  * Games hosted externally (on GitHub) can be used from any bot using
  * the endpoints provided by [[GameManager]] to query and set the scores.
  * All the wiring is done (server address is passed to the game) making
  * games completely independent.
  *
  * '''Security:'''
  * Public games can potentially contain, or be modified to contain malicious code,
  * ads, user data collection... that may violate Telegram's terms.
  * Be careful and only link games that you own/trust.
  *
  * To spawn the GameManager, a server is needed.
  * Otherwise use your favorite tunnel e.g. [[https://ngrok.com ngrok]]
  * Spawn the tunnel (and leave it running):
  * {{{
  *   ngrok http 8080
  * }}}
  *
  * Use the ngrok-provided address (e.g. https://e719813a.ngrok.io) as
  * your 'gameManagerHost'.
  *
  * The following endpoints should be linked to GameManager:
  * gameManagerHost/games/api/getScores
  * gameManagerHost/games/api/setScore
  *
  * @param token           Bot's token.
  * @param gameManagerHost Base URL of the game manager.
  */
class GitHubHosted2048Bot(token: String, gameManagerHost: String)
  extends AkkaExampleBot(token)
    with Polling
    with Commands
    with Callbacks
    with GameManager {

  override val port: Int = 8080

  val Play2048 = "play_2048"
  val GitHubPages = Uri("https://mukel.github.io")

  onCommand(Play2048 or "2048" or "start") { implicit msg =>
    request(
      SendGame(msg.source, Play2048)
    )
  }

  onCallbackQuery { implicit cbq =>
    val acked = cbq.gameShortName.collect {
      case Play2048 =>
        val payload = Payload.forCallbackQuery(gameManagerHost)

        val url = GitHubPages
          .withPath(Path(s"/$Play2048/index.html"))
          .withQuery(Query("payload" -> payload.base64Encode))

        ackCallback(url = Some(url.toString()))
    }

    acked.getOrElse(ackCallback())
  }

  // Enable CORS for GitHub Pages.
  // Allows GitHub Pages to call cross-domain getScores and setScore.
  private val allowGitHub = CorsSettings.defaultSettings
    .withAllowedOrigins(HttpOriginRange(HttpOrigin(GitHubPages.toString())))

  override def routes: Route =
    super.routes ~
      cors(allowGitHub) {
        gameManagerRoute
      }
}
