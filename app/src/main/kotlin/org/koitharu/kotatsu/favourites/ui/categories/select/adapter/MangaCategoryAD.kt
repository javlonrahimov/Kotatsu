package org.koitharu.kotatsu.favourites.ui.categories.select.adapter

import com.hannesdorfmann.adapterdelegates4.dsl.adapterDelegateViewBinding
import org.koitharu.kotatsu.core.ui.list.OnListItemClickListener
import org.koitharu.kotatsu.databinding.ItemCheckableNewBinding
import org.koitharu.kotatsu.favourites.ui.categories.select.model.MangaCategoryItem
import org.koitharu.kotatsu.list.ui.model.ListModel

fun mangaCategoryAD(
	clickListener: OnListItemClickListener<MangaCategoryItem>,
) = adapterDelegateViewBinding<MangaCategoryItem, ListModel, ItemCheckableNewBinding>(
	{ inflater, parent -> ItemCheckableNewBinding.inflate(inflater, parent, false) },
) {

	itemView.setOnClickListener {
		clickListener.onItemClick(item, itemView)
	}

	bind {
		with(binding.root) {
			text = item.name
			isChecked = item.isChecked
		}
	}
}
