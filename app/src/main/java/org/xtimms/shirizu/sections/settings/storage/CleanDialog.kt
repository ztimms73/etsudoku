package org.xtimms.shirizu.sections.settings.storage

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CleaningServices
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cafe.adriel.voyager.hilt.getScreenModel
import org.xtimms.shirizu.R
import org.xtimms.shirizu.core.components.ConfirmButton
import org.xtimms.shirizu.core.components.DialogCheckBoxItem
import org.xtimms.shirizu.core.components.DismissButton
import org.xtimms.shirizu.core.components.ShirizuDialog
import org.xtimms.shirizu.utils.FileSize

@Composable
fun CleanDialog(
    onDismissRequest: () -> Unit = {},
    isPagesCacheSelected: Boolean,
    isThumbnailsCacheSelected: Boolean,
    isNetworkCacheSelected: Boolean,
    onConfirm: (isPagesCacheSelected: Boolean, isThumbnailCacheSelected: Boolean, isNetworkCacheSelected: Boolean) -> Unit = { _, _, _ -> }
) {

    // val screenModel = getScreenModel<StorageScreenModel>()
    // val state by screenModel.state.collectAsState()

    var pagesCache by remember {
        mutableStateOf(isPagesCacheSelected)
    }
    var thumbnailsCache by remember {
        mutableStateOf(isThumbnailsCacheSelected)
    }
    var networkCache by remember {
        mutableStateOf(isNetworkCacheSelected)
    }

    ShirizuDialog(
        onDismissRequest = onDismissRequest,
        confirmButton = {
            ConfirmButton {
                onConfirm(pagesCache, thumbnailsCache, networkCache)
                onDismissRequest()
            }
        },
        dismissButton = {
            DismissButton {
                onDismissRequest()
            }
        },
        title = {
            Text(
                text = stringResource(
                    id = R.string.free_up_space
                )
            )
        },
        icon = { Icon(imageVector = Icons.Outlined.CleaningServices, contentDescription = null) },
        text = {
            Column {
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                DialogCheckBoxItem(
                    text = stringResource(id = R.string.pages_cache),
                    checked = pagesCache
                ) {
                    pagesCache = !pagesCache
                }
                DialogCheckBoxItem(
                    text = stringResource(id = R.string.thumbnails_cache),
                    checked = thumbnailsCache
                ) {
                    thumbnailsCache = !thumbnailsCache
                }
                DialogCheckBoxItem(
                    text = stringResource(id = R.string.network_cache),
                    checked = networkCache
                ) {
                    networkCache = !networkCache
                }
                HorizontalDivider(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp))
                Spacer(modifier = Modifier.height(4.dp))
                //val summary = StringBuilder().run {
                //    append(
                //        FileSize.BYTES.format(
                //            LocalContext.current,
                            // (uiState.pagesCache + uiState.thumbnailsCache + uiState.httpCacheSize).toFloat()
                //        )
                //    )
               //     append("")
               // }
                //Text(
                //    text = stringResource(R.string.free_up_space_summary) + " " + summary,
                //    modifier = Modifier
                //        .fillMaxWidth()
                //        .padding(horizontal = 24.dp),
                //)
            }
        })
}