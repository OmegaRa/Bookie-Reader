package com.example.bookiereader

import android.annotation.SuppressLint
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.graphics.createBitmap
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.navigator.preferences.ColumnCount
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.navigator.preferences.Axis
import org.readium.r2.navigator.preferences.Fit
import org.readium.r2.navigator.preferences.ReadingProgression
import org.readium.r2.navigator.pdf.PdfNavigatorFragment
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.r2.navigator.VisualNavigator
import org.readium.adapter.pdfium.navigator.PdfiumPreferences
import java.io.ByteArrayInputStream

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class, ExperimentalReadiumApi::class)
@Composable
fun ReaderScreen(viewModel: BookViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = viewModel.currentBookFile
    val book = viewModel.currentBook
    val readerTheme = viewModel.readerTheme
    val systemColorScheme = MaterialTheme.colorScheme

    // UI Colors - These follow the theme and system settings
    val (uiBg, uiText) = remember(readerTheme, systemColorScheme) {
        when (readerTheme) {
            "Light" -> Pair(Color.White.toArgb(), Color.Black.toArgb())
            "Sepia" -> Pair(Color(0xFFF4ECD8).toArgb(), Color(0xFF5B4636).toArgb())
            "Dark" -> Pair(Color(0xFF121212).toArgb(), Color.White.toArgb())
            "System" -> Pair(systemColorScheme.surface.toArgb(), systemColorScheme.onSurface.toArgb())
            else -> Pair(systemColorScheme.surface.toArgb(), systemColorScheme.onSurface.toArgb())
        }
    }

    // Book Colors
    val isSystemDark = isSystemInDarkTheme()
    val (bookBg, bookText) = remember(readerTheme, isSystemDark, systemColorScheme) {
        when (readerTheme) {
            "Sepia" -> Pair(Color(0xFFF4ECD8).toArgb(), Color(0xFF5B4636).toArgb())
            "Dark" -> Pair(Color(0xFF121212).toArgb(), Color.White.toArgb())
            "Light" -> Pair(Color.White.toArgb(), Color.Black.toArgb())
            "System" -> if (isSystemDark) {
                Pair(Color(0xFF121212).toArgb(), Color.White.toArgb())
            } else {
                Pair(Color.White.toArgb(), Color.Black.toArgb())
            }
            else -> Pair(systemColorScheme.surface.toArgb(), systemColorScheme.onSurface.toArgb())
        }
    }

    val pages = viewModel.readerPages
    val error = viewModel.readerError
    val isReaderLoading = viewModel.isReaderLoading
    val toc = viewModel.readerToc
    val bookmarks = viewModel.bookmarks
    var currentNavigator by remember(book?.id) { mutableStateOf<VisualNavigator?>(null) }
    var isRestoring by remember(book?.id) { mutableStateOf(true) }

    LaunchedEffect(currentNavigator) {
        currentNavigator?.currentLocator?.collect { locator ->
            if (isRestoring) return@collect

            val totalProgression = locator.locations.totalProgression
            if (totalProgression != null) {
                viewModel.currentProgression = totalProgression
                viewModel.saveReadingProgress(locator)
            } else {
                val fallback = viewModel.calculateFallbackProgress(viewModel.currentPublication, locator)
                viewModel.currentProgression = fallback
                viewModel.saveReadingProgress(locator, fallback)
            }
            // For PDF, position is usually the 1-indexed page number
            locator.locations.position?.let { viewModel.currentPageIndex = it - 1 }
        }
    }

    val format = book?.format?.lowercase() ?: ""

    // Use key(book?.id) to force reset pagerState when book changes.
    val pagerState = key(book?.id) {
        rememberPagerState(pageCount = { viewModel.totalPagesCount })
    }

    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showControls by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    val bookmarkToDeleteState = remember { mutableStateOf<Locator?>(null) }
    val bookmarkToDelete = bookmarkToDeleteState.value

    // Track the last stable position to maintain it during pagination updates
    var lastStableChapter by remember(book?.id) { mutableIntStateOf(0) }
    var lastStablePageInChapter by remember(book?.id) { mutableIntStateOf(0) }

    // Track if we have already restored the position for the current book
    var hasRestoredInitialPosition by remember(book?.id) { mutableStateOf(false) }

    // Update stable position when user finishes scrolling
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress && hasRestoredInitialPosition) {
            val (c, p) = viewModel.getChapterAndPage(pagerState.currentPage)
            lastStableChapter = c
            lastStablePageInChapter = p
            
            // Save progress for legacy formats
            if (!isRestoring && currentNavigator == null && viewModel.totalPagesCount > 1) {
                val progress = pagerState.currentPage.toDouble() / (viewModel.totalPagesCount - 1).toDouble()
                book?.id?.let { viewModel.saveLegacyProgress(it, progress) }
            }
        }
    }

    // Initial position restoration logic
    LaunchedEffect(book?.id, currentNavigator) {
        if (currentNavigator == null) {
            // Legacy/Mobi path
            val progress = book?.id?.let { viewModel.getLegacyProgress(it) } ?: 0.0
            if (progress > 0.0) {
                // Wait for at least some pages to be loaded/calculated if possible
                var attempts = 0
                while (viewModel.totalPagesCount <= 1 && attempts < 10) {
                    delay(100)
                    attempts++
                }
                
                if (viewModel.totalPagesCount > 1) {
                    val targetPage = (progress * (viewModel.totalPagesCount - 1)).toInt().coerceIn(0, viewModel.totalPagesCount - 1)
                    pagerState.scrollToPage(targetPage)
                    val (c, p) = viewModel.getChapterAndPage(targetPage)
                    lastStableChapter = c
                    lastStablePageInChapter = p
                }
            }
            hasRestoredInitialPosition = true
            isRestoring = false
        } else {
            // Readium path - it handles its own restoration via initialLocator
            delay(1000) // Stability delay
            hasRestoredInitialPosition = true
            isRestoring = false
        }
    }

    // Adjust pager when page counts change to prevent jumping (Pagination Stability)
    LaunchedEffect(viewModel.chapterPageCounts) {
        if (hasRestoredInitialPosition && !isRestoring && currentNavigator == null && viewModel.readerPages.isNotEmpty()) {
            val newGlobalIndex = viewModel.getGlobalIndex(lastStableChapter, lastStablePageInChapter)
            if (newGlobalIndex != pagerState.currentPage && newGlobalIndex < viewModel.totalPagesCount) {
                pagerState.scrollToPage(newGlobalIndex)
            }
        }
    }

    // Auto-hide controls after 3 seconds, but only if settings are not shown
    LaunchedEffect(pagerState.currentPage, showControls, showSettings) {
        if (showControls && !showSettings) {
            delay(3000)
            showControls = false
        }
    }

    LaunchedEffect(file?.absolutePath, bookBg, bookText, viewModel.readerFontSize, viewModel.readerScrollMode) {
        if (file != null && file.exists()) {
            val format = file.extension.lowercase()
            val isReadium = format == "epub" || format == "pdf"
            // Only reset navigator if it's a different book or a format that requires full reload (legacy)
            if (!isReadium || viewModel.currentBook?.id != book?.id) {
                currentNavigator = null
            }
            viewModel.prepareReader(file, bookBg, bookText)
        }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = drawerState.isOpen,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = Color(uiBg),
                drawerContentColor = Color(uiText)
            ) {
                HorizontalDivider()
                if (bookmarks.isNotEmpty()) {
                    Text(
                        stringResource(R.string.bookmarks_header),
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp)) {
                        itemsIndexed(bookmarks) { _, locator ->
                            NavigationDrawerItem(
                                label = {
                                    Text(
                                        locator.title ?: "Page ${((locator.locations.totalProgression ?: 0.0) * 100).toInt()}%",
                                        maxLines = 1
                                    )
                                },
                                selected = false,
                                onClick = {},
                                modifier = Modifier.combinedClickable(
                                    onClick = {
                                        currentNavigator?.go(locator, animated = true)
                                        scope.launch { drawerState.close() }
                                    },
                                    onLongClick = { bookmarkToDeleteState.value = locator }
                                ),
                                colors = NavigationDrawerItemDefaults.colors(
                                    unselectedTextColor = Color(uiText),
                                    unselectedContainerColor = Color.Transparent
                                )
                            )
                        }
                    }
                    HorizontalDivider()
                }

                Text(
                    stringResource(R.string.toc_header),
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.titleMedium
                )
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    itemsIndexed(toc) { _, item ->
                        NavigationDrawerItem(
                            label = {
                                Text(
                                    item.title,
                                    modifier = Modifier.padding(start = (item.level * 16).dp)
                                )
                            },
                            selected = false,
                            onClick = {
                                if (format == "epub" || format == "pdf") {
                                    val itemLocator = item.locatorJson?.let { Locator.fromJSON(org.json.JSONObject(it)) }
                                    if (itemLocator != null) {
                                        currentNavigator?.go(itemLocator, animated = true)
                                    } else {
                                        val link = viewModel.currentPublication?.linkWithHref(org.readium.r2.shared.util.Url(item.href)!!)
                                        if (link != null) {
                                            val locator = viewModel.currentPublication?.locatorFromLink(link)
                                            if (locator != null) {
                                                currentNavigator?.go(locator, animated = true)
                                            }
                                        }
                                    }
                                } else {
                                    Log.d("ReaderScreen", "ToC Clicked: '${item.title}' -> target page: ${item.pageIndex}")
                                    if (item.pageIndex != -1) {
                                        scope.launch {
                                            val globalIndex = viewModel.getGlobalIndex(item.pageIndex, 0)
                                            if (globalIndex < pagerState.pageCount) {
                                                pagerState.scrollToPage(globalIndex)
                                            }
                                        }
                                    }
                                }
                                scope.launch { drawerState.close() }
                            },
                            colors = NavigationDrawerItemDefaults.colors(
                                unselectedTextColor = Color(uiText),
                                unselectedContainerColor = Color.Transparent
                            )
                        )
                    }
                }
            }
        }
    ) {
        Scaffold(
            containerColor = Color(bookBg)
        ) { padding ->
            Box(modifier = Modifier
                .fillMaxSize()
                .background(Color(bookBg))
            ) {
                if (file == null || !file.exists()) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(stringResource(R.string.error_file_not_found), color = Color(bookText))
                    }
                } else {
                    key(book?.id) {
                        when (format) {
                            "pdf" -> {
                                if (isReaderLoading) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color(bookText))
                                    }
                                } else if (error != null) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(error, color = Color(bookText))
                                    }
                                } else if (viewModel.pdfNavigatorFactory != null) {
                                    val pub = viewModel.currentPublication!!
                                    val containerId = remember(book?.id) { View.generateViewId() }

                                    DisposableEffect(book?.id) {
                                        onDispose {
                                            val activity = context as? FragmentActivity
                                            activity?.supportFragmentManager?.findFragmentById(containerId)?.let {
                                                activity.supportFragmentManager.beginTransaction()
                                                    .remove(it)
                                                    .commitAllowingStateLoss()
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.fillMaxSize()) {
                                        var lastScrollMode by remember { mutableStateOf(viewModel.readerScrollMode) }
                                        AndroidView(
                                            factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
                                            modifier = Modifier.fillMaxSize(),
                                            update = { _ ->
                                                val activity = context as? FragmentActivity
                                                if (activity != null && viewModel.pdfNavigatorFactory != null) {
                                                    val existing = activity.supportFragmentManager.findFragmentById(containerId)
                                                    
                                                    // If scroll mode changed, we MUST recreate the fragment
                                                    val forceRecreate = lastScrollMode != viewModel.readerScrollMode
                                                    if (forceRecreate) {
                                                        lastScrollMode = viewModel.readerScrollMode
                                                        existing?.let { 
                                                            activity.supportFragmentManager.beginTransaction().remove(it).commitNow() 
                                                        }
                                                    }

                                                    if (existing == null || forceRecreate) {
                                                        val savedLocator = viewModel.getLastLocator(book?.id ?: -1)
                                                        val initialLocator = savedLocator ?: pub.locatorFromLink(pub.readingOrder.first())
                                                        
                                                        // Ensure isRestoring is true before setting up the navigator to prevent immediate saves
                                                        isRestoring = true
                                                        
                                                        viewModel.currentProgression = initialLocator?.locations?.totalProgression

                                                        val initialPreferences = PdfiumPreferences(
                                                            scrollAxis = if (viewModel.readerScrollMode == "Horizontal") Axis.HORIZONTAL else Axis.VERTICAL,
                                                            fit = Fit.CONTAIN,
                                                            readingProgression = ReadingProgression.LTR
                                                        )

                                                        @Suppress("UNCHECKED_CAST")
                                                        val factory = viewModel.pdfNavigatorFactory as PdfNavigatorFactory<*, PdfiumPreferences, *>
                                                        
                                                        val navigatorFactory = factory.createFragmentFactory(
                                                            initialLocator = initialLocator,
                                                            initialPreferences = initialPreferences,
                                                            listener = object : PdfNavigatorFragment.Listener {}
                                                        )
                                                        val navigator = navigatorFactory.instantiate(activity.classLoader, PdfNavigatorFragment::class.java.name) as PdfNavigatorFragment<*, *>
                                                        
                                                        scope.launch {
                                                            navigator.currentLocator.collect { locator ->
                                                                if (isRestoring) return@collect
                                                                
                                                                viewModel.currentPageIndex = locator.locations.position?.let { it - 1 } ?: 0
                                                                val totalProgression = locator.locations.totalProgression
                                                                if (totalProgression != null) {
                                                                    viewModel.currentProgression = totalProgression
                                                                    viewModel.saveReadingProgress(locator)
                                                                } else {
                                                                    val fallback = viewModel.calculateFallbackProgress(viewModel.currentPublication, locator)
                                                                    viewModel.currentProgression = fallback
                                                                    viewModel.saveReadingProgress(locator, fallback)
                                                                }
                                                            }
                                                        }

                                                        navigator.addInputListener(object : InputListener {
                                                            override fun onTap(event: TapEvent): Boolean {
                                                                showControls = !showControls
                                                                return true
                                                            }
                                                        })
                                                        
                                                        activity.supportFragmentManager.beginTransaction()
                                                            .replace(containerId, navigator)
                                                            .commitNow()
                                                        currentNavigator = navigator
                                                    } else if (existing is VisualNavigator) {
                                                        currentNavigator = existing
                                                        if (existing is PdfNavigatorFragment<*, *>) {
                                                            @Suppress("UNCHECKED_CAST")
                                                            val pdfFrag = existing as PdfNavigatorFragment<*, PdfiumPreferences>
                                                            val newPrefs = PdfiumPreferences(
                                                                scrollAxis = if (viewModel.readerScrollMode == "Horizontal") Axis.HORIZONTAL else Axis.VERTICAL,
                                                                fit = Fit.CONTAIN,
                                                                readingProgression = ReadingProgression.LTR
                                                            )
                                                            pdfFrag.submitPreferences(newPrefs)
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    }
                                } else {
                                    // Fallback logic
                                    val pageScales = remember(book?.id) { mutableStateMapOf<Int, Float>() }
                                    val isCurrentPageZoomed = (pageScales[pagerState.currentPage] ?: 1f) > 1f

                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.fillMaxSize().padding(padding),
                                        beyondViewportPageCount = 1,
                                        pageSpacing = 0.dp,
                                        userScrollEnabled = !isCurrentPageZoomed
                                    ) { pageIndex ->
                                        var scale by remember { mutableFloatStateOf(1f) }
                                        var offset by remember { mutableStateOf(Offset.Zero) }
                                        LaunchedEffect(scale) { pageScales[pageIndex] = scale }

                                        val state = rememberTransformableState { zoomChange, offsetChange, _ ->
                                            scale = (scale * zoomChange).coerceIn(1f, 5f)
                                            if (scale > 1f) {
                                                offset += offsetChange
                                            } else {
                                                offset = Offset.Zero
                                            }
                                        }

                                        Box(
                                            modifier = Modifier
                                                .fillMaxSize()
                                                .combinedClickable(
                                                    interactionSource = remember { MutableInteractionSource() },
                                                    indication = null,
                                                    onClick = { showControls = !showControls },
                                                    onDoubleClick = {
                                                        if (scale > 1f) {
                                                            scale = 1f
                                                            offset = Offset.Zero
                                                        } else {
                                                            scale = 2.5f
                                                        }
                                                    }
                                                )
                                                .clip(RectangleShape)
                                                .transformable(state = state)
                                                .graphicsLayer {
                                                    scaleX = scale
                                                    scaleY = scale
                                                    translationX = offset.x
                                                    translationY = offset.y
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            AndroidView(
                                                factory = { ctx ->
                                                    android.widget.ImageView(ctx).apply {
                                                        layoutParams = ViewGroup.LayoutParams(
                                                            ViewGroup.LayoutParams.MATCH_PARENT,
                                                            ViewGroup.LayoutParams.MATCH_PARENT
                                                        )
                                                        scaleType = android.widget.ImageView.ScaleType.FIT_CENTER
                                                    }
                                                },
                                                update = { imageView ->
                                                    scope.launch(Dispatchers.IO) {
                                                        try {
                                                            val pfd = android.os.ParcelFileDescriptor.open(file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)
                                                            val renderer = android.graphics.pdf.PdfRenderer(pfd)
                                                            if (pageIndex < renderer.pageCount) {
                                                                val page = renderer.openPage(pageIndex)
                                                                val renderScale = 3.0f
                                                                val width = (page.width * renderScale).toInt()
                                                                val height = (page.height * renderScale).toInt()
                                                                val bitmap = createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
                                                                val canvas = android.graphics.Canvas(bitmap)
                                                                canvas.drawColor(android.graphics.Color.WHITE)
                                                                page.render(bitmap, null, null, android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                                                withContext(Dispatchers.Main) { imageView.setImageBitmap(bitmap) }
                                                                page.close()
                                                            }
                                                            renderer.close()
                                                            pfd.close()
                                                        } catch (e: Exception) {
                                                            Log.e("ReaderScreen", "Error rendering PDF page", e)
                                                        }
                                                    }
                                                },
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        }
                                    }
                                }
                            }
                            "epub" -> {
                                if (isReaderLoading) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color(bookText))
                                    }
                                } else if (error != null) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(error, color = Color(bookText))
                                    }
                                } else if (viewModel.currentPublication != null) {
                                    val pub = viewModel.currentPublication!!
                                    val factory = viewModel.epubNavigatorFactory ?: return@Box
                                    val containerId = remember(book?.id) { View.generateViewId() }

                                    DisposableEffect(book?.id) {
                                        onDispose {
                                            val activity = context as? FragmentActivity
                                            activity?.supportFragmentManager?.findFragmentById(containerId)?.let {
                                                activity.supportFragmentManager.beginTransaction()
                                                    .remove(it)
                                                    .commitAllowingStateLoss()
                                            }
                                        }
                                    }

                                    Box(modifier = Modifier.fillMaxSize()) {
                                        AndroidView(
                                            factory = { ctx -> FragmentContainerView(ctx).apply { id = containerId } },
                                            modifier = Modifier.fillMaxSize(),
                                            update = { _ ->
                                                val activity = context as? FragmentActivity
                                                if (activity != null) {
                                                    val existing = activity.supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment
                                                    if (existing == null) {
                                                        val savedLocator = viewModel.getLastLocator(book?.id ?: -1)
                                                        val initialLocator = savedLocator ?: pub.locatorFromLink(pub.readingOrder.first())
                                                        
                                                        // Ensure isRestoring is true before setting up the navigator to prevent immediate saves
                                                        isRestoring = true
                                                        
                                                        val initialProgression = initialLocator?.locations?.totalProgression
                                                        if (initialProgression != null) {
                                                            viewModel.currentProgression = initialProgression
                                                        } else if (initialLocator != null) {
                                                            viewModel.currentProgression = viewModel.calculateFallbackProgress(pub, initialLocator)
                                                        }

                                                        val initialPreferences = EpubPreferences(
                                                            scroll = false,
                                                            columnCount = ColumnCount.AUTO,
                                                            fontSize = viewModel.readerFontSize.toDouble(),
                                                            theme = when (readerTheme) {
                                                                "Sepia" -> Theme.SEPIA
                                                                "Dark" -> Theme.DARK
                                                                "Light" -> Theme.LIGHT
                                                                "System" -> if (isSystemDark) Theme.DARK else Theme.LIGHT
                                                                else -> Theme.LIGHT
                                                            },
                                                            readingProgression = ReadingProgression.LTR
                                                        )

                                                        val paginationListener = object : EpubNavigatorFragment.PaginationListener {
                                                            override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                                                                if (isRestoring) return

                                                                viewModel.currentPageIndex = pageIndex
                                                                val totalProgression = locator.locations.totalProgression
                                                                if (totalProgression != null) {
                                                                    viewModel.currentProgression = totalProgression
                                                                    viewModel.saveReadingProgress(locator)
                                                                } else {
                                                                    val fallback = viewModel.calculateFallbackProgress(viewModel.currentPublication, locator)
                                                                    viewModel.currentProgression = fallback
                                                                    viewModel.saveReadingProgress(locator, fallback)
                                                                }
                                                            }
                                                        }

                                                        val listener = object : EpubNavigatorFragment.Listener {
                                                            override fun onExternalLinkActivated(url: AbsoluteUrl) {}
                                                        }

                                                        val navigatorFactory = factory.createFragmentFactory(
                                                            initialLocator = initialLocator,
                                                            initialPreferences = initialPreferences,
                                                            listener = listener,
                                                            paginationListener = paginationListener
                                                        )
                                                        val navigator = navigatorFactory.instantiate(activity.classLoader, EpubNavigatorFragment::class.java.name) as EpubNavigatorFragment
                                                        navigator.addInputListener(object : InputListener {
                                                            override fun onTap(event: TapEvent): Boolean {
                                                                showControls = !showControls
                                                                return true
                                                            }
                                                        })
                                                        activity.supportFragmentManager.beginTransaction()
                                                            .replace(containerId, navigator)
                                                            .commitNow()
                                                        currentNavigator = navigator
                                                    } else {
                                                        currentNavigator = existing
                                                        val newPrefs = EpubPreferences(
                                                            scroll = false,
                                                            columnCount = ColumnCount.AUTO,
                                                            fontSize = viewModel.readerFontSize.toDouble(),
                                                            theme = when (readerTheme) {
                                                                "Sepia" -> Theme.SEPIA
                                                                "Dark" -> Theme.DARK
                                                                "Light" -> Theme.LIGHT
                                                                "System" -> if (isSystemDark) Theme.DARK else Theme.LIGHT
                                                                else -> Theme.LIGHT
                                                            },
                                                            readingProgression = ReadingProgression.LTR
                                                        )
                                                        existing.submitPreferences(newPrefs)
                                                    }
                                                }
                                            }
                                        )
                                    }
                                } else if (viewModel.readerPages.isNotEmpty()) {
                                    Box(modifier = Modifier
                                        .fillMaxSize()
                                        .padding(padding)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { showControls = !showControls }
                                    ) {
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.fillMaxSize(),
                                            beyondViewportPageCount = 1,
                                            pageSpacing = 0.dp,
                                            userScrollEnabled = true
                                        ) { globalIndex ->
                                            val (chapterIndex, pageInChapter) = viewModel.getChapterAndPage(globalIndex)
                                            val page = pages.getOrNull(chapterIndex)
                                            if (page != null) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    AndroidView(
                                                        factory = { ctx ->
                                                            WebView(ctx).apply {
                                                                layoutParams = ViewGroup.LayoutParams(
                                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                                )
                                                                setBackgroundColor(bookBg)
                                                                isVerticalScrollBarEnabled = false
                                                                isHorizontalScrollBarEnabled = false
                                                                addJavascriptInterface(object {
                                                                    @Suppress("unused")
                                                                    @android.webkit.JavascriptInterface
                                                                    fun onPageCountReady(count: Int) {
                                                                        scope.launch(Dispatchers.Main) {
                                                                            val adjustedCount = maxOf(1, count)
                                                                            if (viewModel.chapterPageCounts[chapterIndex] != adjustedCount) {
                                                                                viewModel.updateChapterPageCount(chapterIndex, adjustedCount)
                                                                            }
                                                                        }
                                                                    }
                                                                }, "Android")

                                                                @SuppressLint("ClickableViewAccessibility")
                                                                setOnTouchListener { v, event ->
                                                                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                                                                        val duration = event.eventTime - event.downTime
                                                                        if (duration < 200) {
                                                                            v.performClick()
                                                                            showControls = !showControls
                                                                        }
                                                                    }
                                                                    false
                                                                }

                                                                webViewClient = object : WebViewClient() {
                                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                                        super.onPageFinished(view, url)
                                                                        view?.evaluateJavascript("scrollToPage($pageInChapter)", null)
                                                                    }

                                                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                                        val url = request?.url?.toString() ?: return false
                                                                        val href = if (url.startsWith("file:///android_asset/")) {
                                                                            url.substringAfter("file:///android_asset/")
                                                                        } else if (url.startsWith("file://android_asset/")) {
                                                                            url.substringAfter("file://android_asset/")
                                                                        } else {
                                                                            null
                                                                        }
                                                                        if (href != null) {
                                                                            val decodedHref = android.net.Uri.decode(href)
                                                                            val cleanHref = decodedHref.substringBefore("#").trimStart('/')
                                                                            val targetChapter = viewModel.findPageIndexForHref(cleanHref)
                                                                            if (targetChapter != -1) {
                                                                                scope.launch {
                                                                                    val targetGlobal = viewModel.getGlobalIndex(targetChapter, 0)
                                                                                    pagerState.scrollToPage(targetGlobal)
                                                                                }
                                                                                return true
                                                                            }
                                                                        }
                                                                        return true
                                                                    }

                                                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                                                        val url = request?.url?.toString() ?: return null
                                                                        if (url.startsWith("http") || url.startsWith("https")) return null
                                                                        val epub = viewModel.currentEpubBook
                                                                        val mobi = viewModel.currentMobiData
                                                                        var path = url.substringAfter("file://").trimStart('/')
                                                                        if (path.startsWith("android_asset/")) {
                                                                            path = path.substring("android_asset/".length)
                                                                        }
                                                                        path = android.net.Uri.decode(path).substringBefore("?").substringBefore("#")
                                                                        val fileName = path.substringAfterLast("/")

                                                                        if (path.contains("recindex=")) {
                                                                            val indexStr = path.substringAfter("recindex=").takeWhile { it.isDigit() }
                                                                            val index = indexStr.toIntOrNull()
                                                                            if (index != null && mobi != null) {
                                                                                val imgData = mobi.images[index]
                                                                                if (imgData != null) {
                                                                                    return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(imgData))
                                                                                }
                                                                            }
                                                                        }

                                                                        if (epub != null) {
                                                                            var res = epub.resources?.getByHref(path)
                                                                            if (res == null && path.contains("/")) {
                                                                                res = epub.resources?.getByHref(path.substringAfter("/"))
                                                                            }
                                                                            if (res == null && fileName.isNotEmpty()) {
                                                                                res = epub.resources?.all?.find { 
                                                                                    val href = it.href.trimStart('/')
                                                                                    href == fileName || href.endsWith("/$fileName")
                                                                                }
                                                                            }
                                                                            if (res != null) {
                                                                                val mimeType = res.mediaType?.name ?: "image/jpeg"
                                                                                return WebResourceResponse(mimeType, null, ByteArrayInputStream(res.data))
                                                                            }
                                                                        }
                                                                        return super.shouldInterceptRequest(view, request)
                                                                    }
                                                                }

                                                                @SuppressLint("SetJavaScriptEnabled")
                                                                settings.apply {
                                                                    javaScriptEnabled = true
                                                                    domStorageEnabled = true
                                                                    defaultTextEncodingName = "utf-8"
                                                                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                                                    textZoom = 100
                                                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                                    allowFileAccess = true
                                                                    allowContentAccess = true
                                                                }
                                                            }
                                                        },
                                                        update = { view ->
                                                            val basePath = page.basePath ?: ""
                                                            val baseUrl = "file:///android_asset/${if (basePath.isEmpty()) "" else "$basePath/"}"
                                                            val currentTag = "${baseUrl}|${page.content.hashCode()}"
                                                            if (view.tag != currentTag) {
                                                                view.loadDataWithBaseURL(baseUrl, page.content, "text/html", "UTF-8", null)
                                                                view.tag = currentTag
                                                            } else {
                                                                view.evaluateJavascript("scrollToPage($pageInChapter)", null)
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text("Failed to load EPUB", color = Color(bookText))
                                    }
                                }
                            }
                            "azw3", "mobi" -> {
                                if (isReaderLoading) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        CircularProgressIndicator(color = Color(bookText))
                                    }
                                } else if (error != null) {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(error, color = Color(bookText))
                                    }
                                } else {
                                    Box(modifier = Modifier
                                        .fillMaxSize()
                                        .padding(padding)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) { showControls = !showControls }
                                    ) {
                                        HorizontalPager(
                                            state = pagerState,
                                            modifier = Modifier.fillMaxSize(),
                                            beyondViewportPageCount = 1,
                                            pageSpacing = 0.dp,
                                            userScrollEnabled = true
                                        ) { globalIndex ->
                                            val (chapterIndex, pageInChapter) = viewModel.getChapterAndPage(globalIndex)
                                            val page = pages.getOrNull(chapterIndex)
                                            if (page != null) {
                                                Box(modifier = Modifier.fillMaxSize()) {
                                                    AndroidView(
                                                        factory = { ctx ->
                                                            WebView(ctx).apply {
                                                                layoutParams = ViewGroup.LayoutParams(
                                                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                                                    ViewGroup.LayoutParams.MATCH_PARENT
                                                                )
                                                                setBackgroundColor(bookBg)
                                                                isVerticalScrollBarEnabled = false
                                                                isHorizontalScrollBarEnabled = false
                                                                addJavascriptInterface(object {
                                                                    @Suppress("unused")
                                                                    @android.webkit.JavascriptInterface
                                                                    fun onPageCountReady(count: Int) {
                                                                        scope.launch(Dispatchers.Main) {
                                                                            val adjustedCount = maxOf(1, count)
                                                                            if (viewModel.chapterPageCounts[chapterIndex] != adjustedCount) {
                                                                                viewModel.updateChapterPageCount(chapterIndex, adjustedCount)
                                                                            }
                                                                        }
                                                                    }
                                                                }, "Android")

                                                                @SuppressLint("ClickableViewAccessibility")
                                                                setOnTouchListener { v, event ->
                                                                    if (event.action == android.view.MotionEvent.ACTION_UP) {
                                                                        val duration = event.eventTime - event.downTime
                                                                        if (duration < 200) {
                                                                            v.performClick()
                                                                            showControls = !showControls
                                                                        }
                                                                    }
                                                                    false
                                                                }

                                                                webViewClient = object : WebViewClient() {
                                                                    override fun onPageFinished(view: WebView?, url: String?) {
                                                                        super.onPageFinished(view, url)
                                                                        view?.evaluateJavascript("scrollToPage($pageInChapter)", null)
                                                                    }

                                                                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                                                        val url = request?.url?.toString() ?: return false
                                                                        val href = if (url.startsWith("file:///android_asset/")) {
                                                                            url.substringAfter("file:///android_asset/")
                                                                        } else if (url.startsWith("file://android_asset/")) {
                                                                            url.substringAfter("file://android_asset/")
                                                                        } else {
                                                                            null
                                                                        }
                                                                        if (href != null) {
                                                                            val decodedHref = android.net.Uri.decode(href)
                                                                            val cleanHref = decodedHref.substringBefore("#").trimStart('/')
                                                                            val targetChapter = viewModel.findPageIndexForHref(cleanHref)
                                                                            if (targetChapter != -1) {
                                                                                scope.launch {
                                                                                    val targetGlobal = viewModel.getGlobalIndex(targetChapter, 0)
                                                                                    pagerState.scrollToPage(targetGlobal)
                                                                                }
                                                                                return true
                                                                            }
                                                                        }
                                                                        return true
                                                                    }

                                                                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                                                        val url = request?.url?.toString() ?: return null
                                                                        if (url.startsWith("http") || url.startsWith("https")) return null
                                                                        val epub = viewModel.currentEpubBook
                                                                        val mobi = viewModel.currentMobiData
                                                                        var path = url.substringAfter("file://").trimStart('/')
                                                                        if (path.startsWith("android_asset/")) {
                                                                            path = path.substring("android_asset/".length)
                                                                        }
                                                                        path = android.net.Uri.decode(path).substringBefore("?").substringBefore("#")
                                                                        val fileName = path.substringAfterLast("/")

                                                                        if (path.contains("recindex=")) {
                                                                            val indexStr = path.substringAfter("recindex=").takeWhile { it.isDigit() }
                                                                            val index = indexStr.toIntOrNull()
                                                                            if (index != null && mobi != null) {
                                                                                val imgData = mobi.images[index]
                                                                                if (imgData != null) {
                                                                                    return WebResourceResponse("image/jpeg", null, ByteArrayInputStream(imgData))
                                                                                }
                                                                            }
                                                                        }

                                                                        if (epub != null) {
                                                                            var res = epub.resources?.getByHref(path)
                                                                            if (res == null && path.contains("/")) {
                                                                                res = epub.resources?.getByHref(path.substringAfter("/"))
                                                                            }
                                                                            if (res == null && fileName.isNotEmpty()) {
                                                                                res = epub.resources?.all?.find { 
                                                                                    val href = it.href.trimStart('/')
                                                                                    href == fileName || href.endsWith("/$fileName")
                                                                                }
                                                                            }
                                                                            if (res != null) {
                                                                                val mimeType = res.mediaType?.name ?: "image/jpeg"
                                                                                return WebResourceResponse(mimeType, null, ByteArrayInputStream(res.data))
                                                                            }
                                                                        }
                                                                        return super.shouldInterceptRequest(view, request)
                                                                    }
                                                                }

                                                                @SuppressLint("SetJavaScriptEnabled")
                                                                settings.apply {
                                                                    javaScriptEnabled = true
                                                                    domStorageEnabled = true
                                                                    defaultTextEncodingName = "utf-8"
                                                                    layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                                                    textZoom = 100
                                                                    mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                                                    allowFileAccess = true
                                                                    allowContentAccess = true
                                                                }
                                                            }
                                                        },
                                                        update = { view ->
                                                            val basePath = page.basePath ?: ""
                                                            val baseUrl = "file:///android_asset/${if (basePath.isEmpty()) "" else "$basePath/"}"
                                                            val currentTag = "${baseUrl}|${page.content.hashCode()}"
                                                            if (view.tag != currentTag) {
                                                                view.loadDataWithBaseURL(baseUrl, page.content, "text/html", "UTF-8", null)
                                                                view.tag = currentTag
                                                            } else {
                                                                view.evaluateJavascript("scrollToPage($pageInChapter)", null)
                                                            }
                                                        },
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                            else -> {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Text(stringResource(R.string.unsupported_format, format), color = Color(bookText))
                                }
                            }
                        }
                    }
                }

                // OVERLAY CONTROLS
                AnimatedVisibility(
                    visible = showControls,
                    enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
                    exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
                    modifier = Modifier.align(Alignment.TopCenter)
                ) {
                    TopAppBar(
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = Color(uiBg).copy(alpha = 0.95f),
                            titleContentColor = Color(uiText),
                            navigationIconContentColor = Color(uiText),
                            actionIconContentColor = Color(uiText)
                        ),
                        title = {
                            Text(book?.title ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, maxLines = 1)
                        },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = stringResource(R.string.toc_header))
                            }
                            if (format == "epub" || format == "pdf") {
                                IconButton(onClick = {
                                    currentNavigator?.currentLocator?.value?.let { locator ->
                                        if (viewModel.bookmarks.any { it.locations.totalProgression == locator.locations.totalProgression }) {
                                            viewModel.bookmarks.find { it.locations.totalProgression == locator.locations.totalProgression }?.let {
                                                viewModel.removeBookmark(it)
                                            }
                                        } else {
                                            viewModel.addBookmark(locator)
                                        }
                                    }
                                }) {
                                    val isBookmarked = currentNavigator?.currentLocator?.collectAsState(null)?.value?.let { locator ->
                                        viewModel.bookmarks.any { it.locations.totalProgression == locator.locations.totalProgression }
                                    } ?: false
                                    Icon(
                                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        contentDescription = stringResource(R.string.bookmark_label)
                                    )
                                }
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.settings_title))
                            }
                        }
                    )
                }

                // Page counter overlay
                AnimatedVisibility(
                    visible = showControls,
                    enter = fadeIn(),
                    exit = fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp)
                ) {
                    Surface(
                        color = Color(uiBg).copy(alpha = 0.9f),
                        shape = MaterialTheme.shapes.medium,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(uiText).copy(alpha = 0.1f)),
                        shadowElevation = 4.dp
                    ) {
                        val currentBookFormat = book?.format?.lowercase() ?: ""
                        
                        val displayProgress = when {
                            currentNavigator != null -> viewModel.currentProgression ?: 0.0
                            viewModel.totalPagesCount > 1 -> (pagerState.currentPage.toDouble() / (viewModel.totalPagesCount - 1).toDouble())
                            else -> 0.0
                        }

                        Text(
                            text = if (currentBookFormat == "epub" || currentBookFormat == "pdf") 
                                stringResource(R.string.reader_progress_percent, (displayProgress * 100).toInt().coerceIn(0, 100))
                                else stringResource(R.string.reader_page_number, pagerState.currentPage + 1, viewModel.totalPagesCount),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            style = MaterialTheme.typography.labelLarge,
                            color = Color(uiText)
                        )
                    }
                }
            }
        }

        if (showSettings) {
            AlertDialog(
                onDismissRequest = { showSettings = false },
                title = { Text(stringResource(R.string.reader_settings_title)) },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(stringResource(R.string.font_size_label, viewModel.readerFontSize), style = MaterialTheme.typography.bodyMedium)
                        Row(
                            modifier = Modifier.padding(top = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Button(
                                onClick = { viewModel.updateReaderFontSize(-1) },
                                modifier = Modifier.weight(1f)
                            ) { Text("-") }
                            Spacer(Modifier.width(16.dp))
                            Button(
                                onClick = { viewModel.updateReaderFontSize(1) },
                                modifier = Modifier.weight(1f)
                            ) { Text("+") }
                        }

                        val fmt = book?.format?.lowercase() ?: ""
                        if (fmt == "pdf") {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.scroll_mode_label), style = MaterialTheme.typography.bodyMedium)
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = viewModel.readerScrollMode == "Horizontal",
                                    onClick = { viewModel.updateReaderScrollMode("Horizontal") },
                                    label = { Text(stringResource(R.string.horizontal_label), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                                )
                                FilterChip(
                                    modifier = Modifier.weight(1f),
                                    selected = viewModel.readerScrollMode == "Vertical",
                                    onClick = { viewModel.updateReaderScrollMode("Vertical") },
                                    label = { Text(stringResource(R.string.vertical_label), modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                                )
                            }
                        }

                        if (fmt != "pdf") {
                            Spacer(Modifier.height(16.dp))
                            Text(stringResource(R.string.theme_label), style = MaterialTheme.typography.bodyMedium)
                            Column(modifier = Modifier.padding(top = 8.dp)) {
                                val themeOptions = listOf(
                                    "System" to stringResource(R.string.theme_system),
                                    "Light" to stringResource(R.string.theme_light),
                                    "Sepia" to stringResource(R.string.theme_sepia),
                                    "Dark" to stringResource(R.string.theme_dark)
                                )
                                themeOptions.chunked(2).forEach { rowOptions ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        rowOptions.forEach { (value, label) ->
                                            FilterChip(
                                                modifier = Modifier.weight(1f),
                                                selected = viewModel.readerTheme == value,
                                                onClick = { viewModel.updateReaderTheme(value) },
                                                label = { Text(label, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showSettings = false }) { Text(stringResource(R.string.close)) }
                }
            )
        }

        if (bookmarkToDelete != null) {
            AlertDialog(
                onDismissRequest = { bookmarkToDeleteState.value = null },
                title = { Text(stringResource(R.string.delete_bookmark_title)) },
                text = { Text(stringResource(R.string.delete_bookmark_confirm)) },
                confirmButton = {
                    TextButton(
                        onClick = {
                            viewModel.removeBookmark(bookmarkToDelete)
                            bookmarkToDeleteState.value = null
                        },
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                    ) { Text(stringResource(R.string.delete)) }
                },
                dismissButton = {
                    TextButton(onClick = { bookmarkToDeleteState.value = null }) { Text(stringResource(R.string.cancel)) }
                }
            )
        }
    }
}
