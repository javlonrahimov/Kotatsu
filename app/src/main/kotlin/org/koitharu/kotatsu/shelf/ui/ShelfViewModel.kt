package org.koitharu.kotatsu.shelf.ui

import androidx.collection.ArraySet
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.plus
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.model.FavouriteCategory
import org.koitharu.kotatsu.core.os.NetworkState
import org.koitharu.kotatsu.core.prefs.AppSettings
import org.koitharu.kotatsu.core.prefs.ListMode
import org.koitharu.kotatsu.core.prefs.observeAsFlow
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.ui.util.ReversibleAction
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.download.ui.worker.DownloadWorker
import org.koitharu.kotatsu.favourites.domain.FavouritesRepository
import org.koitharu.kotatsu.history.data.HistoryRepository
import org.koitharu.kotatsu.list.domain.ListExtraProvider
import org.koitharu.kotatsu.list.ui.model.EmptyHint
import org.koitharu.kotatsu.list.ui.model.EmptyState
import org.koitharu.kotatsu.list.ui.model.ListModel
import org.koitharu.kotatsu.list.ui.model.LoadingState
import org.koitharu.kotatsu.list.ui.model.toErrorState
import org.koitharu.kotatsu.list.ui.model.toGridModel
import org.koitharu.kotatsu.list.ui.model.toUi
import org.koitharu.kotatsu.local.domain.DeleteLocalMangaUseCase
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.shelf.domain.ShelfContentObserveUseCase
import org.koitharu.kotatsu.shelf.domain.model.ShelfContent
import org.koitharu.kotatsu.shelf.domain.model.ShelfSection
import org.koitharu.kotatsu.shelf.ui.model.ShelfSectionModel
import org.koitharu.kotatsu.sync.domain.SyncController
import javax.inject.Inject

