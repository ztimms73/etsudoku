package org.xtimms.shirizu.work.suggestions

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.annotation.FloatRange
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.PendingIntentCompat
import androidx.core.text.HtmlCompat
import androidx.core.text.bold
import androidx.core.text.buildSpannedString
import androidx.core.text.parseAsHtml
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.await
import androidx.work.workDataOf
import coil.ImageLoader
import coil.request.ImageRequest
import dagger.Reusable
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaSource
import org.koitharu.kotatsu.parsers.model.MangaTag
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.util.almostEquals
import org.koitharu.kotatsu.parsers.util.runCatchingCancellable
import org.xtimms.shirizu.R
import org.xtimms.shirizu.core.model.MangaSuggestion
import org.xtimms.shirizu.core.model.TagsBlacklist
import org.xtimms.shirizu.core.model.distinctById
import org.xtimms.shirizu.core.parser.MangaRepository
import org.xtimms.shirizu.core.prefs.AppSettings
import org.xtimms.shirizu.data.repository.FavouritesRepository
import org.xtimms.shirizu.data.repository.HistoryRepository
import org.xtimms.shirizu.data.repository.MangaSourcesRepository
import org.xtimms.shirizu.data.repository.SuggestionRepository
import org.xtimms.shirizu.utils.lang.asArrayList
import org.xtimms.shirizu.utils.lang.awaitUniqueWorkInfoByName
import org.xtimms.shirizu.utils.lang.flatten
import org.xtimms.shirizu.utils.lang.sanitize
import org.xtimms.shirizu.utils.lang.takeMostFrequent
import org.xtimms.shirizu.utils.lang.toBitmapOrNull
import org.xtimms.shirizu.utils.system.checkNotificationPermission
import org.xtimms.shirizu.utils.system.trySetForeground
import org.xtimms.shirizu.work.PeriodicWorkScheduler
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlin.math.pow
import kotlin.random.Random

