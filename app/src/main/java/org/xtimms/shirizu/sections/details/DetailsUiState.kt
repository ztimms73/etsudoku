package org.xtimms.shirizu.sections.details

import org.xtimms.shirizu.core.base.state.UiState
import org.xtimms.shirizu.sections.details.data.MangaDetails

data class DetailsUiState(
    val details: MangaDetails? = null,
    override val isLoading: Boolean = false,
    override val message: String? = null,
) : UiState() {
    override fun setLoading(value: Boolean) = copy(isLoading = value)
    override fun setMessage(value: String?) = copy(message = value)
}