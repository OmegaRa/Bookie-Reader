package com.example.bookiereader

import android.app.Application
import android.content.Context
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.core.content.edit
import com.example.bookiereader.data.Book
import com.example.bookiereader.data.LoginRequest
import com.example.bookiereader.data.local.AppDatabase
import com.example.bookiereader.data.local.LocalBookEntity
import com.example.bookiereader.network.ApiService
import com.example.bookiereader.network.GithubService
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.epub.EpubReader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.readium.r2.shared.publication.Link
import org.readium.r2.shared.publication.Locator
import org.readium.r2.shared.publication.Publication
import org.readium.r2.streamer.PublicationOpener
import org.readium.r2.streamer.parser.epub.EpubParser
import org.readium.r2.streamer.parser.pdf.PdfParser
import org.readium.r2.shared.util.asset.Asset
import org.readium.r2.shared.util.asset.AssetRetriever
import org.readium.r2.shared.util.http.DefaultHttpClient
import org.readium.r2.navigator.epub.EpubNavigatorFactory
import org.readium.r2.navigator.VisualNavigator
import org.readium.r2.navigator.pdf.PdfNavigatorFactory
import org.readium.adapter.pdfium.navigator.PdfiumEngineProvider
import org.readium.adapter.pdfium.document.PdfiumDocumentFactory
import com.github.barteksc.pdfviewer.PDFView
import org.readium.r2.shared.util.Try
import org.readium.r2.shared.util.Try.Success
import org.readium.r2.shared.util.Try.Failure
import org.readium.r2.shared.ExperimentalReadiumApi
import com.google.gson.reflect.TypeToken
import org.json.JSONObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.io.FileOutputStream

data class ReaderPage(val content: String, val basePath: String?)

data class TocItem(
    val title: String,
    val href: String,
    val level: Int = 0,
    val pageIndex: Int = -1,
    val locatorJson: String? = null
)

