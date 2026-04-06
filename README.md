# Bookie Reader

Bookie Reader is a modern, lightweight Android ebook reader designed specifically for use with the [Bookie](https://github.com/sweatyeggs69/Bookie) self-hosted ebook manager. It provides a seamless reading experience by connecting directly to your personal library.
<p align="center">
<img width="405" height="902" alt="Dark Mode" src="https://github.com/user-attachments/assets/6f58d5ca-aed3-4efb-bb29-6c4f2c198a6d" /> <img width="405" height="902" alt="Light Mode" src="https://github.com/user-attachments/assets/3ce3b9a7-5de9-47b1-b1a5-c0e079b157fc" />
</p>

## ✨ Features

- **Multi-Format Support**: Read EPUB, PDF, and MOBI/AZW3 files.
- **Server Integration**: Connects directly to your Bookie server to browse and download your collection.
- **Local Library**: Import and manage local ebook files from your device.
- **Advanced Reader**:
  - Customizable font sizes.
  - Multiple themes: Light, Dark, Sepia, and System.
  - Horizontal and Vertical scroll modes (for supported formats).
  - Table of Contents and Bookmark support.
  - Dynamic pagination for reflowable formats.
- **Library Management**:
  - Grid and List views.
  - Search by title, author, series, or tags.
  - Sort by Title, Author, Series, or Tag.
  - Batch selection and deletion.
- **Modern UI**: Built with Jetpack Compose and Material 3.

## 🚀 Getting Started

1. **Prerequisites**: Ensure you have a running instance of [Bookie](https://github.com/sweatyeggs69/Bookie).
2. **Connection**: Upon launching the app, enter your Bookie server URL and your credentials (username and password).
3. **Browsing**: Once connected, you'll see your library. You can pull to refresh the book list.
4. **Reading**: Tap a book to download and open it in the reader.

## 🛠️ Built With

- **[Jetpack Compose](https://developer.android.com/compose)** - Modern toolkit for building native UI.
- **[Readium Kotlin Toolkit](https://github.com/readium/kotlin-toolkit)** - Robust engine for EPUB and PDF rendering.
- **[Retrofit](https://square.github.io/retrofit/) & [OkHttp](https://square.github.io/okhttp/)** - Networking layer for API communication.
- **[Room](https://developer.android.com/training/data-storage/room)** - Local database for book metadata and local imports.
- **[Coil](https://coil-kt.github.io/coil/)** - Image loading for book covers.
- **[PDFBox-Android](https://github.com/TomRoush/PdfBox-Android)** - PDF metadata extraction.

## 🙏 Acknowledgements

- This app is meant to point to the excellent [Bookie](https://github.com/sweatyeggs69/Bookie) self-hosted ebook manager.
- Special thanks to the **Readium** team for their comprehensive [Kotlin Toolkit](https://github.com/readium/kotlin-toolkit), which powers the pagination and rendering features.
- Created with assistance from **Gemini in Android Studio**.

[![Get it on Obtainium](https://github.com/user-attachments/assets/713d71c5-3dec-4ec4-a3f2-8d28d025a9c6)](http://apps.obtainium.imranr.dev/redirect.html?r=obtainium://add/https://github.com/OmegaRa/Bookie-Reader/releases)
[![Get it on GitHub](https://github.com/machiav3lli/oandbackupx/raw/034b226cea5c1b30eb4f6a6f313e4dadcbb0ece4/badge_github.png)](https://github.com/OmegaRa/Bookie-Reader/releases)


---
*Note: This is an unofficial companion app for the Bookie project.*
