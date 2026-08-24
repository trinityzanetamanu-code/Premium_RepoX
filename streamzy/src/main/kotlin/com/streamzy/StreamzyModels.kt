package com.streamzy

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class StreamzySearchResponse(
    val results: List<StreamzySearchItem> = emptyList()
)

@Serializable
data class StreamzySearchItem(
    val id: Int,
    @SerialName("media_type")
    val mediaType: String,
    val title: String,
    @SerialName("release_date")
    val releaseDate: String? = null,
    @SerialName("poster_path")
    val posterPath: String? = null,
    @SerialName("vote_average")
    val voteAverage: Double? = null
)
