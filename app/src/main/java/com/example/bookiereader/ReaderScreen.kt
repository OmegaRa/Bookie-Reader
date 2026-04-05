package com.example.bookiereader

import android.annotation.SuppressLint
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.pager.*
import androidx.compose.material.icons.Icons
import kotlinx.coroutines.delay
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.runtime.saveable.rememberSaveable
import android.view.View
import androidx.fragment.app.FragmentContainerView
import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.navigator.epub.EpubPreferences
import org.readium.r2.navigator.input.InputListener
import org.readium.r2.navigator.input.TapEvent
import org.readium.r2.shared.ExperimentalReadiumApi
import org.readium.r2.shared.util.AbsoluteUrl
import org.readium.r2.navigator.preferences.Theme
import org.readium.r2.shared.publication.Locator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.fragment.app.FragmentActivity
import androidx.pdf.viewer.fragment.PdfViewerFragment
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
    var currentNavigator by remember { mutableStateOf<EpubNavigatorFragment?>(null) }
    val format = book?.format?.lowercase() ?: ""
    
    // Use key(book?.id) to force reset pagerState when book changes.
    // Important: Use viewModel.totalPagesCount in the lambda so it stays reactive.
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
    var lastStableChapter by remember { mutableIntStateOf(0) }
    var lastStablePageInChapter by remember { mutableIntStateOf(0) }
    var isInitialLoad by remember { mutableStateOf(true) }

    // Update stable position when user finishes scrolling
    LaunchedEffect(pagerState.currentPage, pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            val (c, p) = viewModel.getChapterAndPage(pagerState.currentPage)
            lastStableChapter = c
            lastStablePageInChapter = p
            isInitialLoad = false
        }
    }

    // Adjust pager when page counts change to prevent jumping
    LaunchedEffect(viewModel.chapterPageCounts) {
        if (!isInitialLoad && viewModel.readerError == null && viewModel.readerPages.isNotEmpty()) {
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

    LaunchedEffect(file?.absolutePath, bookBg, bookText, viewModel.readerFontSize) {
        if (file != null && file.exists()) {
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
                        "Bookmarks",
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
                                    onLongClick = {
                                        bookmarkToDeleteState.value = locator
                                    }
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
                    "Table of Contents",
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
                                if (format == "epub") {
                                    val link = viewModel.currentPublication?.linkWithHref(org.readium.r2.shared.util.Url(item.href)!!)
                                    if (link != null) {
                                        val locator = viewModel.currentPublication?.locatorFromLink(link)
                                        if (locator != null) {
                                            currentNavigator?.go(locator, animated = true)
                                        }
                                    }
                                } else {
                                    Log.d("ReaderScreen", "ToC Clicked: '${item.title}' -> target page: ${item.pageIndex}")
                                    if (item.pageIndex != -1) {
                                        scope.launch {
                                            val globalIndex = viewModel.getGlobalIndex(item.pageIndex, 0)
                                            if (globalIndex < pagerState.pageCount) {
                                                pagerState.scrollToPage(globalIndex)
                                            } else {
                                                Log.e("ReaderScreen", "Global index out of bounds: $globalIndex >= ${pagerState.pageCount}")
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
                    val format = book?.format?.lowercase() ?: ""

                    when (format) {
                        "pdf" -> {
                            val isPdfSupported = remember {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                    SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13
                                } else {
                                    false
                                }
                            }

                            if (isPdfSupported) {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && SdkExtensions.getExtensionVersion(Build.VERSION_CODES.S) >= 13) {
                                    val containerId = remember { View.generateViewId() }
                                    var pdfFragment by remember { mutableStateOf<PdfViewerFragment?>(null) }

                                    AndroidView(
                                        factory = { ctx ->
                                            android.widget.FrameLayout(ctx).apply {
                                                id = containerId
                                            }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        update = { _ ->
                                            val activity = context as? FragmentActivity
                                            if (activity != null && pdfFragment == null) {
                                                val fragment = PdfViewerFragment()
                                                activity.supportFragmentManager.beginTransaction()
                                                    .replace(containerId, fragment)
                                                    .commitNow()
                                                pdfFragment = fragment
                                                fragment.documentUri = android.net.Uri.fromFile(file)
                                            }
                                        }
                                    )
                                } else {
                                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                        Text(stringResource(R.string.unsupported_pdf_version), color = Color(bookText))
                                    }
                                }
                            } else {
                                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(stringResource(R.string.pdf_not_supported), color = Color(bookText))
                                        Text(stringResource(R.string.pdf_support_requirements), style = MaterialTheme.typography.bodySmall, color = Color(bookText).copy(alpha = 0.6f))
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
                                val factory = viewModel.epubNavigatorFactory!!
                                val containerId = rememberSaveable { View.generateViewId() }
                                
                                Box(modifier = Modifier
                                    .fillMaxSize()
                                ) {
                                    AndroidView(
                                        factory = { ctx ->
                                            FragmentContainerView(ctx).apply { id = containerId }
                                        },
                                        modifier = Modifier.fillMaxSize(),
                                        update = { _ ->
                                            val activity = context as? FragmentActivity
                                            if (activity != null) {
                                                val existing = activity.supportFragmentManager.findFragmentById(containerId) as? EpubNavigatorFragment
                                                if (existing == null) {
                                                    val savedLocator = viewModel.getLastLocator(book?.id ?: -1)
                                                    val initialLocator = savedLocator ?: pub.locatorFromLink(pub.readingOrder.first())
                                                    viewModel.currentProgression = initialLocator?.locations?.totalProgression
                                                    
                                                    val initialPreferences = EpubPreferences(
                                                        scroll = false,
                                                        theme = when (readerTheme) {
                                                            "Sepia" -> Theme.SEPIA
                                                            "Dark" -> Theme.DARK
                                                            "Light" -> Theme.LIGHT
                                                            "System" -> if (isSystemDark) Theme.DARK else Theme.LIGHT
                                                            else -> Theme.LIGHT
                                                        }
                                                    )

                                                    val paginationListener = object : EpubNavigatorFragment.PaginationListener {
                                                        override fun onPageChanged(pageIndex: Int, totalPages: Int, locator: Locator) {
                                                            viewModel.currentPageIndex = pageIndex
                                                            viewModel.currentProgression = locator.locations.totalProgression
                                                            // Save progress (last read locator)
                                                            viewModel.saveReadingProgress(locator)
                                                        }
                                                    }

                                                    val listener = object : EpubNavigatorFragment.Listener {
                                                        override fun onExternalLinkActivated(url: AbsoluteUrl) {
                                                            // Handle external link
                                                        }
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
                                                    
                                                    // Dynamic update of preferences
                                                    val newPrefs = EpubPreferences(
                                                        scroll = false,
                                                        theme = when (readerTheme) {
                                                            "Sepia" -> Theme.SEPIA
                                                            "Dark" -> Theme.DARK
                                                            "Light" -> Theme.LIGHT
                                                            "System" -> if (isSystemDark) Theme.DARK else Theme.LIGHT
                                                            else -> Theme.LIGHT
                                                        }
                                                    )
                                                    existing.submitPreferences(newPrefs)
                                                }
                                            }
                                        }
                                    )
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
                                    ) {
                                        showControls = !showControls
                                    }
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
                        title = { Text(book?.title ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, maxLines = 1) },
                        navigationIcon = {
                            IconButton(onClick = onBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                            }
                        },
                        actions = {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.AutoMirrored.Filled.List, contentDescription = "Table of Contents")
                            }
                            if (format == "epub") {
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
                                    val isBookmarked = currentNavigator?.currentLocator?.collectAsState()?.value?.let { locator ->
                                        viewModel.bookmarks.any { it.locations.totalProgression == locator.locations.totalProgression }
                                    } ?: false
                                    Icon(
                                        if (isBookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                                        contentDescription = "Bookmark"
                                    )
                                }
                            }
                            IconButton(onClick = { showSettings = true }) {
                                Icon(Icons.Default.Settings, contentDescription = "Settings")
                            }
                        }
                    )
                }

                // Page counter overlay with fade animation
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
                                        Text(
                                            text = if (currentBookFormat == "epub") "Progress: ${((viewModel.currentProgression ?: 0.0) * 100).toInt()}%"
                                                   else "Page ${pagerState.currentPage + 1} of ${viewModel.totalPagesCount}",
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                                            style = MaterialTheme.typography.labelLarge,
                                            color = Color(uiText)
                                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        AlertDialog(
            onDismissRequest = { showSettings = false },
            title = { Text("Reader Settings") },
            text = {
                Column {
                    Text("Font Size: ${viewModel.readerFontSize}", style = MaterialTheme.typography.bodyMedium)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Button(onClick = { viewModel.updateReaderFontSize(-1) }) { Text("-") }
                        Spacer(Modifier.width(16.dp))
                        Button(onClick = { viewModel.updateReaderFontSize(1) }) { Text("+") }
                    }
                    
                    Spacer(Modifier.height(16.dp))
                    Text("Theme", style = MaterialTheme.typography.bodyMedium)
                    FlowRow(
                        modifier = Modifier.padding(top = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val themeOptions = listOf(
                            "System" to stringResource(R.string.theme_system),
                            "Light" to stringResource(R.string.theme_light),
                            "Sepia" to stringResource(R.string.theme_sepia),
                            "Dark" to stringResource(R.string.theme_dark)
                        )
                        themeOptions.forEach { (value, label) ->
                            FilterChip(
                                selected = viewModel.readerTheme == value,
                                onClick = { viewModel.updateReaderTheme(value) },
                                label = { Text(label) }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSettings = false }) { Text("Close") }
            }
        )
    }

    if (bookmarkToDelete != null) {
        AlertDialog(
            onDismissRequest = { bookmarkToDeleteState.value = null },
            title = { Text("Delete Bookmark") },
            text = { Text("Are you sure you want to delete this bookmark?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeBookmark(bookmarkToDelete)
                        bookmarkToDeleteState.value = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { bookmarkToDeleteState.value = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}
