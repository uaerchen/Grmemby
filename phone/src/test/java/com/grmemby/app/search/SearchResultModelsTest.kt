package com.grmemby.app.search

import com.grmemby.app.ui.screens.dashboard.search.SEARCH_RESULT_FIELDS
import com.grmemby.app.ui.screens.dashboard.search.SEARCH_RESULT_ITEM_TYPES
import com.grmemby.app.ui.screens.dashboard.search.buildRecommendedSearchTags
import com.grmemby.app.ui.screens.dashboard.search.categorizeSearchResults
import com.grmemby.app.ui.screens.dashboard.search.posterGridRows
import com.grmemby.app.ui.screens.dashboard.search.posterImageItemId
import com.grmemby.app.ui.screens.dashboard.search.posterImageTag
import com.grmemby.data.model.BaseItemDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchResultModelsTest {
    @Test
    fun serverSearchIncludesMovieSeriesVideoAndImageTags() {
        assertEquals("Movie,Series,Video", SEARCH_RESULT_ITEM_TYPES)
        assertTrue(SEARCH_RESULT_FIELDS.contains("ImageTags"))
        assertTrue(SEARCH_RESULT_FIELDS.contains("ParentPrimaryImageItemId"))
        assertTrue(SEARCH_RESULT_FIELDS.contains("ParentPrimaryImageTag"))
        assertTrue(SEARCH_RESULT_FIELDS.contains("SeriesPrimaryImageTag"))
    }

    @Test
    fun categorizesMixedEmbySearchResultsInsteadOfDroppingMovies() {
        val buckets = categorizeSearchResults(
            listOf(
                BaseItemDto(id = "m1", name = "Movie", type = "Movie"),
                BaseItemDto(id = "v1", name = "Video", type = "Video"),
                BaseItemDto(id = "s1", name = "Series", type = "Series"),
                BaseItemDto(id = "e1", name = "Episode", type = "Episode"),
                BaseItemDto(id = "blank", name = "", type = "Movie")
            )
        )

        assertEquals(listOf("m1", "v1"), buckets.movies.map { it.id })
        assertEquals(listOf("s1"), buckets.shows.map { it.id })
        assertEquals(listOf("e1"), buckets.episodes.map { it.id })
    }

    @Test
    fun posterHelpersUsePrimaryTagAndEpisodeParentPoster() {
        val movie = BaseItemDto(
            id = "m1",
            type = "Movie",
            imageTags = mapOf("Primary" to "movie-tag")
        )
        val episode = BaseItemDto(
            id = "e1",
            type = "Episode",
            parentPrimaryImageItemId = "series-poster-id",
            parentPrimaryImageTag = "series-poster-tag",
            seriesPrimaryImageTag = "fallback-series-tag"
        )

        assertEquals("m1", movie.posterImageItemId())
        assertEquals("movie-tag", movie.posterImageTag())
        assertEquals("series-poster-id", episode.posterImageItemId())
        assertEquals("series-poster-tag", episode.posterImageTag())
    }

    @Test
    fun recommendedSearchTagsUseServerItemNamesWithoutFakeFallbacks() {
        val tags = buildRecommendedSearchTags(
            recommendedItems = listOf(
                BaseItemDto(id = "1", name = "皇家师姐3：雌雄大盗", genres = listOf("动作")),
                BaseItemDto(id = "2", name = " 了不起的挑战 ", tags = listOf("热门")),
                BaseItemDto(id = "3", name = ""),
                BaseItemDto(id = "4", name = "皇家师姐3：雌雄大盗")
            ),
            limit = 6
        )

        assertEquals(listOf("皇家师姐3：雌雄大盗", "了不起的挑战"), tags)
    }

    @Test
    fun recommendedSearchTagsStayEmptyWhenServerRecommendationsAreEmpty() {
        val tags = buildRecommendedSearchTags(
            limit = 2
        )

        assertEquals(emptyList<String>(), tags)
    }

    @Test
    fun posterGridRowsChunkResultsForVerticalScanning() {
        val rows = posterGridRows((1..7).toList(), columns = 3)

        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6), listOf(7)), rows)
    }
}
