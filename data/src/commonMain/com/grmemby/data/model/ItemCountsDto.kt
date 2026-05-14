package com.grmemby.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class ItemCountsDto(
    @SerialName("MovieCount")
    val movieCount: Int? = null,

    @SerialName("SeriesCount")
    val seriesCount: Int? = null,

    @SerialName("EpisodeCount")
    val episodeCount: Int? = null,

    @SerialName("TrailerCount")
    val trailerCount: Int? = null,

    @SerialName("ProgramCount")
    val programCount: Int? = null,

    @SerialName("SongCount")
    val songCount: Int? = null,

    @SerialName("AlbumCount")
    val albumCount: Int? = null,

    @SerialName("ArtistCount")
    val artistCount: Int? = null,

    @SerialName("MusicVideoCount")
    val musicVideoCount: Int? = null,

    @SerialName("BoxSetCount")
    val boxSetCount: Int? = null,

    @SerialName("BookCount")
    val bookCount: Int? = null,

    @SerialName("ItemCount")
    val itemCount: Int? = null
)
