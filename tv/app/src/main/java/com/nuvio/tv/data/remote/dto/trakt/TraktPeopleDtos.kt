package com.nuvio.tv.data.remote.dto.trakt

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class TraktPersonImagesDto(
    @Json(name = "headshot") val headshot: List<String>? = null,
    @Json(name = "fanart") val fanart: List<String>? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonDto(
    @Json(name = "name") val name: String? = null,
    @Json(name = "ids") val ids: TraktIdsDto? = null,
    @Json(name = "biography") val biography: String? = null,
    @Json(name = "birthday") val birthday: String? = null,
    @Json(name = "death") val death: String? = null,
    @Json(name = "birthplace") val birthplace: String? = null,
    @Json(name = "known_for_department") val knownForDepartment: String? = null,
    @Json(name = "images") val images: TraktPersonImagesDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonSearchResultDto(
    @Json(name = "type") val type: String? = null,
    @Json(name = "score") val score: Double? = null,
    @Json(name = "person") val person: TraktPersonDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonMovieCastDto(
    @Json(name = "character") val character: String? = null,
    @Json(name = "characters") val characters: List<String>? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonMovieCrewDto(
    @Json(name = "job") val job: String? = null,
    @Json(name = "jobs") val jobs: List<String>? = null,
    @Json(name = "movie") val movie: TraktMovieDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonMovieCreditsDto(
    @Json(name = "cast") val cast: List<TraktPersonMovieCastDto>? = null,
    @Json(name = "crew") val crew: Map<String, List<TraktPersonMovieCrewDto>>? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonShowCastDto(
    @Json(name = "character") val character: String? = null,
    @Json(name = "characters") val characters: List<String>? = null,
    @Json(name = "episode_count") val episodeCount: Int? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonShowCrewDto(
    @Json(name = "job") val job: String? = null,
    @Json(name = "jobs") val jobs: List<String>? = null,
    @Json(name = "show") val show: TraktShowDto? = null
)

@JsonClass(generateAdapter = true)
data class TraktPersonShowCreditsDto(
    @Json(name = "cast") val cast: List<TraktPersonShowCastDto>? = null,
    @Json(name = "crew") val crew: Map<String, List<TraktPersonShowCrewDto>>? = null
)
