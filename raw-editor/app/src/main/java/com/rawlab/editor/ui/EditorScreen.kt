package com.rawlab.editor.ui

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rawlab.editor.R
import com.rawlab.editor.gl.RawGLRenderer
import kotlinx.coroutines.delay

@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val decoded = uiState.decoded ?: return
    val context = LocalContext.current

    val renderer = remember { RawGLRenderer(context) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

    LaunchedEffect(decoded) {
        renderer.submitImage(decoded)
        glView?.requestRender()
    }
    LaunchedEffect(uiState.editState) {
        renderer.editState = uiState.editState
        glView?.requestRender()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(uiState.sourceDisplayName, maxLines = 1) },
            actions = {
                TextButton(onClick = {
                    val next = (uiState.editState.rotationDegrees + 90) % 360
                    viewModel.updateEditState(uiState.editState.copy(rotationDegrees = next))
                }) { Text(stringResource(R.string.editor_rotate)) }
                TextButton(onClick = { viewModel.resetEditState() }) {
                    Text(stringResource(R.string.editor_reset))
                }
                TextButton(onClick = { viewModel.export() }, enabled = !uiState.isExporting) {
                    Text(
                        if (uiState.isExporting) {
                            stringResource(R.string.editor_exporting)
                        } else {
                            stringResource(R.string.editor_export)
                        }
                    )
                }
            }
        )

        AndroidView(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            factory = { ctx ->
                GLSurfaceView(ctx).apply {
                    setEGLContextClientVersion(3)
                    setRenderer(renderer)
                    renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
                    glView = this
                    renderer.submitImage(decoded)
                    requestRender()
                }
            }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 260.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            AdjustSlider(stringResource(R.string.editor_exposure), uiState.editState.exposure) {
                viewModel.updateEditState(uiState.editState.copy(exposure = it))
            }
            AdjustSlider(stringResource(R.string.editor_contrast), uiState.editState.contrast) {
                viewModel.updateEditState(uiState.editState.copy(contrast = it))
            }
            AdjustSlider(stringResource(R.string.editor_temperature), uiState.editState.temperature) {
                viewModel.updateEditState(uiState.editState.copy(temperature = it))
            }
            AdjustSlider(stringResource(R.string.editor_tint), uiState.editState.tint) {
                viewModel.updateEditState(uiState.editState.copy(tint = it))
            }
            AdjustSlider(stringResource(R.string.editor_highlights), uiState.editState.highlights) {
                viewModel.updateEditState(uiState.editState.copy(highlights = it))
            }
            AdjustSlider(stringResource(R.string.editor_shadows), uiState.editState.shadows) {
                viewModel.updateEditState(uiState.editState.copy(shadows = it))
            }
            AdjustSlider(stringResource(R.string.editor_saturation), uiState.editState.saturation) {
                viewModel.updateEditState(uiState.editState.copy(saturation = it))
            }
            AdjustSlider(stringResource(R.string.editor_vibrance), uiState.editState.vibrance) {
                viewModel.updateEditState(uiState.editState.copy(vibrance = it))
            }
            AdjustSlider(
                label = stringResource(R.string.editor_sharpen),
                value = uiState.editState.sharpen,
                valueRange = 0f..1f,
            ) {
                viewModel.updateEditState(uiState.editState.copy(sharpen = it))
            }
        }

        uiState.exportMessage?.let { message ->
            Text(
                text = message,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
            )
            LaunchedEffect(message) {
                delay(2000)
                viewModel.consumeExportMessage()
            }
        }
    }
}

@Composable
private fun AdjustSlider(
    label: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float> = -1f..1f,
    onValueChange: (Float) -> Unit,
) {
    Column {
        Text(text = label, style = MaterialTheme.typography.labelMedium)
        Slider(value = value, onValueChange = onValueChange, valueRange = valueRange)
    }
}
