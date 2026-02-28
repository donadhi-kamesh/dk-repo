package com.dk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.newExtractorLink

/**
 * VidFastProvider – DK Repo
 *
 * Streams movies and TV shows from vidfast.pro using TMDB as the discovery
 * layer and VidFast embed URLs for actual playback.
 *
 * Movie embed : https://vidfast.pro/embed/movie/{tmdbId}?autoPlay=true
 * TV embed    : https://vidfast.pro/embed/tv/{tmdbId}/{season}/{episode}?autoPlay=true
 */
class VidFastProvider : MainAPI() {

    override var name           = "VidFast"
    override var mainUrl        = "https://vidfast.pro"
    override var lang           = "en"
    override val hasMainPage    = true
    override val hasQuickSearch = false
    override val supportedTypes = setOf(TvType.Movie, TvType.TvSeries)

    // ─── TMDB API ────────────────────────────────────────────────────────────
    private val tmdbApiUrl   = "https://api.themoviedb.org/3"
    private val tmdbApiKey   = "167005118451cfe63da18e461dacd1d1"
    private val tmdbImageUrl = "https://image.tmdb.org/t/p/w500"

    // ─── Main Page ───────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$tmdbApiUrl/movie/popular?api_key=$tmdbApiKey"      to "🔥 Popular Movies",
        "$tmdbApiUrl/movie/top_rated?api_key=$tmdbApiKey"    to "⭐ Top Rated Movies",
        "$tmdbApiUrl/movie/now_playing?api_key=$tmdbApiKey"  to "🎬 Now Playing",
        "$tmdbApiUrl/movie/upcoming?api_key=$tmdbApiKey"     to "📅 Upcoming Movies",
        "$tmdbApiUrl/tv/popular?api_key=$tmdbApiKey"         to "📺 Popular TV Shows",
        "$tmdbApiUrl/tv/top_rated?api_key=$tmdbApiKey"       to "⭐ Top Rated TV Shows",
        "$tmdbApiUrl/tv/on_the_air?api_key=$tmdbApiKey"      to "📡 On The Air",
        "$tmdbApiUrl/trending/all/week?api_key=$tmdbApiKey"  to "📈 Trending This Week",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val result = app.get("${request.data}&page=$page")
            .parsedSafe<TmdbResults>() ?: return newHomePageResponse(request.name, emptyList())
        val items = result.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ──────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse>? {
        val movieRes = app.get("$tmdbApiUrl/search/movie?api_key=$tmdbApiKey&query=$query")
            .parsedSafe<TmdbResults>()?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        val tvRes = app.get("$tmdbApiUrl/search/tv?api_key=$tmdbApiKey&query=$query")
            .parsedSafe<TmdbResults>()?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return movieRes + tvRes
    }

