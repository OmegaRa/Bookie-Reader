package com.example.bookiereader

import android.annotation.SuppressLint
import android.os.Build
import android.os.ext.SdkExtensions
import android.util.Log
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.pdf.viewer.fragment.PdfViewerFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.domain.Book as EpubBook
import nl.siegmann.epublib.epub.EpubReader
import java.io.ByteArrayInputStream
import java.io.FileInputStream

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: BookViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val file = viewModel.currentBookFile
    val book = viewModel.currentBook
    val colorScheme = MaterialTheme.colorScheme
    val backgroundInt = colorScheme.background.toArgb()
    val textInt = colorScheme.onBackground.toArgb()

    Scaffold(
        containerColor = colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = colorScheme.background,
                    titleContentColor = colorScheme.onBackground,
                    navigationIconContentColor = colorScheme.onBackground
                ),
                title = { Text(book?.title ?: stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.back))
                    }
                }
            )
        }
    ) { padding ->
        if (file == null || !file.exists()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.error_file_not_found), color = colorScheme.onBackground)
            }
            return@Scaffold
        }

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
                        AndroidView(
                            factory = { ctx ->
                                val activity = ctx as? FragmentActivity
                                    ?: throw IllegalStateException("ReaderScreen must be used in a FragmentActivity")
                                
                                val fragmentManager = activity.supportFragmentManager
                                val containerId = android.view.View.generateViewId()
                                val frameLayout = android.widget.FrameLayout(ctx).apply {
                                    id = containerId
                                }

                                val pdfFragment = PdfViewerFragment()
                                fragmentManager.beginTransaction()
                                    .replace(containerId, pdfFragment)
                                    .commitNow()

                                try {
                                    pdfFragment.documentUri = android.net.Uri.fromFile(file)
                                } catch (e: Exception) {
                                    Log.e("ReaderScreen", "Error setting PDF URI", e)
                                }
                                
                                frameLayout
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    } else {
                        Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.unsupported_pdf_version), color = colorScheme.onBackground)
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(stringResource(R.string.pdf_not_supported), color = colorScheme.onBackground)
                            Text(stringResource(R.string.pdf_support_requirements), style = MaterialTheme.typography.bodySmall, color = colorScheme.onBackground.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            "epub" -> {
                var epubBook by remember { mutableStateOf<EpubBook?>(null) }
                var htmlContent by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                val drmError = stringResource(R.string.error_drm_protected)
                val emptyError = stringResource(R.string.error_epub_empty)
                val parseError = stringResource(R.string.error_parsing_epub)

                LaunchedEffect(file) {
                    try {
                        val reader = EpubReader()
                        val loadedEpub = reader.readEpub(FileInputStream(file))
                        
                        val hasDrm = loadedEpub.resources.all.any { 
                            it.href.contains("encryption.xml", ignoreCase = true) || 
                            it.href.contains("rights.xml", ignoreCase = true) ||
                            it.href.contains("manifest-library.xml", ignoreCase = true)
                        }
                        
                        if (hasDrm) {
                            error = drmError
                            return@LaunchedEffect
                        }

                        epubBook = loadedEpub
                        
                        val rawBodyBuilder = StringBuilder()
                        loadedEpub.spine.spineReferences.forEach { ref ->
                            try {
                                val content = ref.resource.reader.readText()
                                // Aggressively extract only the body content
                                val bodyRegex = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                                val match = bodyRegex.find(content)
                                val body = match?.groupValues?.get(1) ?: content
                                
                                // Clean up any remaining full tags that might have been nested
                                val cleanedBody = body.replace(Regex("<html[^>]*>", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("</html>", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                                    .replace(Regex("<body[^>]*>", RegexOption.IGNORE_CASE), "")
                                    .replace(Regex("</body>", RegexOption.IGNORE_CASE), "")
                                
                                rawBodyBuilder.append("<div class='spine-item'>$cleanedBody</div>\n")
                            } catch (e: Exception) {
                                Log.e("ReaderScreen", "Error reading spine item", e)
                            }
                        }
                        
                        val rawBody = rawBodyBuilder.toString()
                        if (rawBody.isBlank()) {
                            error = emptyError
                            return@LaunchedEffect
                        }

                        val bgHex = String.format("#%06X", 0xFFFFFF and backgroundInt)
                        val textHex = String.format("#%06X", 0xFFFFFF and textInt)
                        
                        htmlContent = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    html, body {
                                        margin: 0; padding: 0; 
                                        background-color: $bgHex !important; 
                                        color: $textHex !important;
                                        font-family: 'Roboto', sans-serif;
                                    }
                                    #content-layer {
                                        box-sizing: border-box;
                                        padding: 20px 16px;
                                        max-width: 100%;
                                    }
                                    .spine-item { margin-bottom: 0; }
                                    p, div, span, h1, h2, h3, h4, h5, h6 { 
                                        color: $textHex !important; 
                                        background-color: transparent !important;
                                        font-size: 1.15em;
                                        line-height: 1.7;
                                        text-align: justify;
                                        overflow-wrap: break-word;
                                    }
                                    * { 
                                        max-width: 100% !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                    }
                                    img { 
                                        max-width: 100% !important; 
                                        height: auto !important; 
                                        display: block; 
                                        margin: 15px auto; 
                                    }
                                </style>
                            </head>
                            <body>
                                <div id="content-layer">
                                    $rawBody
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                    } catch (e: Exception) {
                        Log.e("ReaderScreen", "Error parsing EPUB", e)
                        error = parseError
                    }
                }

                if (error != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.error_prefix, error!!), color = MaterialTheme.colorScheme.error)
                    }
                } else if (htmlContent != null && epubBook != null) {
                    key(file.absolutePath) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    setBackgroundColor(backgroundInt)
                                    webViewClient = object : WebViewClient() {
                                        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                                            val url = request?.url?.toString() ?: return null
                                            if (!url.startsWith("http") && !url.startsWith("https")) {
                                                val href = url.substringAfter("file:///android_asset/", url)
                                                val resource = epubBook?.resources?.getByHref(href)
                                                    ?: epubBook?.resources?.all?.find { it.href.endsWith(href.substringAfterLast("/")) }
                                                if (resource != null) {
                                                    return WebResourceResponse(resource.mediaType.toString(), "UTF-8", ByteArrayInputStream(resource.data))
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
                                        loadWithOverviewMode = false
                                        useWideViewPort = false
                                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                        textZoom = 100
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        setSupportZoom(false)
                                        builtInZoomControls = false
                                    }
                                    loadDataWithBaseURL("file:///android_asset/", htmlContent!!, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colorScheme.primary)
                    }
                }
            }
            "azw3", "mobi" -> {
                var htmlContent by remember { mutableStateOf<String?>(null) }
                var error by remember { mutableStateOf<String?>(null) }
                val parseError = stringResource(R.string.error_parsing_mobi)

                LaunchedEffect(file) {
                    try {
                        val mobiData = withContext(Dispatchers.IO) {
                            MobiExtractor.extractText(context, file)
                        }
                        if (mobiData.text.startsWith("Error:")) {
                            error = mobiData.text
                            return@LaunchedEffect
                        }

                        val bgHex = String.format("#%06X", 0xFFFFFF and backgroundInt)
                        val textHex = String.format("#%06X", 0xFFFFFF and textInt)
                        
                        // Extract body content from MOBI if it's HTML-based
                        val bodyRegex = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                        val match = bodyRegex.find(mobiData.text)
                        val innerBody = match?.groupValues?.get(1) ?: mobiData.text
                        
                        val cleanedBody = innerBody.replace(Regex("<html[^>]*>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("</html>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("<head[^>]*>.*?</head>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
                            .replace(Regex("<body[^>]*>", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("</body>", RegexOption.IGNORE_CASE), "")

                        val processedText = if (!mobiData.text.contains("<p", ignoreCase = true)) {
                            cleanedBody.replace("\n", "<br/>")
                        } else {
                            cleanedBody
                        }

                        htmlContent = """
                            <!DOCTYPE html>
                            <html>
                            <head>
                                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                                <style>
                                    html, body {
                                        margin: 0; padding: 0; 
                                        background-color: $bgHex !important; 
                                        color: $textHex !important;
                                        font-family: 'Roboto', sans-serif;
                                    }
                                    #content-layer {
                                        box-sizing: border-box;
                                        padding: 20px 16px;
                                        max-width: 100%;
                                    }
                                    p, div, span, h1, h2, h3, h4, h5, h6 { 
                                        color: $textHex !important; 
                                        background-color: transparent !important;
                                        font-size: 1.15em;
                                        line-height: 1.7;
                                        text-align: justify;
                                        overflow-wrap: break-word;
                                    }
                                    * { 
                                        max-width: 100% !important;
                                        visibility: visible !important;
                                        opacity: 1 !important;
                                    }
                                    img { 
                                        max-width: 100% !important; 
                                        height: auto !important; 
                                        display: block; 
                                        margin: 15px auto; 
                                    }
                                </style>
                            </head>
                            <body>
                                <div id="content-layer">
                                    $processedText
                                </div>
                            </body>
                            </html>
                        """.trimIndent()
                    } catch (e: Exception) {
                        Log.e("ReaderScreen", "Error parsing MOBI", e)
                        error = parseError
                    }
                }

                if (error != null) {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.error_prefix, error!!), color = MaterialTheme.colorScheme.error)
                    }
                } else if (htmlContent != null) {
                    key(file.absolutePath) {
                        AndroidView(
                            factory = { context ->
                                WebView(context).apply {
                                    setBackgroundColor(backgroundInt)
                                    @SuppressLint("SetJavaScriptEnabled")
                                    settings.apply {
                                        javaScriptEnabled = true
                                        domStorageEnabled = true
                                        defaultTextEncodingName = "utf-8"
                                        loadWithOverviewMode = false
                                        useWideViewPort = false
                                        layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                                        textZoom = 100
                                        mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                        setSupportZoom(false)
                                        builtInZoomControls = false
                                    }
                                    loadDataWithBaseURL(null, htmlContent!!, "text/html", "UTF-8", null)
                                }
                            },
                            modifier = Modifier.fillMaxSize().padding(padding)
                        )
                    }
                } else {
                    Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = colorScheme.primary)
                    }
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.unsupported_format, format), color = colorScheme.onBackground)
                }
            }
        }
    }
}
