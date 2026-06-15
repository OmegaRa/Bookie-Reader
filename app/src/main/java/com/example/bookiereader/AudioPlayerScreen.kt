package com.example.bookiereader

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AudioPlayerScreen(
    viewModel: BookViewModel,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val book = viewModel.currentPlayingAudiobook

    if (book == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No audiobook is loaded", color = colorScheme.onBackground)
        }
        return
    }

    val application = context.applicationContext as BookieReaderApplication
    val imageLoader = application.imageLoader

    val coverUrl = viewModel.baseUrl + "books/" + book.id + "/cover" +
            if (viewModel.sessionCacheBuster.isNotEmpty()) "?v=${viewModel.sessionCacheBuster}" else ""

    var showChapterDialog by remember { mutableStateOf(false) }
    var showSpeedDialog by remember { mutableStateOf(false) }

    // Drag-to-seek support
    var sliderDraggingValue by remember { mutableStateOf<Float?>(null) }

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    actionIconContentColor = colorScheme.onBackground
                ),
                title = { Text(stringResource(R.string.now_playing), fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Album Artwork Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(bottom = 24.dp),
                contentAlignment = Alignment.Center
            ) {
                AsyncImage(
                    model = ImageRequest.Builder(context)
                        .data(coverUrl)
                        .crossfade(true)
                        .build(),
                    imageLoader = imageLoader,
                    contentDescription = book.title,
                    modifier = Modifier
                        .aspectRatio(1f)
                        .fillMaxHeight(0.85f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(colorScheme.surfaceVariant),
                    contentScale = ContentScale.Crop
                )
            }

            // Info Area
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = book.title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = colorScheme.onBackground,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                
                Spacer(modifier = Modifier.height(4.dp))
                
                Text(
                    text = book.author ?: stringResource(R.string.unknown_author),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colorScheme.onBackground.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

                if (!book.narrator.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.narrated_by, book.narrator),
                        style = MaterialTheme.typography.bodySmall,
                        color = colorScheme.onBackground.copy(alpha = 0.5f),
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                }

                if (!book.series.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SuggestionChip(
                        onClick = {},
                        label = {
                            Text(
                                text = stringResource(
                                    R.string.book_of_series,
                                    book.seriesOrder?.let { if (it % 1 == 0.0) it.toInt() else it } ?: "?",
                                    book.series
                                ),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Chapter Info
            if (viewModel.audioChapters.isNotEmpty()) {
                val currentChapter = viewModel.audioChapters.getOrNull(viewModel.currentAudioChapterIndex)
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .clickable { showChapterDialog = true }
                        .background(colorScheme.surfaceVariant.copy(alpha = 0.5f))
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.MenuBook, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = currentChapter?.title ?: stringResource(R.string.select_chapter),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                }
            } else {
                Spacer(modifier = Modifier.height(36.dp))
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Progress Slider Area
            Column(modifier = Modifier.fillMaxWidth()) {
                val duration = viewModel.audioDuration
                val currentPos = viewModel.audioPosition
                val progress = if (duration > 0) currentPos.toFloat() / duration else 0f

                val displayProgress = sliderDraggingValue ?: progress

                Slider(
                    value = displayProgress,
                    onValueChange = {
                        sliderDraggingValue = it
                    },
                    onValueChangeFinished = {
                        sliderDraggingValue?.let {
                            viewModel.seekAudiobookTo((it * duration).toInt())
                        }
                        sliderDraggingValue = null
                    },
                    colors = SliderDefaults.colors(
                        thumbColor = colorScheme.primary,
                        activeTrackColor = colorScheme.primary,
                        inactiveTrackColor = colorScheme.primary.copy(alpha = 0.24f)
                    ),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    val elapsedMs = if (sliderDraggingValue != null) (sliderDraggingValue!! * duration).toInt() else currentPos
                    val remainingMs = maxOf(0, duration - elapsedMs)
                    
                    Text(
                        text = formatTime(elapsedMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                    Text(
                        text = "-" + formatTime(remainingMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorScheme.onBackground.copy(alpha = 0.6f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Playback Controls Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Playback Speed Button
                IconButton(onClick = { showSpeedDialog = true }) {
                    Text(
                        text = "${viewModel.audioPlaybackSpeed}x",
                        fontWeight = FontWeight.Bold,
                        color = colorScheme.primary,
                        fontSize = 14.sp
                    )
                }

                // Seek Backward
                IconButton(onClick = { viewModel.skipAudiobookBackward() }) {
                    Icon(
                        imageVector = Icons.Default.FastRewind,
                        contentDescription = stringResource(R.string.skip_backward),
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onBackground
                    )
                }

                // Play / Pause FAB
                FloatingActionButton(
                    onClick = {
                        if (viewModel.isAudioPlaying) {
                            viewModel.pauseAudiobook()
                        } else {
                            viewModel.resumeAudiobook()
                        }
                    },
                    containerColor = colorScheme.primary,
                    contentColor = colorScheme.onPrimary,
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.size(64.dp)
                ) {
                    Icon(
                        imageVector = if (viewModel.isAudioPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (viewModel.isAudioPlaying) stringResource(R.string.pause) else stringResource(R.string.play),
                        modifier = Modifier.size(36.dp)
                    )
                }

                // Seek Forward
                IconButton(onClick = { viewModel.skipAudiobookForward() }) {
                    Icon(
                        imageVector = Icons.Default.FastForward,
                        contentDescription = stringResource(R.string.skip_forward),
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onBackground
                    )
                }

                // Stop Button
                IconButton(onClick = {
                    viewModel.stopAudiobookPlayback()
                    onBack()
                }) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = stringResource(R.string.stop),
                        modifier = Modifier.size(32.dp),
                        tint = colorScheme.onBackground
                    )
                }
            }
        }
    }

    // Chapters Dialog
    if (showChapterDialog) {
        AlertDialog(
            onDismissRequest = { showChapterDialog = false },
            title = { Text(stringResource(R.string.chapters_title), fontWeight = FontWeight.Bold) },
            text = {
                Box(modifier = Modifier.heightIn(max = 300.dp)) {
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        itemsIndexed(viewModel.audioChapters) { idx, chapter ->
                            val isActive = idx == viewModel.currentAudioChapterIndex
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isActive) colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                    .clickable {
                                        viewModel.seekAudiobookTo((chapter.start * 1000).toInt())
                                        showChapterDialog = false
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = chapter.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isActive) colorScheme.primary else colorScheme.onSurface,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = formatTime((chapter.start * 1000).toInt()),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isActive) colorScheme.primary else colorScheme.onSurface.copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showChapterDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }

    // Speed Dialog
    if (showSpeedDialog) {
        AlertDialog(
            onDismissRequest = { showSpeedDialog = false },
            title = { Text(stringResource(R.string.playback_speed_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f).forEach { speed ->
                        val isActive = speed == viewModel.audioPlaybackSpeed
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isActive) colorScheme.primary.copy(alpha = 0.15f) else Color.Transparent)
                                .clickable {
                                    viewModel.setAudiobookPlaybackSpeed(speed)
                                    showSpeedDialog = false
                                }
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "${speed}x",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal,
                                color = if (isActive) colorScheme.primary else colorScheme.onSurface,
                                modifier = Modifier.weight(1f)
                            )
                            if (isActive) {
                                Icon(Icons.Default.Check, contentDescription = null, tint = colorScheme.primary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSpeedDialog = false }) {
                    Text(stringResource(R.string.close))
                }
            }
        )
    }
}

// Inline format time helper
private fun formatTime(ms: Int): String {
    val totalSecs = ms / 1000
    val hours = totalSecs / 3600
    val minutes = (totalSecs % 3600) / 60
    val seconds = totalSecs % 60
    return if (hours > 0) {
        String.format("%02d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}