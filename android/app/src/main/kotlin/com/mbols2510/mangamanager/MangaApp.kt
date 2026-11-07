package com.mbols2510.mangamanager

import android.app.Application

/** Owns the process-wide [AppContainer]. Built lazily, so nothing is opened until first used. */
class MangaApp : Application() {
    val container: AppContainer by lazy { AppContainer(this) }
}
