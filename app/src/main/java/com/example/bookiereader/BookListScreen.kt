package com.example.bookiereader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.IosShare
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.bookiereader.data.Book

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookListScreen(
    viewModel: BookViewModel,
    onOpenBook: () -> Unit,
    onLogout: () -> Unit,
    onSettings: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
        onResult = { uri ->
            uri?.let { viewModel.importLocalBook(context, it) }
        }
    )

    var isGridView by remember { mutableStateOf(true) }
    
    var showSortMenu by remember { mutableStateOf(false) }
    var showProfileMenu by remember { mutableStateOf(false) }

    val selectedBooks = remember { mutableStateOf(setOf<Int>()) }
    val isSelectionMode = selectedBooks.value.isNotEmpty()

    val onToggleSelection: (Int) -> Unit = { bookId ->
        if (selectedBooks.value.contains(bookId)) {
            selectedBooks.value = selectedBooks.value - bookId
        } else {
            selectedBooks.value = selectedBooks.value + bookId
        }
    }

    val sortedBooks = viewModel.filteredBooks

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    actionIconContentColor = colorScheme.onBackground
                ),
                title = {
                    if (isSelectionMode) {
                        Text(pluralStringResource(R.plurals.selected_count, selectedBooks.value.size, selectedBooks.value.size))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = R.drawable.ic_bookie_logo),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = Color.Unspecified
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                },
                navigationIcon = {
                    if (isSelectionMode) {
                        IconButton(onClick = { selectedBooks.value = emptySet() }) {
                            Icon(Icons.Default.Close, contentDescription = stringResource(R.string.cancel_selection))
                        }
                    }
                },
                actions = {
                    if (isSelectionMode) {
                        IconButton(onClick = {
                            viewModel.deleteSelectedBooks(selectedBooks.value)
                            selectedBooks.value = emptySet()
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_selected), tint = Color.Red)
                        }
                    } else {
                        IconButton(onClick = { 
                            launcher.launch(arrayOf("application/epub+zip", "application/pdf"))
                        }) {
                            Icon(Icons.Outlined.IosShare, contentDescription = stringResource(R.string.import_book))
                        }
                        Box {
                            IconButton(onClick = { showProfileMenu = true }) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AccountCircle, contentDescription = stringResource(R.string.profile))
                                    Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options), modifier = Modifier.size(16.dp))
                                }
                            }
                            DropdownMenu(
                                expanded = showProfileMenu,
                                onDismissRequest = { showProfileMenu = false },
                                modifier = Modifier.background(colorScheme.surface)
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.settings), color = colorScheme.onSurface) },
                                    onClick = { 
                                        showProfileMenu = false
                                        onSettings()
                                    },
                                    leadingIcon = { Icon(Icons.Outlined.Settings, contentDescription = null, tint = colorScheme.onSurface) }
                                )
                                HorizontalDivider(color = colorScheme.onSurface.copy(alpha = 0.2f))
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.logout), color = Color.Red) },
                                    onClick = { 
                                        showProfileMenu = false
                                        onLogout()
                                    }
                                )
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
        ) {
            // Search Bar Row
            Row(
                modifier = Modifier
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                BasicTextField(
                    value = viewModel.searchQuery,
                    onValueChange = { viewModel.updateSearchQuery(it) },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .background(colorScheme.surface, RoundedCornerShape(8.dp)),
                    textStyle = TextStyle(color = colorScheme.onSurface, fontSize = 15.sp),
                    cursorBrush = SolidColor(colorScheme.onSurface),
                    singleLine = true,
                    decorationBox = { innerTextField ->
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp)
                                .fillMaxSize(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Default.Search,
                                contentDescription = null,
                                tint = colorScheme.onSurface.copy(alpha = 0.6f),
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                if (viewModel.searchQuery.isEmpty()) {
                                    Text(
                                        text = stringResource(R.string.search_placeholder),
                                        color = colorScheme.onSurface.copy(alpha = 0.6f),
                                        fontSize = 15.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                innerTextField()
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))
                
                // Filter/Sort Button
                Box {
                    IconButton(
                        onClick = { showSortMenu = true },
                        modifier = Modifier
                            .size(48.dp)
                            .background(colorScheme.surface, RoundedCornerShape(8.dp))
                    ) {
                        Icon(Icons.Default.Tune, contentDescription = stringResource(R.string.filter), tint = colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                        modifier = Modifier.background(colorScheme.surface)
                    ) {
                        mapOf(
                            "Title" to R.string.sort_by_title,
                            "Author" to R.string.sort_by_author,
                            "Series" to R.string.sort_by_series,
                            "Tag" to R.string.sort_by_tag
                        ).forEach { (option, labelRes) ->
                            DropdownMenuItem(
                                text = { Text(stringResource(labelRes), color = if (viewModel.sortBy == option) accentColor else colorScheme.onSurface) },
                                onClick = {
                                    viewModel.setSortOrder(option)
                                    showSortMenu = false
                                }
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.width(8.dp))
                
                // Grid/List Toggle Button
                IconButton(
                    onClick = { isGridView = !isGridView },
                    modifier = Modifier
                        .size(48.dp)
                        .background(colorScheme.surface, RoundedCornerShape(8.dp))
                ) {
                    Icon(
                        if (isGridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                        contentDescription = stringResource(R.string.toggle_view), 
                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }

            // Tag Chips
            val tags = listOf(stringResource(R.string.all_tags)) + viewModel.allTags
            if (tags.size > 1) {
                androidx.compose.foundation.lazy.LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.padding(bottom = 8.dp)
                ) {
                    items(tags) { tag ->
                        val isAll = tag == stringResource(R.string.all_tags)
                        FilterChip(
                            selected = (isAll && viewModel.selectedTag == null) || (tag == viewModel.selectedTag),
                            onClick = { viewModel.updateSelectedTag(if (isAll) "All" else tag) },
                            label = { Text(tag) },
                            colors = FilterChipDefaults.filterChipColors(
                                containerColor = colorScheme.surface,
                                labelColor = colorScheme.onSurface.copy(alpha = 0.6f),
                                selectedContainerColor = accentColor.copy(alpha = 0.2f),
                                selectedLabelColor = accentColor
                            ),
                            border = null
                        )
                    }
                }
            }

            val pullToRefreshState = rememberPullToRefreshState()
            
            PullToRefreshBox(
                state = pullToRefreshState,
                isRefreshing = viewModel.isLoading,
                onRefresh = { viewModel.refreshBooks() },
                modifier = Modifier.fillMaxSize()
            ) {
                if (sortedBooks.isEmpty() && !viewModel.isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.no_books_found), color = colorScheme.onSurface.copy(alpha = 0.6f))
                    }
                } else {
                    if (isGridView) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedBooks) { book ->
                                BookGridItem(
                                    book = book,
                                    viewModel = viewModel,
                                    onOpenBook = onOpenBook,
                                    isSelected = selectedBooks.value.contains(book.id),
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = { onToggleSelection(book.id) }
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(sortedBooks) { book ->
                                BookListItem(
                                    book = book,
                                    viewModel = viewModel,
                                    onOpenBook = onOpenBook,
                                    isSelected = selectedBooks.value.contains(book.id),
                                    isSelectionMode = isSelectionMode,
                                    onToggleSelection = { onToggleSelection(book.id) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun BookGridItem(
    book: Book,
    viewModel: BookViewModel,
    onOpenBook: () -> Unit,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelection: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    
    val imageLoader = viewModel.okHttpClient?.let {
        ImageLoader.Builder(context).okHttpClient(it).build()
    } ?: ImageLoader(context)

    val coverUrl = "${viewModel.baseUrl}books/${book.id}/cover"
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else colorScheme.surface)
            .clickable { 
                if (isSelectionMode) {
                   onToggleSelection()
                } else {
                    viewModel.downloadAndOpenBook(context, book, onOpenBook) 
                }
            }
    ) {
        Box {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(0.7f)
                    .clip(RoundedCornerShape(topStart = 8.dp, topEnd = 8.dp)),
                contentScale = ContentScale.Crop
            )
            
            if (isSelectionMode) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(accentColor.copy(alpha = 0.3f))
                    )
                }
                IconButton(
                    onClick = onToggleSelection,
                    modifier = Modifier.align(Alignment.TopEnd).padding(4.dp)
                ) {
                    Icon(
                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            if (!isSelectionMode) {
                Box(modifier = Modifier.align(Alignment.TopStart)) {
                    IconButton(
                        onClick = { showMenu = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.MoreVert, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                    }
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                        modifier = Modifier.background(colorScheme.surface)
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.select), color = colorScheme.onSurface) },
                            onClick = {
                                showMenu = false
                                onToggleSelection()
                            }
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.delete), color = Color.Red) },
                            onClick = {
                                showMenu = false
                                viewModel.deleteBook(book)
                            }
                        )
                    }
                }
            }
        }
        
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            val subtitle = if (book.series != null) {
                stringResource(
                    R.string.book_of_series, 
                    book.seriesOrder?.let { if (it % 1 == 0.0) it.toInt() else it } ?: "?", 
                    book.series
                )
            } else {
                book.author ?: stringResource(R.string.unknown_author)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun BookListItem(
    book: Book,
    viewModel: BookViewModel,
    onOpenBook: () -> Unit,
    isSelected: Boolean,
    isSelectionMode: Boolean,
    onToggleSelection: () -> Unit
) {
    val context = LocalContext.current
    val colorScheme = MaterialTheme.colorScheme
    val accentColor = colorScheme.primary
    var showMenu by remember { mutableStateOf(false) }
    
    val imageLoader = viewModel.okHttpClient?.let {
        ImageLoader.Builder(context).okHttpClient(it).build()
    } ?: ImageLoader(context)

    val coverUrl = "${viewModel.baseUrl}books/${book.id}/cover"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isSelected) accentColor.copy(alpha = 0.2f) else colorScheme.surface)
            .clickable { 
                if (isSelectionMode) {
                    onToggleSelection()
                } else {
                    viewModel.downloadAndOpenBook(context, book, onOpenBook)
                }
            }
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(contentAlignment = Alignment.Center) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(coverUrl)
                    .crossfade(true)
                    .build(),
                imageLoader = imageLoader,
                contentDescription = null,
                modifier = Modifier
                    .size(60.dp, 80.dp)
                    .clip(RoundedCornerShape(4.dp)),
                contentScale = ContentScale.Crop
            )
            if (isSelectionMode) {
                IconButton(onClick = onToggleSelection) {
                    Icon(
                        if (isSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                        contentDescription = null,
                        tint = if (isSelected) accentColor else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val subtitle = if (book.series != null) {
                stringResource(
                    R.string.book_of_series, 
                    book.seriesOrder?.let { if (it % 1 == 0.0) it.toInt() else it } ?: "?", 
                    book.series
                )
            } else {
                book.author ?: stringResource(R.string.unknown_author)
            }
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                maxLines = 1
            )
            Text(
                text = book.format.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = accentColor
            )
        }
        
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.more_options), tint = colorScheme.onSurface.copy(alpha = 0.6f))
            }
            DropdownMenu(
                expanded = showMenu,
                onDismissRequest = { showMenu = false },
                modifier = Modifier.background(colorScheme.surface)
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.select), color = colorScheme.onSurface) },
                    onClick = {
                        showMenu = false
                        onToggleSelection()
                    }
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.delete), color = Color.Red) },
                    onClick = {
                        showMenu = false
                        viewModel.deleteBook(book)
                    }
                )
            }
        }
    }
}
