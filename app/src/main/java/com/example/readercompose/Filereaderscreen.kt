package com.example.readercompose

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Состояния экрана: ожидание, загрузка, успех и ошибка.
private sealed interface ScreenState {
    data object Empty : ScreenState
    data object Loading : ScreenState
    data class Success(val book: OpenedBook) : ScreenState
    data class Error(val message: String) : ScreenState
}

@Composable
fun FileReaderScreen() {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()
    var screenState by remember { mutableStateOf<ScreenState>(ScreenState.Empty) }

    // Открываем системный выбор файлов, доступ к памяти не запрашиваем.
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                screenState = ScreenState.Loading

                try {
                    // Долгое чтение идёт не в главном потоке.
                    val book = withContext(Dispatchers.IO) {
                        BookReader.open(context, uri)
                    }
                    screenState = ScreenState.Success(book)
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    screenState = ScreenState.Error(
                        error.message ?: "Не удалось прочитать файл"
                    )
                }
            }
        }
    }

    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Читалка файлов",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Text(
                text = "Форматы TXT и FB2 · Без базы данных",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Button(
                modifier = Modifier.fillMaxWidth(),
                enabled = screenState !is ScreenState.Loading,
                onClick = {
                    // '*/*' нужен, чтобы файловые менеджеры показывали FB2.
                    // Допустимое расширение проверяется при чтении.
                    filePicker.launch(arrayOf("*/*"))
                }
            ) {
                Text("Открыть файл")
            }

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                when (val state = screenState) {
                    ScreenState.Empty -> CenterMessage(
                        "Выберите текстовый файл или книгу FB2"
                    )

                    ScreenState.Loading -> Row(
                        modifier = Modifier.fillMaxSize(),
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator()
                        Text(
                            text = "  Чтение файла...",
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }

                    is ScreenState.Error -> CenterMessage(
                        "Ошибка: ${state.message}",
                        isError = true
                    )

                    is ScreenState.Success -> BookContent(state.book)
                }
            }
        }
    }
}

@Composable
private fun BookContent(book: OpenedBook) {
    val listState = rememberLazyListState()

    // При открытии новой книги начинаем чтение с начала.
    LaunchedEffect(book.name) {
        listState.scrollToItem(0)
    }

    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            item {
                Text(
                    text = book.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Прокручивайте вниз, чтобы читать",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 12.sp
                )
            }

            items(book.paragraphs) { paragraph ->
                if (paragraph.text.isEmpty()) {
                    Spacer(modifier = Modifier.height(8.dp))
                } else {
                    Text(
                        text = paragraph.text,
                        style = if (paragraph.heading) {
                            MaterialTheme.typography.titleMedium
                        } else {
                            MaterialTheme.typography.bodyLarge
                        },
                        lineHeight = 25.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (paragraph.heading) {
                            FontWeight.Bold
                        } else {
                            FontWeight.Normal
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CenterMessage(message: String, isError: Boolean = false) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = message,
            color = if (isError) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}
