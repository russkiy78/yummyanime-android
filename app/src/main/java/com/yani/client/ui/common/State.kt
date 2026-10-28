package com.yani.client.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yani.client.AppContainer
import com.yani.client.YummyApplication
import kotlin.coroutines.cancellation.CancellationException

sealed interface Load<out T> {
    data object Loading : Load<Nothing>
    data class Error(val error: Throwable) : Load<Nothing>
    data class Ok<T>(val data: T) : Load<T>
}

val <T> Load<T>.dataOrNull: T? get() = (this as? Load.Ok<T>)?.data

suspend fun <T> load(block: suspend () -> T): Load<T> = try {
    Load.Ok(block())
} catch (e: CancellationException) {
    throw e
} catch (e: Exception) {
    Load.Error(e)
}

@Composable
fun appContainer(): AppContainer = (LocalContext.current.applicationContext as YummyApplication).container

/** ViewModel factory with access to the app container. */
@Composable
inline fun <reified VM : ViewModel> appViewModel(
    key: String? = null,
    crossinline create: (AppContainer) -> VM,
): VM {
    val container = (LocalContext.current.applicationContext as YummyApplication).container
    return viewModel(key = key) { create(container) }
}