@OptIn(ExperimentalReadiumApi::class)
class BookViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("bookie_prefs", Context.MODE_PRIVATE)
    private val db = AppDatabase.getDatabase(application)
    private val localBookDao = db.localBookDao()

    // Software-level encryption to allow for Android Backup while keeping data non-plain-text
    private fun encrypt(value: String?): String? {
        if (value == null) return null
        return try {
            val bytes = value.toByteArray(Charsets.UTF_8)
            "enc:" + Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (_: Exception) {
            value
        }
    }

    private fun decrypt(value: String?): String? {
        if (value == null) return null
        if (!value.startsWith("enc:")) return value
        return try {
            val actualValue = value.substring(4)
            val bytes = Base64.decode(actualValue, Base64.DEFAULT)
            String(bytes, Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    fun saveCredentials(baseUrl: String, cookie: String) {
        prefs.edit {
            putString("base_url", encrypt(baseUrl))
            putString("session_cookie", encrypt(cookie))
        }
    }

    fun getSavedBaseUrl(): String? = decrypt(prefs.getString("base_url", null))
    fun getSavedSessionCookie(): String? = decrypt(prefs.getString("session_cookie", null))

    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    var localBooks by mutableStateOf<List<Book>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var baseUrl by mutableStateOf(getSavedBaseUrl() ?: "")
        private set

    var okHttpClient: OkHttpClient? by mutableStateOf(null)
        private set

    var sessionCacheBuster by mutableStateOf("")
        private set

    var currentBookFile by mutableStateOf<File?>(null)
    var currentBook by mutableStateOf<Book?>(null)
    
    var readerPages by mutableStateOf<List<ReaderPage>>(emptyList())
    var readerToc by mutableStateOf<List<TocItem>>(emptyList())
    var isReaderLoading by mutableStateOf(false)
    var readerError by mutableStateOf<String?>(null)
    var currentPageIndex by mutableIntStateOf(0)
    var currentProgression by mutableStateOf<Double?>(null)

    // New state for dynamic pagination - Use SnapshotStateMap for more granular updates
    val chapterPageCounts = mutableStateMapOf<Int, Int>()

    // Cache for total pages to avoid frequent recalculation
    private var _cachedTotalPages = derivedStateOf {
        if (readerPages.isEmpty()) 0
        else {
            var total = 0
            for (i in readerPages.indices) {
                total += chapterPageCounts[i] ?: 1
            }
            maxOf(1, total)
        }
    }

    val totalPagesCount: Int get() = _cachedTotalPages.value

    fun getChapterAndPage(globalIndex: Int): Pair<Int, Int> {
        if (readerPages.isEmpty()) {
            return 0 to 0
        }
        
        // Optimize: If we have many chapters, this linear search could be slow.
        // But for most books it's acceptable. We use the map directly.
        var count = 0
        for (i in readerPages.indices) {
            val pagesInChapter = chapterPageCounts[i] ?: 1
            if (globalIndex < count + pagesInChapter) {
                return i to (globalIndex - count)
            }
            count += pagesInChapter
        }
        return (readerPages.size - 1).coerceAtLeast(0) to 0
    }

    fun getGlobalIndex(chapterIndex: Int, pageInChapter: Int): Int {
        if (readerPages.isEmpty()) return 0
        val safeChapter = chapterIndex.coerceIn(0, readerPages.size - 1)
        var count = 0
        for (i in 0 until safeChapter) {
            count += chapterPageCounts[i] ?: 1
        }
        val pagesInTarget = chapterPageCounts[safeChapter] ?: 1
        return count + pageInChapter.coerceIn(0, pagesInTarget - 1)
    }

    fun updateChapterPageCount(chapterIndex: Int, count: Int) {
        if (chapterPageCounts[chapterIndex] != count) {
            chapterPageCounts[chapterIndex] = count
        }
    }
    
    // Settings state
    var readerFontSize by mutableFloatStateOf(prefs.getFloat("reader_font_size", 18f))
    var themeMode by mutableStateOf(prefs.getString("theme_mode", "System") ?: "System")
    var readerTheme by mutableStateOf(prefs.getString("reader_theme", "System") ?: "System")
    var readerScrollMode by mutableStateOf(prefs.getString("reader_scroll_mode", "Horizontal") ?: "Horizontal")
    var sortBy by mutableStateOf(prefs.getString("sort_by", "Author") ?: "Author")
    var searchQuery by mutableStateOf("")
    var selectedTag by mutableStateOf<String?>(null)

    // Update state
    var isUpdateAvailable by mutableStateOf(false)
        private set
    var updateUrl by mutableStateOf<String?>(null)
        private set

    val allTags: List<String>
        get() = (books + localBooks).flatMap { it.tags ?: emptyList() }.distinct().sorted()

    fun deleteSelectedBooks(bookIds: Set<Int>) {
        viewModelScope.launch(Dispatchers.IO) {
            bookIds.forEach { id ->
                val book = (books + localBooks).find { it.id == id }
                if (book != null) {
                    deleteBook(book)
                }
            }
        }
    }

    fun updateThemeMode(mode: String) {
        if (mode.isBlank()) return
        themeMode = mode
        prefs.edit { putString("theme_mode", mode) }
    }

    var currentEpubBook: nl.siegmann.epublib.domain.Book? = null
        private set
    var currentPublication: Publication? by mutableStateOf(null)
        private set
    var epubNavigatorFactory: EpubNavigatorFactory? by mutableStateOf(null)
        private set
    var pdfNavigatorFactory: Any? by mutableStateOf(null)
        private set
    var pdfNavigator: VisualNavigator? by mutableStateOf(null)
        private set

    private fun flattenToc(publication: Publication, links: List<Link>, level: Int = 0): List<TocItem> {
        val result = mutableListOf<TocItem>()
        for (link in links) {
            val locator = publication.locatorFromLink(link)
            result.add(TocItem(
                title = link.title ?: "Untitled",
                href = link.href.toString(),
                level = level,
                locatorJson = locator?.toJSON()?.toString()
            ))
            if (link.children.isNotEmpty()) {
                result.addAll(flattenToc(publication, link.children, level + 1))
            }
        }
        return result
    }

    // Bookmarks state
    var bookmarks by mutableStateOf<List<Locator>>(emptyList())
        private set

    fun addBookmark(locator: Locator) {
        if (!bookmarks.contains(locator)) {
            bookmarks = bookmarks + locator
            saveBookmarks()
        }
    }

    fun removeBookmark(locator: Locator) {
        bookmarks = bookmarks.filter { it != locator }
        saveBookmarks()
    }

    private fun saveProgressInternal(bookId: Int, progress: Float, locator: Locator?) {
        viewModelScope.launch(Dispatchers.IO) {
            localBookDao.updateProgress(bookId, progress)
            // Also update the in-memory list so the UI refreshes immediately
            withContext(Dispatchers.Main) {
                localBooks = localBooks.map { 
                    if (it.id == bookId) it.copy(progress = progress) else it 
                }
                books = books.map { 
                    if (it.id == bookId) it.copy(progress = progress) else it 
                }
                if (currentBook?.id == bookId) {
                    currentBook = currentBook?.copy(progress = progress)
                }
            }
            
            // For remote books, we'd eventually want to sync this to the server
            // For now, we'll store it in preferences as well
            prefs.edit { 
                putFloat("book_progress_$bookId", progress)
                locator?.let {
                    putString("last_locator_$bookId", it.toJSON().toString())
                }
            }
        }
    }

    fun saveReadingProgress(locator: Locator, fallbackProgress: Double? = null) {
        val bookId = currentBook?.id ?: return
        val progress = locator.locations.totalProgression ?: fallbackProgress ?: 0.0
        currentProgression = progress
        saveProgressInternal(bookId, progress.toFloat(), locator)
    }

    fun calculateFallbackProgress(publication: Publication?, locator: Locator): Double {
        if (publication == null) return 0.0
        val readingOrder = publication.readingOrder
        val currentHref = locator.href.toString().removePrefix("/")
        val currentIndex = readingOrder.indexOfFirst { 
            val linkHref = it.href.toString().removePrefix("/")
            linkHref == currentHref || linkHref.endsWith("/$currentHref") || currentHref.endsWith("/$linkHref")
        }.coerceAtLeast(0)
        val chapterProgress = locator.locations.progression ?: 0.0
        return (currentIndex + chapterProgress) / readingOrder.size.toDouble()
    }

    fun saveLegacyProgress(bookId: Int, progress: Double) {
        currentProgression = progress
        saveProgressInternal(bookId, progress.toFloat(), null)
    }

    fun getLegacyProgress(bookId: Int): Double {
        return prefs.getFloat("book_progress_$bookId", 0f).toDouble()
    }

    fun getLastLocator(bookId: Int): Locator? {
        val json = prefs.getString("last_locator_$bookId", null) ?: return null
        return try {
            Locator.fromJSON(JSONObject(json))
        } catch (_: Exception) {
            null
        }
    }

    private fun saveBookmarks() {
        val bookId = currentBook?.id ?: return
        val json = GsonBuilder().create().toJson(bookmarks.map { it.toJSON().toString() })
        prefs.edit {
            putString("bookmarks_$bookId", json)
        }
    }

    private fun loadBookmarks(bookId: Int) {
        val json = prefs.getString("bookmarks_$bookId", null)
        if (json != null) {
            try {
                val listType = object : TypeToken<List<String>>() {}.type
                val stringList: List<String> = GsonBuilder().create().fromJson(json, listType)
                bookmarks = stringList.mapNotNull { Locator.fromJSON(JSONObject(it)) }
            } catch (_: Exception) {
                Log.e("BookViewModel", "Error loading bookmarks")
                bookmarks = emptyList()
            }
        } else {
            bookmarks = emptyList()
        }
    }

    var currentMobiData: MobiData? = null
        private set
    private var preparationJob: Job? = null
    private var lastPreparedParams: Any? = null
    private var hrefToPageMap = mapOf<String, Int>()

    val filteredBooks: List<Book>
        get() {
            val all = (books + localBooks)
            val filtered = all.filter { book ->
                val matchesSearch = searchQuery.isEmpty() || 
                    book.title.contains(searchQuery, ignoreCase = true) ||
                    (book.author?.contains(searchQuery, ignoreCase = true) ?: false) ||
                    (book.series?.contains(searchQuery, ignoreCase = true) ?: false) ||
                    (book.tags?.any { it.contains(searchQuery, ignoreCase = true) } ?: false)
                
                val matchesTag = selectedTag == null || (book.tags?.contains(selectedTag) ?: false)
                
                matchesSearch && matchesTag
            }
            
            return when (sortBy) {
                "Title" -> filtered.sortedBy { it.title.lowercase() }
                "Author" -> filtered.sortedBy { it.author?.lowercase() ?: "" }
                "Series" -> filtered.sortedBy { "${it.series?.lowercase() ?: "zzz"}${it.seriesOrder ?: 0.0}" }
                "Tag" -> filtered.sortedWith(compareBy<Book> { it.tags?.firstOrNull()?.lowercase() ?: "zzz" }.thenBy { it.title.lowercase() })
                else -> filtered.sortedBy { it.author?.lowercase() ?: "" }
            }
        }

    init {
        loadLocalBooks()
        checkForUpdates()
        val savedUrl = getSavedBaseUrl()
        val savedSession = getSavedSessionCookie()
        if (!savedUrl.isNullOrBlank() && !savedSession.isNullOrBlank()) {
            try {
                setupClientAndFetch(savedUrl, savedSession)
            } catch (_: Exception) {
                Log.e("BookViewModel", "Failed to auto-connect")
                errorMessage = "Failed to reconnect"
            }
        }
    }

    private fun checkForUpdates() {
        viewModelScope.launch {
            try {
                val retrofit = Retrofit.Builder()
                    .baseUrl("https://api.github.com/")
                    .addConverterFactory(GsonConverterFactory.create())
                    .build()
                val service = retrofit.create(GithubService::class.java)
                val latestRelease = service.getLatestRelease()
                
                // GitHub "latest" releases can use tag_name or name for the version string
                // Depending on how the release was created. Some use "latest" as tag and "v1.1.4" as name.
                val latestVersion = (latestRelease.name ?: latestRelease.tag_name).removePrefix("v")
                
                if (isNewerVersion(BuildConfig.VERSION_NAME, latestVersion)) {
                    isUpdateAvailable = true
                    updateUrl = latestRelease.html_url
                }
            } catch (e: Exception) {
                Log.e("BookViewModel", "Failed to check for updates", e)
            }
        }
    }

    @Suppress("SameParameterValue")
    private fun isNewerVersion(current: String, latest: String): Boolean {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
        for (i in 0 until maxOf(currentParts.size, latestParts.size)) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (l > c) return true
            if (c > l) return false
        }
        return false
    }

    private fun loadLocalBooks() {
        viewModelScope.launch {
            val entities = localBookDao.getAllBooks()
            localBooks = entities.map { entity ->
                val savedProgress = prefs.getFloat("book_progress_${entity.id}", -1f)
                Book(
                    id = entity.id,
                    title = entity.title,
                    author = entity.author,
                    format = entity.format,
                    downloadUrl = entity.filePath,
                    coverUrl = null,
                    series = entity.series,
                    seriesOrder = entity.seriesOrder,
                    tags = entity.tags?.split(",")?.filter { it.isNotBlank() },
                    progress = if (savedProgress >= 0f) savedProgress else entity.progress
                )
            }
        }
    }

    fun refreshBooks() {
        val savedUrl = getSavedBaseUrl()
        val savedSession = getSavedSessionCookie()
        if (savedUrl != null && savedSession != null) {
            setupClientAndFetch(savedUrl, savedSession)
        }
    }

    private fun formatBaseUrl(url: String): String {
        var formatted = url.trim()
        if (formatted.isEmpty()) return ""
        
        if (!formatted.startsWith("http://") && !formatted.startsWith("https://")) {
            formatted = "https://$formatted"
        }
        
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }
        
        if (!formatted.contains("/api/")) {
            formatted += "api/"
        }
        
        while (formatted.endsWith("//")) {
            formatted = formatted.substring(0, formatted.length - 1)
        }
        if (!formatted.endsWith("/")) {
            formatted += "/"
        }

        return formatted
    }

    private fun createHttpClient(cookieJar: CookieJar): OkHttpClient {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        return OkHttpClient.Builder()
            .addInterceptor(logging)
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                val request = chain.request()
                val newRequestBuilder = request.newBuilder()
                
                // Only add application/json if we're not requesting an image
                val path = request.url.encodedPath
                if (path.startsWith("/api/") && !path.contains("/cover") && !path.contains("/image")) {
                    newRequestBuilder.header("Accept", "application/json")
                } else if (path.contains("/cover") || path.contains("/image")) {
                    newRequestBuilder.header("Accept", "image/*")
                }
                
                chain.proceed(newRequestBuilder.build())
            }
            .build()
    }

    private fun setupClientAndFetch(url: String, sessionCookie: String?) {
        val formattedUrl = formatBaseUrl(url)
        val httpUrl = formattedUrl.toHttpUrlOrNull()
        if (httpUrl == null) {
            Log.e("BookViewModel", "Invalid URL: $formattedUrl")
            return
        }
        baseUrl = formattedUrl
        
        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            init {
                if (sessionCookie != null) {
                    val cookie = Cookie.parse(httpUrl, sessionCookie)
                    if (cookie != null) {
                        cookieStore[httpUrl.host] = listOf(cookie)
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                cookies.find { it.name == "session" }?.let { session ->
                    saveCredentials(baseUrl, session.toString())
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val client = createHttpClient(cookieJar)
        
        okHttpClient = client
        sessionCacheBuster = System.currentTimeMillis().toString()
        (app as? BookieReaderApplication)?.updateAuthenticatedClient(client)

        val gson = GsonBuilder().create()
        val retrofit = Retrofit.Builder()
            .baseUrl(formattedUrl)
            .addConverterFactory(GsonConverterFactory.create(gson))
            .client(client)
            .build()

        val service = retrofit.create(ApiService::class.java)

        viewModelScope.launch {
            isLoading = true
            try {
                var currentPage = 1
                val allFetchedBooks = mutableListOf<Book>()
                var totalBooks: Int
                
                do {
                    val response = service.getBooks(page = currentPage, perPage = 100)
                    val booksWithProgress = response.books.map { book ->
                        val savedProgress = prefs.getFloat("book_progress_${book.id}", -1f)
                        if (savedProgress >= 0f) {
                            book.copy(progress = savedProgress)
                        } else {
                            book
                        }
                    }
                    allFetchedBooks.addAll(booksWithProgress)
                    totalBooks = response.total
                    currentPage++
                } while (allFetchedBooks.size < totalBooks && response.books.isNotEmpty())
                
                books = allFetchedBooks
            } catch (_: Exception) {
                prefs.edit { 
                    remove("base_url")
                    remove("session_cookie")
                }
                errorMessage = app.getString(R.string.session_expired)
            } finally {
                isLoading = false
            }
        }
    }

    fun connect(url: String, username: String, password: String, onSuccess: () -> Unit) {
        val formattedUrl = formatBaseUrl(url)
        baseUrl = formattedUrl
        
        viewModelScope.launch {
            isLoading = true
            errorMessage = null
            try {
                val cookieJar = object : CookieJar {
                    private val cookieStore = mutableMapOf<String, List<Cookie>>()
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        cookieStore[url.host] = cookies
                        cookies.find { it.name == "session" }?.let { session ->
                            saveCredentials(baseUrl, session.toString())
                        }
                    }
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
                }

                val client = createHttpClient(cookieJar)
                val retrofit = Retrofit.Builder()
                    .baseUrl(formattedUrl)
                    .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
                    .client(client)
                    .build()

                val service = retrofit.create(ApiService::class.java)
                val loginResponse = service.login(LoginRequest(username, password))
                
                if (loginResponse.isSuccessful) {
                    saveCredentials(formattedUrl, "") // Initial save, cookie is saved via CookieJar
                    okHttpClient = client
                    sessionCacheBuster = System.currentTimeMillis().toString()
                    (app as? BookieReaderApplication)?.updateAuthenticatedClient(client)
                    
                    var currentPage = 1
                    val allFetchedBooks = mutableListOf<Book>()
                    var totalBooks: Int
                    
                    do {
                        val response = service.getBooks(page = currentPage, perPage = 100)
                        allFetchedBooks.addAll(response.books)
                        totalBooks = response.total
                        currentPage++
                    } while (allFetchedBooks.size < totalBooks && response.books.isNotEmpty())
                    
                    books = allFetchedBooks
                    onSuccess()
                } else {
                    errorMessage = app.getString(R.string.login_failed, loginResponse.code())
                }
            } catch (_: Exception) {
                errorMessage = app.getString(R.string.failed_to_connect)
            } finally {
                isLoading = false
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        val serverUrl = getSavedBaseUrl() ?: ""
        val session = getSavedSessionCookie() ?: ""
        
        prefs.edit { 
            clear()
            // Restore only the essential connection info
            if (serverUrl.isNotEmpty()) putString("base_url", encrypt(serverUrl))
            if (session.isNotEmpty()) putString("session_cookie", encrypt(session))
        }
        
        // Reset in-memory state
        themeMode = "System"
        readerTheme = "System"
        readerFontSize = 18f
        readerScrollMode = "Horizontal"
        sortBy = "Author"
        
        books = emptyList()
        okHttpClient = null
        baseUrl = ""
        onLoggedOut()
    }

    fun setSortOrder(order: String) {
        sortBy = order
        prefs.edit { putString("sort_by", order) }
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun updateSelectedTag(tag: String?) {
        selectedTag = if (tag == "All" || tag == "None") null else tag
    }

    fun updateReaderTheme(theme: String) {
        if (theme.isBlank()) return
        readerTheme = theme
        prefs.edit { putString("reader_theme", theme) }
    }

    fun updateReaderFontSize(delta: Int) {
        readerFontSize = (readerFontSize + delta).coerceIn(12f, 40f)
        prefs.edit { putFloat("reader_font_size", readerFontSize) }
    }

    fun updateReaderScrollMode(mode: String) {
        readerScrollMode = mode
        prefs.edit { putString("reader_scroll_mode", mode) }
    }

    private fun colorToHex(color: Int): String {
        return String.format("#%06X", 0xFFFFFF and color)
    }

    fun prepareReader(file: File, bgColor: Int? = null, textColor: Int? = null) {
        val currentParams = listOf(file.absolutePath, readerFontSize, readerTheme, readerScrollMode, bgColor, textColor)
        
        val oldParams = lastPreparedParams as? List<*>
        val fileChanged = oldParams == null || oldParams[0] != file.absolutePath
        
        // If nothing changed, return
        if (!fileChanged && lastPreparedParams == currentParams && (readerPages.isNotEmpty() || currentPublication != null)) return
        
        val isReadiumFormat = file.extension.lowercase() in listOf("epub", "pdf")
        
        // If it's Readium and only preferences/colors changed, we don't need to reload the publication
        if (!fileChanged && isReadiumFormat && currentPublication != null) {
            lastPreparedParams = currentParams
            return
        }

        preparationJob?.cancel()
        
        // Only reset publication-related state if file changed
        if (fileChanged) {
            isReaderLoading = true
            readerError = null
            currentPublication = null
            epubNavigatorFactory = null
            pdfNavigatorFactory = null
            pdfNavigator = null
            currentEpubBook = null
            currentMobiData = null
            readerPages = emptyList()
            readerToc = emptyList()
            chapterPageCounts.clear()
        }

        preparationJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val format = file.extension.lowercase()
                val isDark = when (readerTheme) {
                    "Dark" -> true
                    "Light" -> false
                    "System" -> (app.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
                    else -> false
                }
                val bgHex = if (bgColor != null) colorToHex(bgColor) else if (isDark) "#121212" else "#FFFFFF"
                val textHex = if (textColor != null) colorToHex(textColor) else if (isDark) "#E0E0E0" else "#1A1A1A"
                
                val result: Pair<Any, List<ReaderPage>> = withContext(Dispatchers.IO) {
                    when (format) {
                        "epub" -> {
                            val assetRetriever = AssetRetriever(app.contentResolver, DefaultHttpClient())
                            val publicationOpener = PublicationOpener(
                                publicationParser = EpubParser(),
                            )
                            val publicationResult = withContext(Dispatchers.IO) {
                                currentPublication?.close()
                                val asset: Asset? = assetRetriever.retrieve(file).getOrNull()
                                if (asset != null) {
                                    publicationOpener.open(asset, allowUserInteraction = false)
                                } else {
                                    Try.failure(Exception("Asset could not be retrieved."))
                                }
                            }

                            when (publicationResult) {
                                is Success -> {
                                    val publication = publicationResult.value
                                    withContext(Dispatchers.Main) {
                                        currentPublication = publication
                                        epubNavigatorFactory = EpubNavigatorFactory(publication)
                                        readerToc = flattenToc(publication, publication.tableOfContents)
                                        loadBookmarks(currentBook?.id ?: -1)
                                    }
                                    Pair(publication as Any, emptyList())
                                }

                                is Failure -> {
                                    val error = publicationResult.value
                                    Log.e("BookViewModel", "Readium EPUB opening failed: $error")
                                    withContext(Dispatchers.Main) {
                                        readerError = error.toString()
                                        currentPublication = null
                                        epubNavigatorFactory = null
                                        pdfNavigatorFactory = null
                                    }
                                    val reader = EpubReader()
                                    val loadedEpub = reader.readEpub(file.inputStream())
                                    val toc = mutableListOf<TocItem>()
                                    val allPages = mutableListOf<ReaderPage>()
                                    val tempHrefToPageMap = mutableMapOf<String, Int>()

                                    val tocResourceHrefs = loadedEpub.tableOfContents.tocReferences.map { it.resource.href }
                                    loadedEpub.tableOfContents.tocReferences.forEach { item ->
                                        toc.add(TocItem(item.title, item.resource.href, 0))
                                    }

                                    // One "Page" per Spine Item (Chapter)
                                    loadedEpub.spine.spineReferences.forEachIndexed { spineIndex, ref ->
                                        try {
                                            val href = ref.resource.href
                                            tempHrefToPageMap[href] = spineIndex

                                            val content = ref.resource.reader.readText()
                                            val basePath = ref.resource.href.substringBeforeLast("/", "")
                                            val bodyRegex = Regex(
                                                "<body[^>]*>(.*?)</body>",
                                                setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
                                            )
                                            val match = bodyRegex.find(content)
                                            val body = match?.groupValues?.get(1) ?: content

                                            allPages.add(ReaderPage(wrapInTemplate(body, bgHex, textHex), basePath))
                                        } catch (_: Exception) {
                                            Log.e("BookViewModel", "Error loading chapter $spineIndex")
                                        }
                                    }

                                    val finalToc = toc.mapIndexed { index, item ->
                                        val resHref = tocResourceHrefs[index].trimStart('/')
                                        val spineIdx = loadedEpub.spine.spineReferences.indexOfFirst {
                                            val sHref = it.resource.href.trimStart('/')
                                            sHref == resHref || sHref == Uri.decode(resHref)
                                        }
                                        item.copy(pageIndex = spineIdx)
                                    }

                                    withContext(Dispatchers.Main) {
                                        readerToc = finalToc
                                        hrefToPageMap = tempHrefToPageMap
                                    }
                                    Pair(loadedEpub as Any, allPages)
                                }
                            }
                        }
                        "mobi", "azw3" -> {
                            val mobiData = MobiExtractor.extractText(app, file)
                            val bodyRegex = Regex("<body[^>]*>(.*?)</body>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            val match = bodyRegex.find(mobiData.text)
                            val innerBody = match?.groupValues?.get(1) ?: mobiData.text

                            val chapters = innerBody.split(Regex("(?i)<h[1-2][^>]*>"))
                            val generatedPages = mutableListOf<ReaderPage>()

                            if (chapters.size > 1) {
                                chapters.forEachIndexed { index, content ->
                                    if (content.isNotBlank()) {
                                        val prefix = if (index > 0) "<h2>" else ""
                                        generatedPages.add(ReaderPage(wrapInTemplate(prefix + content, bgHex, textHex), ""))
                                    }
                                }
                            } else {
                                generatedPages.add(ReaderPage(wrapInTemplate(innerBody, bgHex, textHex), ""))
                            }

                            Pair(mobiData, generatedPages)
                        }
                        "pdf" -> {
                            currentPublication?.close()
                            val assetRetriever = AssetRetriever(app.contentResolver, DefaultHttpClient())
                            val publicationOpener = PublicationOpener(
                                publicationParser = PdfParser(app, pdfFactory = PdfiumDocumentFactory(app)),
                            )
                            val publicationResult = withContext(Dispatchers.IO) {
                                val asset: Asset? = assetRetriever.retrieve(file).getOrNull()
                                if (asset != null) {
                                    publicationOpener.open(asset, allowUserInteraction = false)
                                } else {
                                    Try.failure(Exception("Asset could not be retrieved."))
                                }
                            }

                            when (publicationResult) {
                                is Success -> {
                                    val publication = publicationResult.value
                                    withContext(Dispatchers.Main) {
                                        currentPublication = publication
                                        val engineProvider = PdfiumEngineProvider(
                                            listener = object : PdfiumEngineProvider.Listener {
                                                override fun onConfigurePdfView(configurator: PDFView.Configurator) {
                                                    configurator
                                                        .pageSnap(true)
                                                        .pageFling(true)
                                                        .autoSpacing(true)
                                                }
                                            }
                                        )
                                        pdfNavigatorFactory = PdfNavigatorFactory(publication, engineProvider)
                                        readerToc = flattenToc(publication, publication.tableOfContents)
                                        loadBookmarks(currentBook?.id ?: -1)
                                    }
                                    Pair(publication as Any, emptyList())
                                }

                                is Failure -> {
                                    val error = publicationResult.value
                                    Log.e("BookViewModel", "Readium PDF opening failed: $error")
                                    withContext(Dispatchers.Main) {
                                        readerError = error.toString()
                                        currentPublication = null
                                        epubNavigatorFactory = null
                                        pdfNavigatorFactory = null
                                    }
                                    // Fallback to legacy PDF handling if Readium fails
                                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                                    val renderer = PdfRenderer(pfd)
                                    val pageCount = renderer.pageCount
                                    renderer.close()
                                    pfd.close()

                                    val dummyPages = List(pageCount) { ReaderPage("", null) }
                                    Pair(pageCount as Any, dummyPages)
                                }
                            }
                        }
                        else -> throw Exception("Unsupported format")
                    }
                }
                
                withContext(Dispatchers.Main) {
                    val finalPages = result.second
                    if (format == "epub" || format == "pdf") {
                        if (result.first is Publication) {
                            currentPublication = result.first as Publication
                            currentEpubBook = null
                        } else {
                            currentEpubBook = result.first as? nl.siegmann.epublib.domain.Book
                            currentPublication = null
                        }
                        currentMobiData = null
                    } else {
                        currentMobiData = result.first as? MobiData
                        currentEpubBook = null
                        readerToc = emptyList()
                    }
                    chapterPageCounts.clear() // Reset pagination info
                    readerPages = finalPages
                    lastPreparedParams = currentParams
                }
            } catch (e: Exception) {
                if (e !is kotlinx.coroutines.CancellationException) {
                    readerError = "Failed to load book"
                }
            } finally {
                isReaderLoading = false
            }
        }
    }

    private fun wrapInTemplate(content: String, bgHex: String, textHex: String): String {
        val isVertical = readerScrollMode == "Vertical"
        return """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
            <style>
                * {
                    box-sizing: border-box;
                    -webkit-tap-highlight-color: transparent;
                }
                html, body {
                    margin: 0;
                    padding: 0;
                    background-color: $bgHex;
                    color: $textHex;
                    width: 100vw;
                    ${if (isVertical) "" else "height: 100vh; overflow: hidden;"}
                    font-family: "Georgia", serif;
                }
                body {
                    font-size: ${readerFontSize}px;
                    line-height: 1.6;
                    -webkit-text-size-adjust: none;
                }
                #content-container {
                    ${if (isVertical) "" else "height: 100vh;"}
                    width: 100vw;
                    ${if (isVertical) "" else """
                    /* Using column-width slightly less than 100vw to ensure single column per view */
                    column-width: 100vw;
                    column-gap: 0;
                    column-fill: auto;
                    """}
                    padding: 40px 24px 60px 24px;
                }
                /* Desktop/Tablet wide support: two columns per screen */
                @media (min-width: 1000px) {
                    #content-container {
                        ${if (isVertical) "" else "column-width: 45vw; column-gap: 5vw;"}
                        padding: 60px 5vw;
                    }
                }
                img, svg {
                    max-width: 100% !important;
                    ${if (isVertical) "" else "max-height: calc(100vh - 120px) !important;"}
                    display: block;
                    margin: 10px auto;
                    object-fit: contain;
                    ${if (isVertical) "" else "break-inside: avoid;"}
                }
                p, h1, h2, h3, h4, h5, h6, li, blockquote {
                    ${if (isVertical) "" else "break-inside: avoid-column;"}
                    orphans: 2;
                    widows: 2;
                }
                p {
                    margin: 0 0 1em 0;
                    text-align: justify;
                    overflow-wrap: break-word;
                    word-wrap: break-word;
                }
                h1, h2, h3, h4, h5, h6 {
                    text-align: center;
                    line-height: 1.3;
                    margin: 1.2em 0 0.8em 0;
                    break-after: avoid;
                }
                /* Hide scrollbars */
                ::-webkit-scrollbar {
                    display: none;
                }
            </style>
            </head>
            <body>
                <div id="content-container">
                    $content
                </div>
                <script>
                    function getPageCount() {
                        var container = document.getElementById('content-container');
                        if (!container) return 1;
                        if ($isVertical) {
                            return Math.max(1, Math.ceil(container.scrollHeight / window.innerHeight));
                        }
                        // Use scrollWidth to determine how many 'pages' (viewports) we have
                        return Math.max(1, Math.ceil(container.scrollWidth / window.innerWidth));
                    }
                    
                    function scrollToPage(index) {
                        if ($isVertical) {
                            window.scrollTo({
                                left: 0,
                                top: index * window.innerHeight,
                                behavior: 'instant'
                            });
                            return;
                        }
                        var x = index * window.innerWidth;
                        window.scrollTo({
                            left: x,
                            top: 0,
                            behavior: 'instant'
                        });
                    }

                    function reportPageCount() {
                        if (window.Android && window.Android.onPageCountReady) {
                            window.Android.onPageCountReady(getPageCount());
                        }
                    }

                    // Multi-pass reporting to handle late-loading assets
                    if (document.readyState === 'complete') {
                        reportPageCount();
                    }
                    
                    window.onload = function() {
                        reportPageCount();
                        setTimeout(reportPageCount, 500);
                        setTimeout(reportPageCount, 2000);
                    };
                    
                    window.addEventListener('resize', reportPageCount);
                    // Add observer to catch layout shifts after fonts/images load
                    const resizeObserver = new ResizeObserver(() => reportPageCount());
                    resizeObserver.observe(document.body);
                </script>
            </body>
            </html>
        """.trimIndent()
    }

    fun findPageIndexForHref(href: String): Int {
        return hrefToPageMap[href] ?: -1
    }

    fun downloadAndOpenBook(context: Context, book: Book, onReady: () -> Unit) {
        preparationJob?.cancel()
        readerPages = emptyList()
        readerToc = emptyList()
        currentEpubBook = null
        currentMobiData = null
        lastPreparedParams = null
        currentPageIndex = 0
        currentBook = null
        currentBookFile = null

        if (book.id < 0) {
            currentBook = book
            currentBookFile = File(book.downloadUrl)
            onReady()
            return
        }
        val client = okHttpClient ?: return
        val downloadUrl = "${baseUrl}books/${book.id}/download"
        
        viewModelScope.launch {
            isLoading = true
            try {
                val request = Request.Builder().url(downloadUrl).build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        if (!response.isSuccessful) throw Exception(app.getString(R.string.download_failed))
                        val body = response.body
                        
                        val file = File(context.cacheDir, "book_${book.id}.${book.format}")
                        body.byteStream().use { input ->
                            FileOutputStream(file).use { output -> input.copyTo(output) }
                        }
                        
                        currentBookFile = file
                        currentBook = book
                        withContext(Dispatchers.Main) { onReady() }
                    }
                }
            } catch (_: Exception) {
                errorMessage = app.getString(R.string.download_error)
            } finally {
                isLoading = false
            }
        }
    }

    fun importLocalBook(context: Context, uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val inputStream = context.contentResolver.openInputStream(uri) ?: return@launch
                val fileName = getFileName(context, uri) ?: "imported_book_${System.currentTimeMillis()}"
                val extension = fileName.substringAfterLast(".", "epub")
                
                val localDir = File(context.filesDir, "local_books")
                if (!localDir.exists()) localDir.mkdirs()
                
                val destFile = File(localDir, fileName)
                destFile.outputStream().use { output ->
                    inputStream.copyTo(output)
                }
                
                val bookId = -(System.currentTimeMillis() % Int.MAX_VALUE).toInt()
                val title = fileName.substringBeforeLast(".")
                var author = context.getString(R.string.local_book)
                var series: String? = null
                var seriesIndex: Float? = null

                when (extension) {
                    "mobi", "azw3" -> {
                        try {
                            val mobiData = MobiExtractor.extractText(context, destFile)
                            series = mobiData.series
                            seriesIndex = mobiData.seriesIndex
                        } catch (_: Exception) {
                            Log.e("BookViewModel", "Error extracting MOBI metadata")
                        }
                    }
                    "epub" -> {
                        try {
                            val reader = EpubReader()
                            val epubBook = reader.readEpub(destFile.inputStream())
                            author = epubBook.metadata.authors.firstOrNull()?.let { "${it.firstname} ${it.lastname}".trim() } ?: context.getString(R.string.unknown_author)
                        } catch (_: Exception) {
                            Log.e("BookViewModel", "Error extracting EPUB metadata")
                        }
                    }
                    "pdf" -> {
                        try {
                            PDDocument.load(destFile).use { pdf ->
                                val info = pdf.documentInformation
                                author = info.author ?: context.getString(R.string.unknown_author)
                            }
                        } catch (_: Exception) {
                            Log.e("BookViewModel", "Error extracting PDF metadata")
                        }
                    }
                }
                
                val entity = LocalBookEntity(
                    id = bookId,
                    title = title,
                    author = author,
                    format = extension,
                    filePath = destFile.absolutePath,
                    series = series,
                    seriesOrder = seriesIndex?.toDouble()
                )
                
                localBookDao.insertBook(entity)
                
                val newBook = Book(
                    id = bookId,
                    title = title,
                    author = author,
                    format = extension,
                    downloadUrl = destFile.absolutePath,
                    coverUrl = null,
                    series = series,
                    seriesOrder = seriesIndex?.toDouble(),
                    tags = null
                )
                
                withContext(Dispatchers.Main) {
                    localBooks = localBooks + newBook
                }
            } catch (_: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = app.getString(R.string.error_failed_to_import)
                }
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            if (book.id < 0) {
                localBookDao.deleteBook(LocalBookEntity(
                    id = book.id,
                    title = book.title,
                    author = book.author,
                    format = book.format,
                    filePath = book.downloadUrl,
                    series = book.series,
                    seriesOrder = book.seriesOrder
                ))
                
                val file = File(book.downloadUrl)
                if (file.exists()) {
                    file.delete()
                }
                withContext(Dispatchers.Main) {
                    localBooks = localBooks.filter { it.id != book.id }
                }
            } else {
                withContext(Dispatchers.Main) {
                    books = books.filter { it.id != book.id }
                }
            }
        }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index != -1) result = cursor.getString(index)
                }
            }
        }
        if (result == null) {
            result = uri.path
            val cut = result?.lastIndexOf('/') ?: -1
            if (cut != -1) result = result?.substring(cut + 1)
        }
        return result
    }


}
