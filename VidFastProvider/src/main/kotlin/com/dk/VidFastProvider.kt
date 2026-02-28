package com.dk

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addImdbId
import com.lagradost.cloudstream3.LoadResponse.Companion.addTMDbId
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson

/**
 * VidFastProvider – DK Repo
 *
 * Streams movies and TV shows from vidfast.pro using TMDB as the discovery
 * layer and VidFast embed URLs for actual playback.
 *
 * Movie embed : https://vidfast.pro/movie/{tmdbId}?autoPlay=true
 * TV embed    : https://vidfast.pro/tv/{tmdbId}/{season}/{episode}?autoPlay=true
 */
class VidFastProvider : MainAPI() {

    override var name            = "VidFast"
    override var mainUrl         = "https://vidfast.pro"
    override var lang            = "en"
    override val hasMainPage     = true
    override val hasQuickSearch  = false
    override val supportedTypes  = setOf(TvType.Movie, TvType.TvSeries)

    // ─── TMDB API ────────────────────────────────────────────────────────────
    private val tmdbApiUrl    = "https://api.themoviedb.org/3"
    private val tmdbApiKey    = "8d6d91941230817f7807d6181306b6da" // public demo key
    private val tmdbImageUrl  = "https://image.tmdb.org/t/p/w500"

    // ─── Main Page ──────────────────────────────────────────────────────────
    override val mainPage = mainPageOf(
        "$tmdbApiUrl/movie/popular?api_key=$tmdbApiKey"                 to "🔥 Popular Movies",
        "$tmdbApiUrl/movie/top_rated?api_key=$tmdbApiKey"               to "⭐ Top Rated Movies",
        "$tmdbApiUrl/movie/now_playing?api_key=$tmdbApiKey"             to "🎬 Now Playing",
        "$tmdbApiUrl/movie/upcoming?api_key=$tmdbApiKey"                to "📅 Upcoming Movies",
        "$tmdbApiUrl/tv/popular?api_key=$tmdbApiKey"                    to "📺 Popular TV Shows",
        "$tmdbApiUrl/tv/top_rated?api_key=$tmdbApiKey"                  to "⭐ Top Rated TV Shows",
        "$tmdbApiUrl/tv/on_the_air?api_key=$tmdbApiKey"                 to "📡 On The Air",
        "$tmdbApiUrl/trending/all/week?api_key=$tmdbApiKey"             to "📈 Trending This Week",
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url    = "${request.data}&page=$page"
        val result = app.get(url).parsedSafe<TmdbResults>() ?: return newHomePageResponse(
            request.name, emptyList()
        )
        val items = result.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
        return newHomePageResponse(request.name, items)
    }

    // ─── Search ─────────────────────────────────────────────────────────────
    override suspend fun search(query: String): List<SearchResponse>? {
        val movieResults = app.get(
            "$tmdbApiUrl/search/movie?api_key=$tmdbApiKey&query=$query"
        ).parsedSafe<TmdbResults>()?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        val tvResults = app.get(
            "$tmdbApiUrl/search/tv?api_key=$tmdbApiKey&query=$query"
        ).parsedSafe<TmdbResults>()?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        return (movieResults + tvResults).sortedByDescending { it.quality.value }
    }

    // ─── Load ────────────────────────────────────────────────────────────────
    override suspend fun load(url: String): LoadResponse? {
        // url format: "movie:12345" or "tv:67890"
        val (mediaType, tmdbId) = url.split(":").let {
            if (it.size < 2) return null
            Pair(it[0], it[1])
        }

        return when (mediaType) {
            "movie" -> loadMovie(tmdbId)
            "tv"    -> loadTvSeries(tmdbId)
            else    -> null
        }
    }

