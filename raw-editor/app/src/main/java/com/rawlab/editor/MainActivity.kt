package com.rawlab.editor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.rawlab.editor.ui.EditorScreen
import com.rawlab.editor.ui.EditorViewModel
import com.rawlab.editor.ui.HomeScreen

class MainActivity : ComponentActivity() {

    private val viewModel: EditorViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawLabApp(viewModel)
        }
    }
}

@Composable
private fun RawLabApp(viewModel: EditorViewModel) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            val uiState by viewModel.uiState.collectAsState()
            if (uiState.decoded == null) {
                HomeScreen(
                    isLoading = uiState.isLoading,
                    errorMessage = uiState.errorMessage,
                    onPickRaw = viewModel::openRaw,
                )
            } else {
                EditorScreen(viewModel = viewModel)
            }
        }
    }
}
