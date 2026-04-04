package com.example.bookiereader

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
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
import com.google.gson.GsonBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nl.siegmann.epublib.epub.EpubReader
import com.tom_roush.pdfbox.pdmodel.PDDocument
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

class BookViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application
    private val prefs = application.getSharedPreferences("bookie_prefs", Context.MODE_PRIVATE)
    private val db = AppDatabase.getDatabase(application)
    private val localBookDao = db.localBookDao()

    var books by mutableStateOf<List<Book>>(emptyList())
        private set

    var localBooks by mutableStateOf<List<Book>>(emptyList())
        private set

    var isLoading by mutableStateOf(false)
        private set

    var errorMessage by mutableStateOf<String?>(null)
        private set

    var baseUrl by mutableStateOf(prefs.getString("base_url", "") ?: "")
        private set

    var okHttpClient: OkHttpClient? by mutableStateOf(null)
        private set

    var currentBookFile by mutableStateOf<File?>(null)
        private set

    var currentBook by mutableStateOf<Book?>(null)
        private set

    var sortBy by mutableStateOf("Author")
        private set

    var themeMode by mutableStateOf(prefs.getString("theme_mode", "System") ?: "System")
        private set

    var searchQuery by mutableStateOf("")
        private set

    var selectedTag by mutableStateOf<String?>(null)
        private set

    val allTags: List<String>
        get() {
            val serverTags = books.flatMap { it.tags ?: emptyList() }
            val localTags = localBooks.flatMap { it.tags ?: emptyList() }
            return (serverTags + localTags).distinct().sorted()
        }

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
        val savedUrl = prefs.getString("base_url", null)
        val savedSession = prefs.getString("session_cookie", null)
        if (savedUrl != null && savedSession != null) {
            setupClientAndFetch(savedUrl, savedSession)
        }
    }

    private fun loadLocalBooks() {
        viewModelScope.launch {
            val entities = localBookDao.getAllBooks()
            localBooks = entities.map { entity ->
                Book(
                    id = entity.id,
                    title = entity.title,
                    author = entity.author,
                    format = entity.format,
                    downloadUrl = entity.filePath,
                    coverUrl = null,
                    series = entity.series,
                    seriesOrder = entity.seriesOrder,
                    tags = entity.tags?.split(",")?.filter { it.isNotBlank() }
                )
            }
        }
    }

    fun refreshBooks() {
        val savedUrl = prefs.getString("base_url", null)
        val savedSession = prefs.getString("session_cookie", null)
        if (savedUrl != null && savedSession != null) {
            setupClientAndFetch(savedUrl, savedSession)
        }
    }

    private fun setupClientAndFetch(url: String, sessionCookie: String?) {
        val formattedUrl = if (url.endsWith("/")) url else "$url/"
        baseUrl = formattedUrl
        
        val cookieJar = object : CookieJar {
            private val cookieStore = mutableMapOf<String, List<Cookie>>()
            init {
                if (sessionCookie != null) {
                    val httpUrl = formattedUrl.toHttpUrlOrNull()
                    if (httpUrl != null) {
                        val cookie = Cookie.parse(httpUrl, sessionCookie)
                        if (cookie != null) {
                            cookieStore[httpUrl.host] = listOf(cookie)
                        }
                    }
                }
            }

            override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                cookieStore[url.host] = cookies
                cookies.find { it.name == "session" }?.let { session ->
                    prefs.edit { putString("session_cookie", session.toString()) }
                }
            }

            override fun loadForRequest(url: HttpUrl): List<Cookie> {
                return cookieStore[url.host] ?: emptyList()
            }
        }

        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .cookieJar(cookieJar)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("Accept", "application/json")
                    .build()
                chain.proceed(request)
            }
            .build()
        
        okHttpClient = client

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
                    allFetchedBooks.addAll(response.books)
                    totalBooks = response.total
                    currentPage++
                } while (allFetchedBooks.size < totalBooks && response.books.isNotEmpty())
                
                books = allFetchedBooks
            } catch (_: Exception) {
                // If fetching fails, clear prefs to force relogin
                prefs.edit { clear() }
                errorMessage = app.getString(R.string.session_expired)
            } finally {
                isLoading = false
            }
        }
    }

    fun connect(url: String, username: String, password: String, onSuccess: () -> Unit) {
        val formattedUrl = if (url.endsWith("/")) url else "$url/"
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
                            prefs.edit { putString("session_cookie", session.toString()) }
                        }
                    }
                    override fun loadForRequest(url: HttpUrl): List<Cookie> = cookieStore[url.host] ?: emptyList()
                }

                val client = OkHttpClient.Builder().cookieJar(cookieJar).build()
                val retrofit = Retrofit.Builder()
                    .baseUrl(formattedUrl)
                    .addConverterFactory(GsonConverterFactory.create(GsonBuilder().create()))
                    .client(client)
                    .build()

                val service = retrofit.create(ApiService::class.java)
                val loginResponse = service.login(LoginRequest(username, password))
                
                if (loginResponse.isSuccessful) {
                    prefs.edit { putString("base_url", formattedUrl) }
                    okHttpClient = client
                    
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
            } catch (e: Exception) {
                errorMessage = app.getString(R.string.failed_to_connect, e.localizedMessage)
            } finally {
                isLoading = false
            }
        }
    }

    fun logout(onLoggedOut: () -> Unit) {
        prefs.edit { clear() }
        books = emptyList()
        // We keep localBooks even on logout
        okHttpClient = null
        baseUrl = ""
        sortBy = "Author"
        onLoggedOut()
    }

    fun setSortOrder(order: String) {
        sortBy = order
    }

    fun updateSearchQuery(query: String) {
        searchQuery = query
    }

    fun updateSelectedTag(tag: String?) {
        selectedTag = if (tag == "All" || tag == "None") null else tag
    }

    fun updateThemeMode(mode: String) {
        themeMode = mode
        prefs.edit { putString("theme_mode", mode) }
    }

    fun downloadAndOpenBook(context: Context, book: Book, onReady: () -> Unit) {
        if (book.id < 0) { // Local book
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
            } catch (e: Exception) {
                errorMessage = app.getString(R.string.download_error, e.localizedMessage)
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
                        } catch (e: Exception) {
                            android.util.Log.e("BookViewModel", "Error extracting MOBI metadata", e)
                        }
                    }
                    "epub" -> {
                        try {
                            val reader = EpubReader()
                            val epubBook = reader.readEpub(destFile.inputStream())
                            author = epubBook.metadata.authors.firstOrNull()?.let { "${it.firstname} ${it.lastname}".trim() } ?: context.getString(R.string.unknown_author)
                        } catch (e: Exception) {
                            android.util.Log.e("BookViewModel", "Error extracting EPUB metadata", e)
                        }
                    }
                    "pdf" -> {
                        try {
                            PDDocument.load(destFile).use { pdf ->
                                val info = pdf.documentInformation
                                author = info.author ?: context.getString(R.string.unknown_author)
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("BookViewModel", "Error extracting PDF metadata", e)
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
                    seriesOrder = seriesIndex?.toDouble(),
                    tags = null // For now, local imports don't have tags unless we parse them
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
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    errorMessage = app.getString(R.string.error_failed_to_import, e.localizedMessage ?: app.getString(R.string.unknown_error))
                }
            }
        }
    }

    fun deleteBook(book: Book) {
        viewModelScope.launch(Dispatchers.IO) {
            if (book.id < 0) {
                // Local book: delete from DB and file
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
                // Server book: just remove from UI list
                withContext(Dispatchers.Main) {
                    books = books.filter { it.id != book.id }
                }
            }
        }
    }

    fun deleteSelectedBooks(selectedIds: Set<Int>) {
        val toDelete = (books + localBooks).filter { selectedIds.contains(it.id) }
        toDelete.forEach { deleteBook(it) }
    }

    private fun getFileName(context: Context, uri: Uri): String? {
        var result: String? = null
        if (uri.scheme == "content") {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
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
