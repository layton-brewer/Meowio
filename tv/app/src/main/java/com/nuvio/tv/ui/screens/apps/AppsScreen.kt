@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.apps

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import androidx.tv.material3.Button
import androidx.tv.material3.Card
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.ui.theme.NuvioTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** One installed app that can be opened from the TV. */
private data class InstalledApp(
    val packageName: String,
    val label: String,
    val launch: Intent,
    val picture: ImageBitmap?,
)

/** Finds the apps a TV remote can open: TV apps first choice, plain launcher apps as a fallback. */
private suspend fun loadApps(context: Context): List<InstalledApp> = withContext(Dispatchers.Default) {
    val pm = context.packageManager
    fun query(category: String) = pm.queryIntentActivities(
        Intent(Intent.ACTION_MAIN).addCategory(category), 0
    )
    val found = (query(Intent.CATEGORY_LEANBACK_LAUNCHER) + query(Intent.CATEGORY_LAUNCHER))
        .filter { it.activityInfo.packageName != context.packageName }
        .distinctBy { it.activityInfo.packageName }
    found.mapNotNull { info ->
        runCatching {
            val picture = (info.activityInfo.loadBanner(pm) ?: info.loadIcon(pm))
                .toBitmap(width = 320, height = 180)
                .asImageBitmap()
            InstalledApp(
                packageName = info.activityInfo.packageName,
                label = info.loadLabel(pm).toString(),
                launch = Intent(Intent.ACTION_MAIN)
                    .setClassName(info.activityInfo.packageName, info.activityInfo.name)
                    .addCategory(Intent.CATEGORY_LAUNCHER)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                picture = picture,
            )
        }.getOrNull()
    }.sortedBy { it.label.lowercase() }
}

/**
 * Every app installed on the box, so Meowio can serve as a simple home screen. OK opens an app,
 * and holding OK offers hide, app info and uninstall.
 */
@Composable
fun AppsScreen() {
    val context = LocalContext.current
    val hidden by AppsStore.hidden.collectAsState()
    var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
    var showHidden by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<InstalledApp?>(null) }

    LaunchedEffect(Unit) { apps = loadApps(context) }

    val visible = apps?.filter { showHidden || it.packageName !in hidden }.orEmpty()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioTheme.colors.Background)
            .padding(
                start = NuvioTheme.spacing.xxxl,
                end = NuvioTheme.spacing.xxxl,
                top = NuvioTheme.spacing.xl
            )
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.nav_apps),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.SemiBold,
                color = NuvioTheme.colors.TextPrimary
            )
            if (hidden.isNotEmpty()) {
                Button(onClick = { showHidden = !showHidden }) {
                    Text(
                        stringResource(
                            if (showHidden) R.string.apps_hide_hidden else R.string.apps_show_hidden
                        )
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(NuvioTheme.spacing.lg))
        when {
            apps == null -> Text(
                stringResource(R.string.apps_loading),
                color = NuvioTheme.colors.TextSecondary
            )
            visible.isEmpty() -> Text(
                stringResource(R.string.apps_empty),
                color = NuvioTheme.colors.TextSecondary
            )
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(190.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(visible, key = { it.packageName }) { app ->
                    AppTile(
                        app = app,
                        dimmed = app.packageName in hidden,
                        onOpen = { runCatching { context.startActivity(app.launch) } },
                        onMenu = { menuFor = app }
                    )
                }
            }
        }
    }

    menuFor?.let { app ->
        val isHidden = app.packageName in hidden
        Dialog(onDismissRequest = { menuFor = null }) {
            Column(
                modifier = Modifier
                    .background(NuvioTheme.colors.BackgroundCard, MaterialTheme.shapes.large)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    app.label,
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioTheme.colors.TextPrimary
                )
                Button(onClick = {
                    menuFor = null
                    runCatching { context.startActivity(app.launch) }
                }) { Text(stringResource(R.string.apps_open)) }
                Button(onClick = {
                    AppsStore.setHidden(context, app.packageName, !isHidden)
                    menuFor = null
                }) {
                    Text(stringResource(if (isHidden) R.string.apps_unhide else R.string.apps_hide))
                }
                Button(onClick = {
                    menuFor = null
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                .setData(Uri.fromParts("package", app.packageName, null))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }) { Text(stringResource(R.string.apps_info)) }
                Button(onClick = { menuFor = null }) { Text(stringResource(R.string.apps_close)) }
            }
        }
    }
}

@Composable
private fun AppTile(
    app: InstalledApp,
    dimmed: Boolean,
    onOpen: () -> Unit,
    onMenu: () -> Unit,
) {
    Column(modifier = Modifier.alpha(if (dimmed) 0.45f else 1f)) {
        Card(
            onClick = onOpen,
            onLongClick = onMenu,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                app.picture?.let {
                    Image(
                        bitmap = it,
                        contentDescription = app.label,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
        Text(
            text = app.label,
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioTheme.colors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp)
        )
    }
}
