package com.streamzy

import com.fasterxml.jackson.annotation.JsonProperty

data class StreamzySearchResponse(
    @param:JsonProperty("results")
    val results: List<StreamzySearchItem> = emptyList()
)

data class StreamzySearchItem(
    @param:JsonProperty("id")
    val id: Int = 0,

    @param:JsonProperty("media_type")
    val mediaType: String = "",

    @param:JsonProperty("title")
    val title: String = "",

    @param:JsonProperty("release_date")
    val releaseDate: String? = null,

    @param:JsonProperty("poster_path")
    val posterPath: String? = null,

    @param:JsonProperty("vote_average")
    val voteAverage: Double? = null
)

data class StreamzyEpisodeData(
    val tvId: Int,
    val season: Int,
    val episode: Int
)
