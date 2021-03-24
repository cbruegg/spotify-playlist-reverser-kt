import com.adamratzman.spotify.*
import com.adamratzman.spotify.models.Token
import fi.iki.elonen.NanoHTTPD
import kotlinx.cli.ArgParser
import kotlinx.cli.ArgType
import kotlinx.cli.default
import kotlinx.cli.required
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

const val port = 8888
const val redirectUri = "http://localhost:$port"

suspend fun main(args: Array<String>) {
    val parsedArgs = parseArgs(args)
    val api = createApi(parsedArgs.clientId, parsedArgs.clientSecret)

    val sourcePlaylist = api.playlists.getClientPlaylist(parsedArgs.sourcePlaylistId)?.toFullPlaylist()
        ?: api.playlists.getPlaylist(parsedArgs.sourcePlaylistId)
        ?: run {
            println("Source playlist '${parsedArgs.sourcePlaylistId}' does not exist!")
            return
        }

    println("Fetching tracks from source playlist '${parsedArgs.sourcePlaylistId}'...")

    val sourcePlaylistTracks = try {
        api.playlists.getPlaylistTracks(parsedArgs.sourcePlaylistId)
    } catch (e: SpotifyException.BadRequestException) {
        println("Source playlist '${parsedArgs.sourcePlaylistId}' does not exist!")
        return
    }

    val targetPlaylistName = parsedArgs.targetPlaylistName ?: "${sourcePlaylist.name} (reversed)"
    val tracks = sourcePlaylistTracks.getAllItems()
    val reversedTracks = tracks.reversed()

    val existingPlaylist = api.playlists.getClientPlaylists().firstOrNull { it?.name == targetPlaylistName }
    if (existingPlaylist != null && !parsedArgs.overrideExisting) {
        println("Playlist '$targetPlaylistName' already exists! Specify --override-existing to override it.")
        return
    }

    if (existingPlaylist != null) {
        println("Removing all tracks from existing playlist '${existingPlaylist.name}'...")
        api.playlists.removeAllClientPlaylistTracks(existingPlaylist.id)
    }

    val targetPlaylist =
        existingPlaylist?.toFullPlaylist() ?: api.playlists.createClientPlaylist(
            targetPlaylistName,
            parsedArgs.targetPlaylistDescription
        )

    println("Writing tracks to playlist '${targetPlaylist.name}' in reverse order...")

    val reversedTrackIds = reversedTracks.filterNotNull().mapNotNull { it.track?.id }.toTypedArray()
    api.playlists.addTracksToClientPlaylist(targetPlaylist.id, *reversedTrackIds)

    println("Done!")
}

data class Args(
    val clientId: String,
    val clientSecret: String,
    val sourcePlaylistId: String,
    val targetPlaylistName: String?,
    val targetPlaylistDescription: String?,
    val overrideExisting: Boolean
)

fun parseArgs(args: Array<String>): Args {
    val parser = ArgParser("spotify-playlist-reverser-kt")
    val clientId by parser.option(ArgType.String, fullName = "client-id", description = "API client ID").required()
    val clientSecret by parser.option(ArgType.String, fullName = "client-secret", description = "API client secret")
        .required()
    val sourcePlaylistId by parser.option(
        ArgType.String,
        fullName = "source-playlist-id",
        description = "ID of the source playlist"
    ).required()
    val targetPlaylistName by parser.option(
        ArgType.String,
        fullName = "target-playlist-name",
        description = "Name of the target playlist. If it already exists, specify --override-existing. Defaults to \"\${sourcePlaylist.name} (reversed)\""
    )
    val targetPlaylistDescription by parser.option(
        ArgType.String,
        fullName = "target-playlist-description",
        description = "If the target playlist does not exist yet, this will be used as its description. Defaults to an empty value."
    )
    val overrideExisting by parser.option(
        ArgType.Boolean,
        fullName = "override-existing",
        description = "If a playlist with the same target name already exists, override it."
    ).default(false)
    parser.parse(args)

    return Args(
        clientId,
        clientSecret,
        sourcePlaylistId,
        targetPlaylistName,
        targetPlaylistDescription,
        overrideExisting
    )
}

suspend fun createApi(clientId: String, clientSecret: String): SpotifyClientApi {
    val savedTokenFile = File(".spotify_token.txt")
    val apiFromSavedRefreshToken = apiFromSavedRefreshToken(savedTokenFile, clientId, clientSecret)
    if (apiFromSavedRefreshToken != null) {
        return apiFromSavedRefreshToken
    }

    val authUrl = getSpotifyAuthorizationUrl(
        SpotifyScope.PLAYLIST_READ_PRIVATE, SpotifyScope.PLAYLIST_MODIFY_PUBLIC,
        SpotifyScope.PLAYLIST_MODIFY_PRIVATE, SpotifyScope.USER_READ_PRIVATE,
        clientId = clientId,
        redirectUri = redirectUri
    )
    println("Go to this URL for authorization: $authUrl")

    val authCode = waitForAuthCode()

    val api = spotifyClientApi(
        clientId,
        clientSecret,
        redirectUri,
        SpotifyUserAuthorization(authorizationCode = authCode)
    ).build()

    savedTokenFile.writeText(Json.encodeToString(Token.serializer(),api.token))

    return api
}

suspend fun apiFromSavedRefreshToken(tokenFile: File, clientId: String, clientSecret: String): SpotifyClientApi? {
    if (!tokenFile.exists()) {
        return null
    }

    val savedToken = try {
        tokenFile.readText()
    } catch (e: IOException) {
        println("Failed reading refresh token!")
        return null
    }

    val api = spotifyClientApi(
        clientId,
        clientSecret,
        redirectUri,
        Json.decodeFromString(Token.serializer(), savedToken)
    ).build()

    try {
        api.playlists.getClientPlaylists(limit = 1)
    } catch (e: SpotifyException.BadRequestException) {
        println("Saved token is invalid or outdated.")
        return null
    }

    return api
}

suspend fun waitForAuthCode(): String {
    return suspendCancellableCoroutine { cancellableContinuation ->
        val server = object : NanoHTTPD(port) {
            val isFirstRequest = AtomicBoolean(true)

            override fun serve(session: IHTTPSession): Response {
                if (!isFirstRequest.getAndSet(false)) {
                    return newFixedLengthResponse("Only one response is allowed!")
                }

                val code = session.parameters["code"]?.first()
                GlobalScope.launch {
                    if (code != null) {
                        cancellableContinuation.resume(code)
                    } else {
                        cancellableContinuation.resumeWithException(IOException("Could not parse auth code!"))
                    }
                }
                GlobalScope.launch {
                    delay(5000) // Wait for response to get sent
                    stop()
                }
                return newFixedLengthResponse("<html><head><script>window.close()</script></head></html>")
            }
        }

        server.start()
        cancellableContinuation.invokeOnCancellation { server.stop() }
    }
}
