package com.yani.client.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yani.client.R
import com.yani.client.data.Session
import com.yani.client.player.PlayerActivity
import com.yani.client.ui.anime.AnimeScreen
import com.yani.client.ui.anime.CommentsScreen
import com.yani.client.ui.catalog.CatalogScreen
import com.yani.client.ui.collections.CollectionScreen
import com.yani.client.ui.collections.CollectionsScreen
import com.yani.client.ui.common.LocalIsTv
import com.yani.client.ui.common.LocalNav
import com.yani.client.ui.common.NavActions
import com.yani.client.ui.common.UserAvatar
import com.yani.client.ui.common.appContainer
import com.yani.client.ui.common.focusBorder
import com.yani.client.ui.home.HomeScreen
import com.yani.client.ui.lists.HistoryScreen
import com.yani.client.ui.lists.ListsScreen
import com.yani.client.ui.profile.LoginScreen
import com.yani.client.ui.profile.NotificationsScreen
import com.yani.client.ui.profile.ProfileScreen
import com.yani.client.ui.profile.SettingsScreen
import com.yani.client.ui.schedule.ScheduleScreen

object Routes {
    const val HOME = "home"
    const val CATALOG = "catalog?genre={genre}&title={title}"
    const val SCHEDULE = "schedule"
    const val LISTS = "lists"
    const val PROFILE = "profile"
    const val ANIME = "anime/{id}"
    const val COMMENTS = "comments/{id}?title={title}"
    const val COLLECTIONS = "collections"
    const val COLLECTION = "collection/{id}"
    const val HISTORY = "history"
    const val NOTIFICATIONS = "notifications"
    const val SETTINGS = "settings"
    const val LOGIN = "login"

    fun anime(id: String) = "anime/${Uri.encode(id)}"
    fun catalog(genre: Int?, title: String?) = "catalog?genre=${genre ?: ""}&title=${Uri.encode(title.orEmpty())}"
    fun comments(animeId: Int, title: String) = "comments/$animeId?title=${Uri.encode(title)}"
    fun collection(id: Int) = "collection/$id"
}

private data class TopLevel(val route: String, val navigateTo: String, val label: Int, val icon: ImageVector)

private val topLevel = listOf(
    TopLevel(Routes.HOME, Routes.HOME, R.string.nav_home, Icons.Filled.Home),
    TopLevel(Routes.CATALOG, "catalog", R.string.nav_catalog, Icons.Filled.Search),
    TopLevel(Routes.SCHEDULE, Routes.SCHEDULE, R.string.nav_schedule, Icons.Filled.CalendarMonth),
    TopLevel(Routes.LISTS, Routes.LISTS, R.string.nav_lists, Icons.Filled.Bookmarks),
    TopLevel(Routes.PROFILE, Routes.PROFILE, R.string.nav_profile, Icons.Filled.Person),
)

/** The profile item shows who is logged in, which matters when several accounts are saved. */
@Composable
private fun TopLevelIcon(item: TopLevel, session: Session?) {
    if (item.route == Routes.PROFILE && session != null) {
        UserAvatar(session.avatar, Modifier.size(24.dp), fallback = item.icon)
    } else {
        Icon(item.icon, null)
    }
}

private fun NavController.navigateTopLevel(route: String) = navigate(route) {
    popUpTo(graph.findStartDestination().id) { saveState = true }
    launchSingleTop = true
    restoreState = true
}

private fun navActions(nav: NavHostController, context: Context) = NavActions(
    openAnime = { nav.navigate(Routes.anime(it)) },
    openPlayer = { animeId, videoId -> context.startActivity(PlayerActivity.intent(context, animeId, videoId)) },
    openCatalog = { genre, title -> nav.navigate(Routes.catalog(genre, title)) },
    openCollection = { nav.navigate(Routes.collection(it)) },
    openComments = { id, title -> nav.navigate(Routes.comments(id, title)) },
    openRoute = { nav.navigate(it) { launchSingleTop = true } },
    openUrl = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } },
    back = { nav.popBackStack() },
)

