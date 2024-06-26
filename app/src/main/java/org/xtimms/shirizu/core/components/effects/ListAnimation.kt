package org.xtimms.shirizu.core.components.effects

import android.annotation.SuppressLint
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListUpdateCallback
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.xtimms.shirizu.core.model.ListModel
import java.time.Instant

enum class RowEntityType { Header, Item }

data class RowEntity(
    val type: RowEntityType,
    val key: String,
    var contentHash: String? = null,
    val day: Instant,
    var itemModel: ListModel?,
)

@SuppressLint("ComposableNaming", "UnusedTransitionTargetStateParameter")
/**
 * @param state Use [updateAnimatedItemsState].
 */
inline fun LazyListScope.animatedItemsIndexed(
    state: List<AnimatedItem<RowEntity>>,
    enterTransition: EnterTransition = expandVertically() + fadeIn(),
    exitTransition: ExitTransition = shrinkVertically() + fadeOut(),
    noinline key: ((item: RowEntity) -> Any)? = null,
    crossinline itemContent: @Composable LazyItemScope.(index: Int, item: RowEntity) -> Unit
) {
    items(
        state.size,
        if (key != null) { keyIndex: Int -> key(state[keyIndex].item) } else null
    ) { index ->

        val item = state[index]

        key(key?.invoke(item.item)) {
            AnimatedVisibility(
                visibleState = item.visibility,
                enter = enterTransition,
                exit = exitTransition
            ) {
                itemContent(index, item.item)
            }
        }
    }
}

@Composable
fun updateAnimatedItemsState(
    newList: List<RowEntity>
): State<List<AnimatedItem<RowEntity>>> {

    val state = remember { mutableStateOf(emptyList<AnimatedItem<RowEntity>>()) }
    val firstInject = remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        state.value = emptyList()
        onDispose {
        }
    }

    LaunchedEffect(newList) {
        if (state.value == newList) {
            return@LaunchedEffect
        }
        val oldList = state.value.toList()

        val diffCb = object : DiffUtil.Callback() {
            override fun getOldListSize(): Int = oldList.size
            override fun getNewListSize(): Int = newList.size
            override fun areItemsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                oldList[oldItemPosition].item.key == newList[newItemPosition].key

            override fun areContentsTheSame(oldItemPosition: Int, newItemPosition: Int): Boolean =
                (oldList[oldItemPosition].item.contentHash
                    ?: oldList[oldItemPosition].item.key) == (newList[newItemPosition].contentHash
                    ?: newList[newItemPosition].key)

            override fun getChangePayload(oldItemPosition: Int, newItemPosition: Int): RowEntity =
                newList[newItemPosition]
        }
        val diffResult = calculateDiff(false, diffCb)
        val compositeList = oldList.toMutableList()

        diffResult.dispatchUpdatesTo(object : ListUpdateCallback {
            override fun onInserted(position: Int, count: Int) {
                for (i in 0 until count) {
                    val newItem = AnimatedItem(
                        visibility = MutableTransitionState(firstInject.value),
                        newList[position + i]
                    )
                    newItem.visibility.targetState = true
                    compositeList.add(position + i, newItem)
                }
            }

            override fun onRemoved(position: Int, count: Int) {
                for (i in 0 until count) {
                    compositeList[position + i].visibility.targetState = false
                }
            }

            override fun onMoved(fromPosition: Int, toPosition: Int) {
                // not detecting moves.
            }

            override fun onChanged(position: Int, count: Int, payload: Any?) {
                for (i in 0 until count) {
                    compositeList[position + i].item.itemModel = (payload as RowEntity).itemModel
                    compositeList[position + i].item.contentHash = payload.contentHash
                }
            }
        })

        if (state.value != compositeList) {
            state.value = compositeList
        }
        firstInject.value = false
        val initialAnimation = androidx.compose.animation.core.Animatable(1.0f)
        initialAnimation.animateTo(0f)
        state.value = state.value.filter { it.visibility.targetState }
    }

    return state
}

data class AnimatedItem<T>(
    val visibility: MutableTransitionState<Boolean>,
    val item: T,
) {

    override fun hashCode(): Int {
        return item?.hashCode() ?: 0
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AnimatedItem<*>

        return item == other.item
    }
}

suspend fun calculateDiff(
    detectMoves: Boolean = true,
    diffCb: DiffUtil.Callback
): DiffUtil.DiffResult {
    return withContext(Dispatchers.Unconfined) {
        DiffUtil.calculateDiff(diffCb, detectMoves)
    }
}