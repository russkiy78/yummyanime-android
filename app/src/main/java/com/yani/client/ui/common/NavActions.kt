package com.yani.client.ui.common

import androidx.compose.runtime.staticCompositionLocalOf

class NavActions(
    val openAnime: (idOrAlias: String) -> Unit,
    val openPlayer: (animeId: Int, videoId: Long?) -> Unit,
    val openCatalog: (genreId: Int?, title: String?) -> Unit,
    val openCollection: (id: Int) -> Unit,
    val openComments: (animeId: Int, title: String) -> Unit,
    val openRoute: (route: String) -> Unit,
    val openUrl: (url: String) -> Unit,
    val back: () -> Unit,
)

val LocalNav = staticCompositionLocalOf<NavActions> { error("NavActions not provided") }
