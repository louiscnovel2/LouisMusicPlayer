package com.portable.musicplayer.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.portable.musicplayer.PlayerViewModel
import com.portable.musicplayer.RowAction
import com.portable.musicplayer.RowData
import com.portable.musicplayer.RowKind
import com.portable.musicplayer.Screen

@Composable
fun MusicApp(vm: PlayerViewModel = viewModel()) {
    val state by vm.state.collectAsState()
    val context = LocalContext.current

    // runtime permissions: media access + notification (for the media controls)
    val audioPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
    val permissions = buildList {
        add(audioPermission)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        if (result[audioPermission] == true) vm.reload()
    }
    LaunchedEffect(Unit) {
        val missing = permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isEmpty()) vm.reload() else launcher.launch(missing.toTypedArray())
    }

    var showCreate by remember { mutableStateOf(false) }
    var newName by remember { mutableStateOf("") }
    var pendingDelete by remember { mutableStateOf<String?>(null) }
    var menuRow by remember { mutableStateOf<RowData?>(null) }

    BackHandler(enabled = state.screen != Screen.NowPlaying) { vm.back() }

    DeviceFrame {
        Box(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    if (state.screen == Screen.EqualizerScreen) {
                        EqualizerScreen(
                            state = state,
                            onBack = vm::back,
                            onEnabled = vm::setEqEnabled,
                            onNormalize = vm::setEqNormalize,
                            onBand = vm::setEqBand,
                            onBass = vm::setEqBass,
                            onPreamp = vm::setEqPreamp,
                            onPreset = vm::applyEqPreset,
                            onResetFlat = vm::resetEqFlat,
                            onSavePreset = vm::saveEqPreset,
                        )
                    } else if (state.screen == Screen.NowPlaying) {
                        NowPlayingScreen(
                            state = state,
                            onToggle = vm::toggle,
                            onPrev = vm::previous,
                            onNext = vm::next,
                            onCycleLoop = vm::cycleLoop,
                            onToggleShuffle = vm::toggleShuffle,
                            onSeek = vm::seek,
                        )
                    } else {
                        ListScreen(
                            state = state,
                            onBack = vm::back,
                            onRow = { row ->
                                when (val a = row.action) {
                                    RowAction.NewPlaylist -> {
                                        newName = ""
                                        showCreate = true
                                    }
                                    is RowAction.DeletePlaylist -> pendingDelete = a.name
                                    else -> vm.onRow(row)
                                }
                            },
                            onLongPress = { menuRow = it },
                            loadScroll = vm::listScroll,
                            saveScroll = vm::saveListScroll,
                        )
                    }
                }
                BottomBar(
                    active = state.bottomActive,
                    onBack = vm::back,
                    onFolders = vm::openFoldersRoot,
                    onPlaylists = vm::openPlaylistsRoot,
                    onSettings = vm::openSettings,
                )
            }

            state.osd?.let { text ->
                Box(
                    Modifier
                        .align(Alignment.TopCenter)
                        .padding(top = 56.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xE60E1218))
                        .border(1.dp, Color(0x40FFFFFF), RoundedCornerShape(8.dp))
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text, color = Color(0xFFF4F4F5), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }

    // -- dialogs --------------------------------------------------------------
    if (showCreate) {
        AlertDialog(
            onDismissRequest = { showCreate = false },
            title = { Text("新規プレイリスト") },
            text = {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    singleLine = true,
                    label = { Text("プレイリスト名") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.createPlaylistFromQueue(newName)
                    showCreate = false
                }) { Text("作成") }
            },
            dismissButton = {
                TextButton(onClick = { showCreate = false }) { Text("キャンセル") }
            },
        )
    }

    pendingDelete?.let { name ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("削除") },
            text = { Text("「$name」を削除しますか？") },
            confirmButton = {
                TextButton(onClick = {
                    vm.deletePlaylist(name)
                    pendingDelete = null
                }) { Text("削除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("キャンセル") }
            },
        )
    }

    menuRow?.let { row ->
        RowMenuDialog(
            row = row,
            playlists = vm.playlistNames(),
            screen = state.screen,
            onDismiss = { menuRow = null },
            onOpenFolder = { vm.openFolder(it); menuRow = null },
            onHideFolder = { vm.hideFolder(it); menuRow = null },
            onAddToPlaylist = { name, path ->
                vm.addToPlaylist(name, path)
                menuRow = null
            },
            onRemoveFromPlaylist = { name, path ->
                vm.removeFromPlaylist(name, path)
                menuRow = null
            },
        )
    }
}

@Composable
private fun BottomBar(
    active: String?,
    onBack: () -> Unit,
    onFolders: () -> Unit,
    onPlaylists: () -> Unit,
    onSettings: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GlyphButton("back", size = 30.dp, active = active == "back", onClick = onBack)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GlyphButton("folder", size = 30.dp, active = active == "folders", onClick = onFolders)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GlyphButton("list", size = 30.dp, active = active == "playlists", onClick = onPlaylists)
        }
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            GlyphButton("toolbox", size = 30.dp, active = active == "settings", onClick = onSettings)
        }
    }
}

@Composable
private fun RowMenuDialog(
    row: RowData,
    playlists: List<String>,
    screen: Screen,
    onDismiss: () -> Unit,
    onOpenFolder: (String) -> Unit,
    onHideFolder: (String) -> Unit,
    onAddToPlaylist: (String, String) -> Unit,
    onRemoveFromPlaylist: (String, String) -> Unit,
) {
    val path = when (val a = row.action) {
        is RowAction.OpenFolder -> a.path
        is RowAction.PlayTrack -> a.path
        else -> null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(row.label) },
        text = {
            Column {
                if (row.kind == RowKind.FOLDER && path != null && screen == Screen.FoldersRoot) {
                    MenuItem("▶ このフォルダを開く") { onOpenFolder(path) }
                    MenuItem("このフォルダを一覧から削除") { onHideFolder(path) }
                }
                if (row.kind == RowKind.TRACK && path != null) {
                    Text("プレイリストに追加", color = Palette.TextMuted, fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp))
                    if (playlists.isEmpty()) {
                        Text("プレイリストがありません", fontSize = 13.sp,
                            modifier = Modifier.padding(vertical = 6.dp))
                    }
                    for (pl in playlists) {
                        MenuItem(pl) { onAddToPlaylist(pl, path) }
                    }
                    if (screen is Screen.PlaylistScreen) {
                        MenuItem("この曲をリストから削除") { onRemoveFromPlaylist(screen.name, path) }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("閉じる") }
        },
    )
}

@Composable
private fun MenuItem(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Palette.TextPrimary,
        fontSize = 15.sp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    )
}
