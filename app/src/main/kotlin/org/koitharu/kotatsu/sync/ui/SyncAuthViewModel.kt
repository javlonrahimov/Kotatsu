package org.koitharu.kotatsu.sync.ui

import android.accounts.AccountManager
import android.content.Context
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.core.ui.BaseViewModel
import org.koitharu.kotatsu.core.util.ext.MutableEventFlow
import org.koitharu.kotatsu.core.util.ext.call
import org.koitharu.kotatsu.core.util.ext.ifNullOrEmpty
import org.koitharu.kotatsu.sync.data.SyncAuthApi
import org.koitharu.kotatsu.sync.domain.SyncAuthResult
import javax.inject.Inject

@HiltViewModel
class SyncAuthViewModel @Inject constructor(
	@ApplicationContext context: Context,
	private val api: SyncAuthApi,
) : BaseViewModel() {

	val onAccountAlreadyExists = MutableEventFlow<Unit>()
	val onTokenObtained = MutableEventFlow<SyncAuthResult>()
	val host = MutableStateFlow("")

	private val defaultHost = context.getString(R.string.sync_host_default)

	init {
		launchJob(Dispatchers.Default) {
			val am = AccountManager.get(context)
			val accounts = am.getAccountsByType(context.getString(R.string.account_type_sync))
			if (accounts.isNotEmpty()) {
				onAccountAlreadyExists.call(Unit)
			}
		}
	}

	fun obtainToken(email: String, password: String) {
		val hostValue = host.value.ifNullOrEmpty { defaultHost }
		launchLoadingJob(Dispatchers.Default) {
			val token = api.authenticate(hostValue, email, password)
			val result = SyncAuthResult(host.value, email, password, token)
			onTokenObtained.call(result)
		}
	}
}