    // ─── Load ────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        // URL format: https://vidfast.pro/movie/{tmdbId} or https://vidfast.pro/tv/{tmdbId}
        val path = url.removePrefix(mainUrl).trimStart('/')
        val segments = path.split("/")
        if (segments.size < 2) return null
        val mediaType = segments[0]
        val tmdbId = segments[1]
        return when (mediaType) {
            "movie" -> loadMovie(tmdbId)
            "tv"    -> loadTvSeries(tmdbId)
            else    -> null
        }
    }

    private suspend fun loadMovie(tmdbId: String): MovieLoadResponse? {
        val detail = app.get(
            "$tmdbApiUrl/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,recommendations,external_ids"
        ).parsedSafe<TmdbDetail>() ?: return null

        return newMovieLoadResponse(
            name    = detail.title ?: detail.originalTitle ?: return null,
            url     = "$mainUrl/movie/$tmdbId",
            type    = TvType.Movie,
            dataUrl = MovieData(tmdbId, detail.imdbId).toJson()
        ) {
            posterUrl           = detail.posterPath?.let { "$tmdbImageUrl$it" }
            backgroundPosterUrl = detail.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            year                = detail.releaseDate?.take(4)?.toIntOrNull()
            plot                = detail.overview
            tags                = detail.genres?.mapNotNull { it.name }
            duration            = detail.runtime
            actors              = detail.credits?.cast?.take(15)?.mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null,
                    cast.profilePath?.let { "$tmdbImageUrl$it" }))
            }
            recommendations = detail.recommendations?.results?.mapNotNull { it.toSearchResponse() }
            addImdbId(detail.imdbId)
            addTMDbId(tmdbId)
        }
    }

    private suspend fun loadTvSeries(tmdbId: String): TvSeriesLoadResponse? {
        val detail = app.get(
            "$tmdbApiUrl/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,recommendations,external_ids"
        ).parsedSafe<TmdbDetail>() ?: return null

        val episodes = detail.seasons?.flatMap { season ->
            val sNum = season.seasonNumber ?: return@flatMap emptyList<Episode>()
            if (sNum == 0) return@flatMap emptyList()
            val seasonDetail = app.get(
                "$tmdbApiUrl/tv/$tmdbId/season/$sNum?api_key=$tmdbApiKey"
            ).parsedSafe<TmdbSeason>()
            seasonDetail?.episodes?.mapNotNull { ep ->
                val epNum = ep.episodeNumber ?: return@mapNotNull null
                newEpisode(EpisodeData(tmdbId, sNum, epNum).toJson()) {
                    this.name        = ep.name
                    this.season      = sNum
                    this.episode     = epNum
                    this.posterUrl   = ep.stillPath?.let { "$tmdbImageUrl$it" }
                    this.description = ep.overview
                    addDate(ep.airDate)
                }
            } ?: emptyList()
        } ?: emptyList()

        return newTvSeriesLoadResponse(
            name     = detail.name ?: detail.originalName ?: return null,
            url      = "$mainUrl/tv/$tmdbId",
            type     = TvType.TvSeries,
            episodes = episodes
        ) {
            posterUrl           = detail.posterPath?.let { "$tmdbImageUrl$it" }
            backgroundPosterUrl = detail.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            year                = detail.firstAirDate?.take(4)?.toIntOrNull()
            plot                = detail.overview
            tags                = detail.genres?.mapNotNull { it.name }
            showStatus          = when (detail.status) {
                "Returning Series" -> ShowStatus.Ongoing
                "Ended", "Canceled" -> ShowStatus.Completed
                else -> null
            }
            actors = detail.credits?.cast?.take(15)?.mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null,
                    cast.profilePath?.let { "$tmdbImageUrl$it" }))
            }
            recommendations = detail.recommendations?.results?.mapNotNull { it.toSearchResponse() }
            addImdbId(detail.externalIds?.imdbId)
            addTMDbId(tmdbId)
        }
    }

    // ─── Load Links ──────────────────────────────────────────────────────────

    // Static API path segments — hardcoded in VidFast's JS bundle (365-*.js)
    // These are the same for ALL movies/shows and do not change per request
    private val apiBasePath = "/hezushon/cu/" +
        "1c971a0506a9ccbd81f3d418200a6389b87859a6/" +
        "APA91MSAX28_PU-wlI0-o0yb20QnaJc86iw7KTji8Mz5UeDN56gC9NuxDesrOoNU1zEM95-KBg811zMVm40xMEFVL4YA6PttSoeweep0uh9YbC7l3joL-MuhCACz5lsNgiW4hME6axwAW8zfHj198wl646Ln9cfyJv3M17J1-PD4ocZXgumXl54/" +
        "a0a1cce2/j/" +
        "1000074535403666/" +
        "b2bbf815-5324-52fa-a719-13a0fcc45221/" +
        "e8f43625a0cf6e68f50bb8f1870683ba8c926aed5d9d25dd19789ef16f7c3040"

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val (vidFastUrl, tmdbId) = when {
            data.startsWith("{\"tmdbId\"") -> {
                val movieData = parseJson<MovieData>(data)
                "$mainUrl/movie/${movieData.tmdbId}" to movieData.tmdbId
            }
            data.startsWith("{\"episodeTmdbId\"") -> {
                val epData = parseJson<EpisodeData>(data)
                "$mainUrl/tv/${epData.episodeTmdbId}/${epData.season}/${epData.episode}" to epData.episodeTmdbId
            }
            else -> return false
        }

        try {
            // Step 1: Fetch the VidFast page to get the 'en' token
            val pageHtml = app.get(vidFastUrl, referer = mainUrl).text

            // Step 2: Extract the 'en' token from Next.js self.__next_f.push data
            val enToken = Regex(""""en":"([^"]+)"""").find(pageHtml)?.groupValues?.get(1)
                ?: return true

            // Step 3: Call the server list API
            val serverListUrl = "$mainUrl$apiBasePath/krI/$enToken"
            val serverResp = app.get(
                serverListUrl,
                referer = vidFastUrl,
                headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
            ).text

            // Step 4: Parse server list
            val servers = try {
                parseJson<List<VidFastServer>>(serverResp)
            } catch (_: Exception) { emptyList() }

            // Step 5: For each server, get the m3u8 stream URL
            for (server in servers) {
                try {
                    val serverData = server.data ?: continue
                    val streamApiUrl = "$mainUrl$apiBasePath/L5aN/$serverData"
                    val streamResp = app.get(
                        streamApiUrl,
                        referer = vidFastUrl,
                        headers = mapOf("User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36")
                    ).text
                    val streamData = parseJson<VidFastStream>(streamResp)

                    val m3u8Url = streamData.url ?: continue
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${server.name ?: "Server"}",
                            url = m3u8Url,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = mainUrl
                            this.quality = Qualities.Unknown.value
                        }
                    )

                    // Extract subtitles from stream response tracks
                    streamData.tracks?.forEach { track ->
                        if (track.kind == "captions" || track.kind == "subtitles") {
                            subtitleCallback.invoke(
                                SubtitleFile(
                                    lang = track.label ?: "Unknown",
                                    url = track.file ?: return@forEach
                                )
                            )
                        }
                    }
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        return true
    }

    // ─── VidFast API Models ──────────────────────────────────────────────────
    data class VidFastServer(
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("data") val data: String? = null,
    )

    data class VidFastStream(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("tracks") val tracks: List<VidFastTrack>? = null,
    )

    data class VidFastTrack(
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("label") val label: String? = null,
        @JsonProperty("kind") val kind: String? = null,
    )

    // ─── Data Classes ────────────────────────────────────────────────────────
    data class MovieData(
        @JsonProperty("tmdbId") val tmdbId: String,
        @JsonProperty("imdbId") val imdbId: String? = null,
    )

    data class EpisodeData(
        @JsonProperty("episodeTmdbId") val episodeTmdbId: String,
        @JsonProperty("season")        val season: Int,
        @JsonProperty("episode")       val episode: Int,
    )

    // ─── TMDB JSON Models ────────────────────────────────────────────────────
    data class TmdbResults(
        @JsonProperty("results") val results: List<TmdbItem>? = null,
    )

    data class TmdbItem(
        @JsonProperty("id")             val id: Int? = null,
        @JsonProperty("title")          val title: String? = null,
        @JsonProperty("name")           val name: String? = null,
        @JsonProperty("poster_path")    val posterPath: String? = null,
        @JsonProperty("backdrop_path")  val backdropPath: String? = null,
        @JsonProperty("media_type")     val mediaType: String? = null,
        @JsonProperty("vote_average")   val voteAverage: Double? = null,
        @JsonProperty("release_date")   val releaseDate: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null,
    )

    private fun TmdbItem.toSearchResponse(): SearchResponse? {
        val tmdbId   = id?.toString() ?: return null
        val itemName = title ?: name ?: return null
        val poster   = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }

        // Skip person/company results (e.g. from trending endpoint)
        if (mediaType == "person") return null

        // mediaType is "movie" or null (movie-only endpoints); otherwise treat as TV
        val isMovie = mediaType == "movie" || (mediaType == null && title != null)

        return if (isMovie) {
            newMovieSearchResponse(itemName, "$mainUrl/movie/$tmdbId", TvType.Movie) {
                this.posterUrl = poster
            }
        } else {
            newTvSeriesSearchResponse(itemName, "$mainUrl/tv/$tmdbId", TvType.TvSeries) {
                this.posterUrl = poster
            }
        }
    }

    data class TmdbDetail(
        @JsonProperty("id")              val id: Int? = null,
        @JsonProperty("title")           val title: String? = null,
        @JsonProperty("original_title")  val originalTitle: String? = null,
        @JsonProperty("name")            val name: String? = null,
        @JsonProperty("original_name")   val originalName: String? = null,
        @JsonProperty("overview")        val overview: String? = null,
        @JsonProperty("poster_path")     val posterPath: String? = null,
        @JsonProperty("backdrop_path")   val backdropPath: String? = null,
        @JsonProperty("release_date")    val releaseDate: String? = null,
        @JsonProperty("first_air_date")  val firstAirDate: String? = null,
        @JsonProperty("vote_average")    val voteAverage: Double? = null,
        @JsonProperty("runtime")         val runtime: Int? = null,
        @JsonProperty("status")          val status: String? = null,
        @JsonProperty("imdb_id")         val imdbId: String? = null,
        @JsonProperty("genres")          val genres: List<TmdbGenre>? = null,
        @JsonProperty("seasons")         val seasons: List<TmdbSeasonInfo>? = null,
        @JsonProperty("credits")         val credits: TmdbCredits? = null,
        @JsonProperty("recommendations") val recommendations: TmdbResults? = null,
        @JsonProperty("external_ids")    val externalIds: TmdbExternalIds? = null,
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String? = null,
    )

    data class TmdbSeasonInfo(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int? = null,
        @JsonProperty("name")           val name: String? = null,
        @JsonProperty("overview")       val overview: String? = null,
        @JsonProperty("still_path")     val stillPath: String? = null,
        @JsonProperty("air_date")       val airDate: String? = null,
        @JsonProperty("vote_average")   val voteAverage: Double? = null,
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    )

    data class TmdbCast(
        @JsonProperty("name")         val name: String? = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )
}
