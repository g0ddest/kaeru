package app.kaeru.ui.common.home

import app.kaeru.domain.model.HomeFeed

data class HomeUiState(
    val feed: HomeFeed = HomeFeed.EMPTY,
    val isLoading: Boolean = true,
    val isRefreshing: Boolean = false,
    val errorMessage: String? = null,
)
