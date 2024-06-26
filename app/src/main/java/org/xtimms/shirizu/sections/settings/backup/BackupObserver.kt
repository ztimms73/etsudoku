package org.xtimms.shirizu.sections.settings.backup

import android.app.backup.BackupManager
import android.content.Context
import androidx.room.InvalidationTracker
import dagger.hilt.android.qualifiers.ApplicationContext
import org.xtimms.shirizu.core.database.TABLE_FAVOURITES
import org.xtimms.shirizu.core.database.TABLE_FAVOURITE_CATEGORIES
import org.xtimms.shirizu.core.database.TABLE_HISTORY
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackupObserver @Inject constructor(
    @ApplicationContext context: Context,
) : InvalidationTracker.Observer(arrayOf(TABLE_HISTORY, TABLE_FAVOURITES, TABLE_FAVOURITE_CATEGORIES)) {

    private val backupManager = BackupManager(context)

    override fun onInvalidated(tables: Set<String>) {
        backupManager.dataChanged()
    }
}