@Composable
fun AppRoot(deepLinkAlias: String?, onDeepLinkConsumed: () -> Unit) {
    val nav = rememberNavController()
    val context = LocalContext.current
    val actions = remember(nav) { navActions(nav, context) }
    val entry by nav.currentBackStackEntryAsState()
    val currentRoute = entry?.destination?.route
    val session by appContainer().prefs.session.collectAsStateWithLifecycle()
    val useRail = LocalIsTv.current || LocalConfiguration.current.screenWidthDp >= 840

    LaunchedEffect(deepLinkAlias) {
        if (deepLinkAlias != null) {
            nav.navigate(Routes.anime(deepLinkAlias))
            onDeepLinkConsumed()
        }
    }

    CompositionLocalProvider(LocalNav provides actions) {
        if (useRail) {
            Row(
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    Spacer(Modifier.height(12.dp))
                    topLevel.forEach { item ->
                        val source = remember { MutableInteractionSource() }
                        NavigationRailItem(
                            selected = currentRoute == item.route,
                            onClick = { nav.navigateTopLevel(item.navigateTo) },
                            icon = { TopLevelIcon(item, session) },
                            label = { Text(stringResource(item.label)) },
                            interactionSource = source,
                            modifier = Modifier
                                .padding(vertical = 2.dp)
                                .focusBorder(source, RoundedCornerShape(16.dp), scale = 1.04f),
                        )
                    }
                }
                Box(Modifier.weight(1f)) { AppNavHost(nav) }
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    if (topLevel.any { it.route == currentRoute }) {
                        NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                            topLevel.forEach { item ->
                                NavigationBarItem(
                                    selected = currentRoute == item.route,
                                    onClick = { nav.navigateTopLevel(item.navigateTo) },
                                    icon = { TopLevelIcon(item, session) },
                                    label = { Text(stringResource(item.label), maxLines = 1) },
                                )
                            }
                        }
                    }
                },
            ) { padding ->
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .consumeWindowInsets(padding),
                ) { AppNavHost(nav) }
            }
        }
    }
}

@Composable
private fun AppNavHost(nav: NavHostController) {
    NavHost(
        navController = nav,
        startDestination = Routes.HOME,
        enterTransition = { fadeIn(tween(180)) },
        exitTransition = { fadeOut(tween(180)) },
    ) {
        composable(Routes.HOME) { HomeScreen() }
        composable(
            Routes.CATALOG,
            arguments = listOf(
                navArgument("genre") { type = NavType.StringType; nullable = true; defaultValue = null },
                navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null },
            ),
        ) { e ->
            CatalogScreen(
                presetGenre = e.arguments?.getString("genre")?.toIntOrNull(),
                presetTitle = e.arguments?.getString("title")?.takeIf { it.isNotBlank() },
            )
        }
        composable(Routes.SCHEDULE) { ScheduleScreen() }
        composable(Routes.LISTS) { ListsScreen() }
        composable(Routes.PROFILE) { ProfileScreen() }
        composable(Routes.ANIME) { e -> AnimeScreen(e.arguments?.getString("id").orEmpty()) }
        composable(
            Routes.COMMENTS,
            arguments = listOf(navArgument("title") { type = NavType.StringType; nullable = true; defaultValue = null }),
        ) { e ->
            CommentsScreen(
                animeId = e.arguments?.getString("id")?.toIntOrNull() ?: 0,
                title = e.arguments?.getString("title").orEmpty(),
            )
        }
        composable(Routes.COLLECTIONS) { CollectionsScreen() }
        composable(Routes.COLLECTION) { e -> CollectionScreen(e.arguments?.getString("id")?.toIntOrNull() ?: 0) }
        composable(Routes.HISTORY) { HistoryScreen() }
        composable(Routes.NOTIFICATIONS) { NotificationsScreen() }
        composable(Routes.SETTINGS) { SettingsScreen() }
        composable(Routes.LOGIN) { LoginScreen() }
    }
}
