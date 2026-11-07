package com.mbols2510.mangamanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mbols2510.mangamanager.ui.App
import com.mbols2510.mangamanager.ui.AppViewModel
import com.mbols2510.mangamanager.ui.MangaTheme

/** The launcher activity; the only exported component of the app. */
class MainActivity : ComponentActivity() {
    private val container: AppContainer get() = (application as MangaApp).container
    private val appViewModel: AppViewModel by viewModels { container.factory }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MangaTheme {
                App(appViewModel = appViewModel, viewModelFactory = container.factory)
            }
        }
    }
}