@HiltViewModel
class ShelfViewModel @Inject constructor(
	private val historyRepository: HistoryRepository,
	private val favouritesRepository: FavouritesRepository,
	private val settings: AppSettings,
	private val downloadScheduler: DownloadWorker.Scheduler,
	private val deleteLocalMangaUseCase: DeleteLocalMangaUseCase,
	private val listExtraProvider: ListExtraProvider,
	shelfContentObserveUseCase: ShelfContentObserveUseCase,
	syncController: SyncController,
	networkState: NetworkState,
) : BaseViewModel() {

	val onActionDone = MutableEventFlow<ReversibleAction>()
	val onDownloadStarted = MutableEventFlow<Unit>()

	val content: StateFlow<List<ListModel>> = combine(
		settings.observeAsFlow(AppSettings.KEY_SHELF_SECTIONS) { shelfSections },
		settings.observeAsFlow(AppSettings.KEY_TRACKER_ENABLED) { isTrackerEnabled },
		settings.observeAsFlow(AppSettings.KEY_SUGGESTIONS) { isSuggestionsEnabled },
		networkState,
		shelfContentObserveUseCase(),
	) { sections, isTrackerEnabled, isSuggestionsEnabled, isConnected, content ->
		mapList(content, isTrackerEnabled, isSuggestionsEnabled, sections, isConnected)
	}.catch { e ->
		emit(listOf(e.toErrorState(canRetry = false)))
	}.stateIn(viewModelScope + Dispatchers.Default, SharingStarted.Eagerly, listOf(LoadingState))

	init {
		launchJob(Dispatchers.Default) {
			syncController.requestFullSync()
		}
	}

	fun removeFromFavourites(category: FavouriteCategory, ids: Set<Long>) {
		if (ids.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val handle = favouritesRepository.removeFromCategory(category.id, ids)
			onActionDone.call(ReversibleAction(R.string.removed_from_favourites, handle))
		}
	}

	fun removeFromHistory(ids: Set<Long>) {
		if (ids.isEmpty()) {
			return
		}
		launchJob(Dispatchers.Default) {
			val handle = historyRepository.delete(ids)
			onActionDone.call(ReversibleAction(R.string.removed_from_history, handle))
		}
	}

	fun deleteLocal(ids: Set<Long>) {
		launchLoadingJob(Dispatchers.Default) {
			deleteLocalMangaUseCase(ids)
			onActionDone.call(ReversibleAction(R.string.removal_completed, null))
		}
	}

	fun clearHistory(minDate: Long) {
		launchJob(Dispatchers.Default) {
			val stringRes = if (minDate <= 0) {
				historyRepository.clear()
				R.string.history_cleared
			} else {
				historyRepository.deleteAfter(minDate)
				R.string.removed_from_history
			}
			onActionDone.call(ReversibleAction(stringRes, null))
		}
	}

	fun getManga(ids: Set<Long>): Set<Manga> {
		val snapshot = content.value
		val result = ArraySet<Manga>(ids.size)
		for (section in snapshot) {
			if (section !is ShelfSectionModel) {
				continue
			}
			for (item in section.items) {
				if (item.id in ids) {
					result.add(item.manga)
					if (result.size == ids.size) {
						return result
					}
				}
			}
		}
		return result
	}

	fun download(items: Set<Manga>) {
		launchJob(Dispatchers.Default) {
			downloadScheduler.schedule(items)
			onDownloadStarted.call(Unit)
		}
	}

	private suspend fun mapList(
		content: ShelfContent,
		isTrackerEnabled: Boolean,
		isSuggestionsEnabled: Boolean,
		sections: List<ShelfSection>,
		isNetworkAvailable: Boolean,
	): List<ListModel> {
		val result = ArrayList<ListModel>(content.favourites.keys.size + sections.size)
		if (isNetworkAvailable) {
			for (section in sections) {
				when (section) {
					ShelfSection.HISTORY -> mapHistory(result, content.history)
					ShelfSection.LOCAL -> mapLocal(result, content.local)
					ShelfSection.UPDATED -> if (isTrackerEnabled) {
						mapUpdated(result, content.updated)
					}

					ShelfSection.FAVORITES -> mapFavourites(result, content.favourites)
					ShelfSection.SUGGESTIONS -> if (isSuggestionsEnabled) {
						mapSuggestions(result, content.suggestions)
					}
				}
			}
		} else {
			result += EmptyHint(
				icon = R.drawable.ic_empty_common,
				textPrimary = R.string.network_unavailable,
				textSecondary = R.string.network_unavailable_hint,
				actionStringRes = R.string.manage,
			)
			for (section in sections) {
				when (section) {
					ShelfSection.HISTORY -> mapHistory(
						result,
						content.history.filter { it.source == MangaSource.LOCAL },
					)

					ShelfSection.LOCAL -> mapLocal(result, content.local)
					ShelfSection.UPDATED -> Unit
					ShelfSection.FAVORITES -> Unit
					ShelfSection.SUGGESTIONS -> Unit
				}
			}
		}
		if (result.isEmpty()) {
			result += EmptyState(
				icon = R.drawable.ic_empty_history,
				textPrimary = R.string.text_shelf_holder_primary,
				textSecondary = R.string.text_shelf_holder_secondary,
				actionStringRes = 0,
			)
		} else {
			val one = result.singleOrNull()
			if (one is EmptyHint) {
				result[0] = one.toState()
			}
		}
		return result
	}

	private suspend fun mapHistory(
		destination: MutableList<in ShelfSectionModel.History>,
		list: List<Manga>,
	) {
		if (list.isEmpty()) {
			return
		}
		destination += ShelfSectionModel.History(
			items = list.map { manga ->
				manga.toGridModel(listExtraProvider)
			},
			showAllButtonText = R.string.show_all,
		)
	}

	private suspend fun mapUpdated(
		destination: MutableList<in ShelfSectionModel.Updated>,
		updated: List<Manga>,
	) {
		if (updated.isEmpty()) {
			return
		}
		settings.isReadingIndicatorsEnabled
		destination += ShelfSectionModel.Updated(
			items = updated.map { manga ->
				manga.toGridModel(listExtraProvider)
			},
			showAllButtonText = R.string.show_all,
		)
	}

	private suspend fun mapLocal(
		destination: MutableList<in ShelfSectionModel.Local>,
		local: List<Manga>,
	) {
		if (local.isEmpty()) {
			return
		}
		destination += ShelfSectionModel.Local(
			items = local.toUi(ListMode.GRID, listExtraProvider),
			showAllButtonText = R.string.show_all,
		)
	}

	private suspend fun mapSuggestions(
		destination: MutableList<in ShelfSectionModel.Suggestions>,
		suggestions: List<Manga>,
	) {
		if (suggestions.isEmpty()) {
			return
		}
		destination += ShelfSectionModel.Suggestions(
			items = suggestions.toUi(ListMode.GRID, listExtraProvider),
			showAllButtonText = R.string.show_all,
		)
	}

	private suspend fun mapFavourites(
		destination: MutableList<in ShelfSectionModel.Favourites>,
		favourites: Map<FavouriteCategory, List<Manga>>,
	) {
		if (favourites.isEmpty()) {
			return
		}
		for ((category, list) in favourites) {
			if (list.isNotEmpty()) {
				destination += ShelfSectionModel.Favourites(
					items = list.toUi(ListMode.GRID, listExtraProvider),
					category = category,
					showAllButtonText = R.string.show_all,
				)
			}
		}
	}
}
