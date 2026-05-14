package com.grmemby.app.ui.screens.dashboard.search

import com.grmemby.data.model.BaseItemDto

internal const val SEARCH_RESULT_FIELDS =
    "ChildCount,RecursiveItemCount,EpisodeCount,SeriesName,SeriesId,Genres,CommunityRating," +
        "ProductionYear,Overview,ImageTags,PrimaryImageAspectRatio,BackdropImageTags," +
        "ParentPrimaryImageItemId,ParentPrimaryImageTag,SeriesPrimaryImageTag,Tags,OfficialRating"

internal const val SEARCH_RESULT_ITEM_TYPES = "Movie,Series,Video"

internal val DEFAULT_RECOMMENDED_SEARCH_TAGS: List<String> = emptyList()

internal const val RECOMMENDED_SEARCH_TAG_FIELDS =
    "ProductionYear,PrimaryImageAspectRatio,CommunityRating,Overview,ImageTags," +
        "BackdropImageTags,ParentPrimaryImageItemId,ParentPrimaryImageTag,SeriesPrimaryImageTag"

internal data class SearchResultBuckets(
    val movies: List<BaseItemDto>,
    val shows: List<BaseItemDto>,
    val episodes: List<BaseItemDto>
)

internal fun categorizeSearchResults(items: List<BaseItemDto>): SearchResultBuckets {
    val distinctItems = items
        .filter { !it.name.isNullOrBlank() }
        .distinctBy { it.id ?: "${it.type}:${it.name}:${it.productionYear}" }

    return SearchResultBuckets(
        movies = distinctItems.filter { item ->
            item.type.equals("Movie", ignoreCase = true) ||
                item.type.equals("Video", ignoreCase = true)
        }.take(30),
        shows = distinctItems.filter { item ->
            item.type.equals("Series", ignoreCase = true)
        }.take(30),
        episodes = distinctItems.filter { item ->
            item.type.equals("Episode", ignoreCase = true)
        }.take(30)
    )
}

internal fun buildRecommendedSearchTags(
    recommendedItems: List<BaseItemDto> = emptyList(),
    limit: Int = 28
): List<String> {
    return recommendedItems.asSequence()
        .mapNotNull { item -> item.name?.trim()?.takeIf { it.isNotBlank() } }
        .distinctBy { tag -> tag.lowercase() }
        .take(limit)
        .toList()
}

internal fun <T> posterGridRows(
    items: List<T>,
    columns: Int = 3
): List<List<T>> {
    if (columns <= 0) return emptyList()
    return items.chunked(columns)
}

internal fun BaseItemDto.posterImageItemId(): String? {
    return when {
        type.equals("Episode", ignoreCase = true) && !parentPrimaryImageItemId.isNullOrBlank() -> parentPrimaryImageItemId
        else -> id
    }
}

internal fun BaseItemDto.posterImageTag(): String? {
    return imageTags?.get("Primary")
        ?: parentPrimaryImageTag
        ?: seriesPrimaryImageTag
}