    private suspend fun loadMovie(tmdbId: String): MovieLoadResponse? {
        val detail = app.get(
            "$tmdbApiUrl/movie/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,recommendations,videos,external_ids"
        ).parsedSafe<TmdbDetail>() ?: return null

        return newMovieLoadResponse(
            name        = detail.title ?: detail.originalTitle ?: return null,
            url         = "movie:$tmdbId",
            type        = TvType.Movie,
            dataUrl     = MovieData(tmdbId, detail.imdbId).toJson()
        ) {
            this.posterUrl          = detail.posterPath?.let { "$tmdbImageUrl$it" }
            this.backgroundPosterUrl= detail.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            this.year               = detail.releaseDate?.take(4)?.toIntOrNull()
            this.plot               = detail.overview
            this.tags               = detail.genres?.mapNotNull { it.name }
            this.rating             = detail.voteAverage?.times(10)?.toInt()
            this.duration           = detail.runtime
            this.actors             = detail.credits?.cast?.take(15)?.mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null, cast.profilePath?.let { "$tmdbImageUrl$it" }))
            }
            this.recommendations    = detail.recommendations?.results?.mapNotNull { it.toSearchResponse() }
            addImdbId(detail.imdbId)
            addTMDbId(tmdbId)
        }
    }

    private suspend fun loadTvSeries(tmdbId: String): TvSeriesLoadResponse? {
        val detail = app.get(
            "$tmdbApiUrl/tv/$tmdbId?api_key=$tmdbApiKey&append_to_response=credits,recommendations,videos,external_ids"
        ).parsedSafe<TmdbDetail>() ?: return null

        val episodes = detail.seasons?.flatMap { season ->
            val sNum = season.seasonNumber ?: return@flatMap emptyList()
            if (sNum == 0) return@flatMap emptyList() // skip specials

            val seasonDetail = app.get(
                "$tmdbApiUrl/tv/$tmdbId/season/$sNum?api_key=$tmdbApiKey"
            ).parsedSafe<TmdbSeason>()

            seasonDetail?.episodes?.mapNotNull { ep ->
                val epNum = ep.episodeNumber ?: return@mapNotNull null
                Episode(
                    data           = EpisodeData(tmdbId, sNum, epNum).toJson(),
                    name           = ep.name,
                    season         = sNum,
                    episode        = epNum,
                    posterUrl      = ep.stillPath?.let { "$tmdbImageUrl$it" },
                    rating         = ep.voteAverage?.times(10)?.toInt(),
                    description    = ep.overview,
                ).apply {
                    this.addDate(ep.airDate)
                }
            } ?: emptyList()
        } ?: emptyList()

        val imdbId = detail.externalIds?.imdbId

        return newTvSeriesLoadResponse(
            name     = detail.name ?: detail.originalName ?: return null,
            url      = "tv:$tmdbId",
            type     = TvType.TvSeries,
            episodes = episodes
        ) {
            this.posterUrl          = detail.posterPath?.let { "$tmdbImageUrl$it" }
            this.backgroundPosterUrl= detail.backdropPath?.let { "https://image.tmdb.org/t/p/original$it" }
            this.year               = detail.firstAirDate?.take(4)?.toIntOrNull()
            this.plot               = detail.overview
            this.tags               = detail.genres?.mapNotNull { it.name }
            this.rating             = detail.voteAverage?.times(10)?.toInt()
            this.showStatus         = when (detail.status) {
                "Returning Series" -> ShowStatus.Ongoing
                "Ended"           -> ShowStatus.Completed
                "Canceled"        -> ShowStatus.Completed
                else              -> null
            }
            this.actors             = detail.credits?.cast?.take(15)?.mapNotNull { cast ->
                ActorData(Actor(cast.name ?: return@mapNotNull null, cast.profilePath?.let { "$tmdbImageUrl$it" }))
            }
            this.recommendations    = detail.recommendations?.results?.mapNotNull { it.toSearchResponse() }
            addImdbId(imdbId)
            addTMDbId(tmdbId)
        }
    }

    // ─── Load Links ──────────────────────────────────────────────────────────
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Determine embed URL from serialized data
        val embedUrl = when {
            data.startsWith("{\"tmdbId\"") -> {
                // MovieData
                val movieData = parseJson<MovieData>(data)
                "$mainUrl/movie/${movieData.tmdbId}?autoPlay=true"
            }
            data.startsWith("{\"episodeTmdbId\"") -> {
                // EpisodeData
                val epData = parseJson<EpisodeData>(data)
                "$mainUrl/tv/${epData.episodeTmdbId}/${epData.season}/${epData.episode}?autoPlay=true"
            }
            else -> return false
        }

        // Use loadExtractor to scrape the embed page for the m3u8 stream
        loadExtractor(embedUrl, mainUrl, subtitleCallback, callback)

        return true
    }

    // ─── Data Classes ────────────────────────────────────────────────────────
    data class MovieData(
        @JsonProperty("tmdbId")  val tmdbId: String,
        @JsonProperty("imdbId")  val imdbId: String? = null,
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
        @JsonProperty("id")            val id: Int?    = null,
        @JsonProperty("title")         val title: String? = null,
        @JsonProperty("name")          val name: String?  = null,
        @JsonProperty("poster_path")   val posterPath: String? = null,
        @JsonProperty("media_type")    val mediaType: String? = null,
        @JsonProperty("vote_average")  val voteAverage: Double? = null,
        @JsonProperty("release_date")  val releaseDate: String? = null,
        @JsonProperty("first_air_date")val firstAirDate: String? = null,
    ) {
        fun toSearchResponse(): SearchResponse? {
            val tmdbId   = id?.toString() ?: return null
            val itemName = title ?: name ?: return null
            val poster   = posterPath?.let { "https://image.tmdb.org/t/p/w500$it" }
            val isMovie  = title != null || mediaType == "movie"
            val year     = (releaseDate ?: firstAirDate)?.take(4)?.toIntOrNull()

            return if (isMovie) {
                newMovieSearchResponse(itemName, "movie:$tmdbId", TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(itemName, "tv:$tmdbId", TvType.TvSeries) {
                    this.posterUrl = poster
                    this.year = year
                }
            }
        }
    }

    data class TmdbDetail(
        @JsonProperty("id")               val id: Int?       = null,
        @JsonProperty("title")            val title: String? = null,
        @JsonProperty("original_title")   val originalTitle: String? = null,
        @JsonProperty("name")             val name: String?  = null,
        @JsonProperty("original_name")    val originalName: String? = null,
        @JsonProperty("overview")         val overview: String? = null,
        @JsonProperty("poster_path")      val posterPath: String? = null,
        @JsonProperty("backdrop_path")    val backdropPath: String? = null,
        @JsonProperty("release_date")     val releaseDate: String? = null,
        @JsonProperty("first_air_date")   val firstAirDate: String? = null,
        @JsonProperty("vote_average")     val voteAverage: Double? = null,
        @JsonProperty("runtime")          val runtime: Int? = null,
        @JsonProperty("status")           val status: String? = null,
        @JsonProperty("imdb_id")          val imdbId: String? = null,
        @JsonProperty("genres")           val genres: List<TmdbGenre>? = null,
        @JsonProperty("seasons")          val seasons: List<TmdbSeasonInfo>? = null,
        @JsonProperty("credits")          val credits: TmdbCredits? = null,
        @JsonProperty("recommendations")  val recommendations: TmdbResults? = null,
        @JsonProperty("external_ids")     val externalIds: TmdbExternalIds? = null,
    )

    data class TmdbGenre(
        @JsonProperty("name") val name: String? = null,
    )

    data class TmdbSeasonInfo(
        @JsonProperty("season_number") val seasonNumber: Int? = null,
        @JsonProperty("episode_count") val episodeCount: Int? = null,
    )

    data class TmdbSeason(
        @JsonProperty("episodes") val episodes: List<TmdbEpisode>? = null,
    )

    data class TmdbEpisode(
        @JsonProperty("episode_number") val episodeNumber: Int?    = null,
        @JsonProperty("name")           val name: String?          = null,
        @JsonProperty("overview")       val overview: String?      = null,
        @JsonProperty("still_path")     val stillPath: String?     = null,
        @JsonProperty("air_date")       val airDate: String?       = null,
        @JsonProperty("vote_average")   val voteAverage: Double?   = null,
    )

    data class TmdbCredits(
        @JsonProperty("cast") val cast: List<TmdbCast>? = null,
    )

    data class TmdbCast(
        @JsonProperty("name")         val name: String?        = null,
        @JsonProperty("profile_path") val profilePath: String? = null,
    )

    data class TmdbExternalIds(
        @JsonProperty("imdb_id") val imdbId: String? = null,
    )
}
