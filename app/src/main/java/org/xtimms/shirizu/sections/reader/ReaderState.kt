package org.xtimms.shirizu.sections.reader

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import org.koitharu.kotatsu.parsers.model.Manga
import org.xtimms.shirizu.core.model.MangaHistory

@Parcelize
data class ReaderState(
    val chapterId: Long,
    val page: Int,
    val scroll: Int,
) : Parcelable {

    constructor(history: MangaHistory) : this(
        chapterId = history.chapterId,
        page = history.page,
        scroll = history.scroll,
    )

    constructor(manga: Manga, branch: String?) : this(
        chapterId = manga.chapters?.firstOrNull {
            it.branch == branch
        }?.id ?: error("Cannot find first chapter"),
        page = 0,
        scroll = 0,
    )
}