@HiltWorker
class SuggestionsWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val coil: ImageLoader,
    private val suggestionRepository: SuggestionRepository,
    private val historyRepository: HistoryRepository,
    private val favouritesRepository: FavouritesRepository,
    private val workManager: WorkManager,
    private val mangaRepositoryFactory: MangaRepository.Factory,
    private val sourcesRepository: MangaSourcesRepository,
) : CoroutineWorker(appContext, params) {

    private val notificationManager by lazy { NotificationManagerCompat.from(appContext) }

    override suspend fun doWork(): Result {
        trySetForeground()
        val count = doWorkImpl()
        val outputData = workDataOf(DATA_COUNT to count)
        return Result.success(outputData)
    }

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val title = applicationContext.getString(R.string.suggestions_updating)
        val channel = NotificationChannelCompat.Builder(WORKER_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
            .setName(title)
            .setShowBadge(true)
            .setVibrationEnabled(false)
            .setSound(null, null)
            .setLightsEnabled(true)
            .build()
        notificationManager.createNotificationChannel(channel)

        val notification = NotificationCompat.Builder(applicationContext, WORKER_CHANNEL_ID)
            .setContentTitle(title)
            .addAction(
                com.google.android.material.R.drawable.material_ic_clear_black_24dp,
                applicationContext.getString(android.R.string.cancel),
                workManager.createCancelPendingIntent(id),
            )
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setDefaults(0)
            .setOngoing(false)
            .setSilent(true)
            .setProgress(0, 0, true)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setForegroundServiceBehavior(
                if (TAG_ONESHOT in tags) {
                    NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE
                } else {
                    NotificationCompat.FOREGROUND_SERVICE_DEFERRED
                },
            )
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(WORKER_NOTIFICATION_ID, notification)
        }
    }

    private suspend fun doWorkImpl(): Int {
        val seed = (
                historyRepository.getList(0, 20) +
                        favouritesRepository.getLastManga(20)
                ).distinctById()
        val sources = sourcesRepository.getEnabledSources()
        if (seed.isEmpty() || sources.isEmpty()) {
            return 0
        }
        val tagsBlacklist = TagsBlacklist(setOf(""), TAG_EQ_THRESHOLD)
        val tags = seed.flatMap { it.tags.map { x -> x.title } }.takeMostFrequent(10)

        val semaphore = Semaphore(MAX_PARALLELISM)
        val producer = channelFlow {
            for (it in sources.shuffled()) {
                launch {
                    semaphore.withPermit {
                        send(getList(it, tags, tagsBlacklist))
                    }
                }
            }
        }
        val suggestions = producer
            .flatten()
            .take(MAX_RAW_RESULTS)
            .map { manga ->
                MangaSuggestion(
                    manga = manga,
                    relevance = computeRelevance(manga.tags, tags),
                )
            }.toList()
            .sortedBy { it.relevance }
            .take(MAX_RESULTS)
        suggestionRepository.replace(suggestions)
        if (AppSettings.isSuggestionsNotificationsEnabled()
            && applicationContext.checkNotificationPermission()) {
            for (i in 0..3) {
                try {
                    val manga = suggestions[Random.nextInt(0, suggestions.size / 3)]
                    val details = mangaRepositoryFactory.create(manga.manga.source)
                        .getDetails(manga.manga)
                    if (details.chapters.isNullOrEmpty()) {
                        continue
                    }
                    if (details.rating > 0 && details.rating < RATING_MIN) {
                        continue
                    }
                    if (details.isNsfw && AppSettings.isSuggestionsExcludeNsfw()) {
                        continue
                    }
                    if (details in tagsBlacklist) {
                        continue
                    }
                    showNotification(details)
                    break
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        return suggestions.size
    }

    private suspend fun getList(
        source: MangaSource,
        tags: List<String>,
        blacklist: TagsBlacklist,
    ): List<Manga> = runCatchingCancellable {
        val repository = mangaRepositoryFactory.create(source)
        val availableOrders = repository.sortOrders
        val order = preferredSortOrders.first { it in availableOrders }
        val availableTags = repository.getTags()
        val tag = tags.firstNotNullOfOrNull { title ->
            availableTags.find { x -> x !in blacklist && x.title.almostEquals(title, TAG_EQ_THRESHOLD) }
        }
        val list = repository.getList(
            offset = 0,
            filter = MangaListFilter.Advanced.Builder(order)
                .tags(setOfNotNull(tag))
                .build(),
        ).asArrayList()
        if (AppSettings.isSuggestionsExcludeNsfw()) {
            list.removeAll { it.isNsfw }
        }
        if (blacklist.isNotEmpty()) {
            list.removeAll { manga -> manga in blacklist }
        }
        list.shuffle()
        list.take(MAX_SOURCE_RESULTS)
    }.onFailure { e ->
        e.printStackTrace()
    }.getOrDefault(emptyList())

    @SuppressLint("MissingPermission")
    private suspend fun showNotification(manga: Manga) {
        val channel = NotificationChannelCompat.Builder(MANGA_CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_DEFAULT)
            .setName(applicationContext.getString(R.string.suggestions))
            .setDescription(applicationContext.getString(R.string.suggestions_summary))
            .setLightsEnabled(true)
            .setShowBadge(true)
            .build()
        notificationManager.createNotificationChannel(channel)

        val id = manga.url.hashCode()
        val title = applicationContext.getString(R.string.suggestion_manga, manga.title)
        val builder = NotificationCompat.Builder(applicationContext, MANGA_CHANNEL_ID)
        val tagsText = manga.tags.joinToString(", ") { it.title }
        with(builder) {
            setContentText(tagsText)
            setContentTitle(title)
            setLargeIcon(
                coil.execute(
                    ImageRequest.Builder(applicationContext)
                        .data(manga.coverUrl)
                        .tag(manga.source)
                        .build(),
                ).toBitmapOrNull(),
            )
            setSmallIcon(R.drawable.ic_stat_suggestion)
            val description = manga.description?.parseAsHtml(HtmlCompat.FROM_HTML_MODE_COMPACT)?.sanitize()
            if (!description.isNullOrBlank()) {
                val style = NotificationCompat.BigTextStyle()
                style.bigText(
                    buildSpannedString {
                        append(tagsText)
                        val chaptersCount = manga.chapters?.size ?: 0
                        appendLine()
                        bold {
                            append(
                                applicationContext.resources.getQuantityString(
                                    R.plurals.chapters,
                                    chaptersCount,
                                    chaptersCount,
                                ),
                            )
                        }
                        appendLine()
                        append(description)
                    },
                )
                style.setBigContentTitle(title)
                setStyle(style)
            }
            setAutoCancel(true)
            setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            setVisibility(if (manga.isNsfw) NotificationCompat.VISIBILITY_SECRET else NotificationCompat.VISIBILITY_PUBLIC)
            setShortcutId(manga.id.toString())
            priority = NotificationCompat.PRIORITY_DEFAULT
        }
        notificationManager.notify(TAG, id, builder.build())
    }

    @FloatRange(from = 0.0, to = 1.0)
    private fun computeRelevance(mangaTags: Set<MangaTag>, allTags: List<String>): Float {
        val maxWeight = (allTags.size + allTags.size + 1 - mangaTags.size) * mangaTags.size / 2.0
        val weight = mangaTags.sumOf { tag ->
            val index = allTags.inexactIndexOf(tag.title, TAG_EQ_THRESHOLD)
            if (index < 0) 0 else allTags.size - index
        }
        return (weight / maxWeight).pow(2.0).toFloat()
    }

    private fun Iterable<String>.inexactIndexOf(element: String, threshold: Float): Int {
        forEachIndexed { i, t ->
            if (t.almostEquals(element, threshold)) {
                return i
            }
        }
        return -1
    }

    @Reusable
    class Scheduler @Inject constructor(
        private val workManager: WorkManager,
    ) : PeriodicWorkScheduler {

        override suspend fun schedule() {
            val request = PeriodicWorkRequestBuilder<SuggestionsWorker>(6, TimeUnit.HOURS)
                .setConstraints(createConstraints())
                .addTag(TAG)
                .setBackoffCriteria(BackoffPolicy.LINEAR, 1, TimeUnit.HOURS)
                .build()
            workManager
                .enqueueUniquePeriodicWork(TAG, ExistingPeriodicWorkPolicy.UPDATE, request)
                .await()
        }

        override suspend fun unschedule() {
            workManager
                .cancelUniqueWork(TAG)
                .await()
        }

        override suspend fun isScheduled(): Boolean {
            return workManager
                .awaitUniqueWorkInfoByName(TAG)
                .any { !it.state.isFinished }
        }

        fun startNow() {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<SuggestionsWorker>()
                .setConstraints(constraints)
                .addTag(TAG_ONESHOT)
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            workManager.enqueue(request)
        }

        private fun createConstraints() = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .setRequiresBatteryNotLow(true)
            .build()
    }

    private companion object {

        const val TAG = "suggestions"
        const val TAG_ONESHOT = "suggestions_oneshot"
        const val DATA_COUNT = "count"
        const val WORKER_CHANNEL_ID = "suggestion_worker"
        const val MANGA_CHANNEL_ID = "suggestions"
        const val WORKER_NOTIFICATION_ID = 36
        const val MAX_RESULTS = 80
        const val MAX_PARALLELISM = 3
        const val MAX_SOURCE_RESULTS = 14
        const val MAX_RAW_RESULTS = 200
        const val TAG_EQ_THRESHOLD = 0.4f
        const val RATING_MIN = 0.5f

        val preferredSortOrders = listOf(
            SortOrder.UPDATED,
            SortOrder.NEWEST,
            SortOrder.POPULARITY,
            SortOrder.RATING,
        )
    }
}