package com.example.readercompose

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.example.readercompose.ui.theme.ReaderComposeTheme

// Точка входа: запускаем экран читалки в теме приложения.
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            ReaderComposeTheme {
                FileReaderScreen()
            }
        }
    }
}
