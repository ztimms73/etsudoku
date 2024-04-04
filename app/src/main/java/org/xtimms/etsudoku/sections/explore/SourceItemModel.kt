package org.xtimms.etsudoku.sections.explore

import android.net.Uri
import org.xtimms.etsudoku.core.model.ListModel

data class SourceItemModel(
    val id: Int,
    val name: String,
    val title: String,
    val favicon: Uri
) : ListModel {

    override fun areItemsTheSame(other: ListModel): Boolean {
        return other is SourceItemModel && other.id == id
    }
}