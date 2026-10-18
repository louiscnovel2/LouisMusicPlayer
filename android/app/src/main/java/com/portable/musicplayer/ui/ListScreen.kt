package com.portable.musicplayer.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portable.musicplayer.RowData
import com.portable.musicplayer.RowKind
import com.portable.musicplayer.UiState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

@Composable
fun ListScreen(
    state: UiState,
    onBack: () -> Unit,
    onRow: (RowData) -> Unit,
    onLongPress: (RowData) -> Unit,
    loadScroll: (String) -> Pair<Int, Int> = { 0 to 0 },
    saveScroll: (String, Int, Int) -> Unit = { _, _, _ -> },
) {
    // One list state per screen key, seeded from the position saved last time
    // this list was on screen (including the previous app session).
    val listState = remember(state.listKey) {
        val (firstIndex, firstOffset) = loadScroll(state.listKey)
        LazyListState(firstIndex, firstOffset)
    }
    LaunchedEffect(state.listKey, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collectLatest { (index, offset) ->
                if (index == 0 && offset == 0) {
                    saveScroll(state.listKey, 0, 0)
                } else {
                    // debounce: persist only once the scroll has settled
                    delay(300)
                    saveScroll(state.listKey, index, offset)
                }
            }
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 6.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            GlyphButton("back", size = 26.dp, onClick = onBack)
            Spacer(Modifier.width(4.dp))
            Text(
                text = state.title,
                color = Palette.TextPrimary,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth()) {
            items(state.rows) { row ->
                RowItem(row = row, onClick = { onRow(row) }, onLongClick = { onLongPress(row) })
            }
        }

        if (state.hint.isNotEmpty()) {
            Text(
                text = state.hint,
                color = Palette.TextSecondary,
                fontSize = 11.sp,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RowItem(row: RowData, onClick: () -> Unit, onLongClick: () -> Unit) {
    val iconColor = if (row.playing) Palette.Accent else Palette.TextSecondary
    Box(
        Modifier
            .fillMaxWidth()
            .height(46.dp)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .drawBehind {
                drawLine(
                    color = Color(0x14FFFFFF),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1f,
                )
            }
            .background(if (row.playing) Color(0x17666666) else Color.Transparent)
    ) {
        Row(
            Modifier.fillMaxSize().padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Canvas(Modifier.size(20.dp)) { drawGlyph(row.icon, iconColor, 1.6f) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = row.label,
                    color = if (row.playing) Palette.Accent else Palette.TextPrimary,
                    fontSize = if (row.kind == RowKind.TRACK) 14.sp else 15.sp,
                    fontWeight = if (row.kind == RowKind.TRACK) FontWeight.Normal else FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (row.sub.isNotEmpty()) {
                    Text(
                        text = row.sub,
                        color = Palette.TextMuted,
                        fontSize = 11.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (row.badge.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Badge(row.badge)
            }
        }
    }
}
