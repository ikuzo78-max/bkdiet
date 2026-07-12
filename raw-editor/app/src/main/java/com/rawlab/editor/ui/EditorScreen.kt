package com.rawlab.editor.ui

import android.opengl.GLSurfaceView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.rawlab.editor.R
import com.rawlab.editor.export.ExportFormat
import com.rawlab.editor.gl.RawGLRenderer
import com.rawlab.editor.raw.FilmSimLut
import com.rawlab.editor.raw.RawProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val MAX_PREVIEW_ZOOM = 8f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val decoded = uiState.decoded ?: return
    val context = LocalContext.current

    val renderer = remember { RawGLRenderer(context) }
    var glView by remember { mutableStateOf<GLSurfaceView?>(null) }

    // 프리뷰 확대/이동 상태 — 원본을 다시 불러오지 않고 이미 화면에 있는 프록시
    // 텍스처를 화면에서만 확대해서 보여준다(핀치줌/드래그, 더블탭으로 초기화).
    var zoom by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }

    val folderPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) viewModel.setExportFolder(uri)
    }

    LaunchedEffect(decoded) {
        renderer.submitImage(decoded)
        glView?.requestRender()
    }
    LaunchedEffect(uiState.editState) {
        renderer.editState = uiState.editState
        glView?.requestRender()
    }

    // 히스토그램은 슬라이더를 움직일 때마다 다시 계산하면 버벅이므로 살짝 디바운스한다.
    var histogramBins by remember { mutableStateOf<IntArray?>(null) }
    LaunchedEffect(decoded, uiState.editState) {
        delay(150)
        val state = uiState.editState
        histogramBins = withContext(Dispatchers.Default) {
            runCatching {
                val filmLut = FilmSimLut.load(context, state.filmSimulation) ?: FloatArray(0)
                RawProcessor.computeHistogram(
                    decoded.pixels, decoded.width, decoded.height,
                    state.exposure, state.contrast, state.temperature, state.tint,
                    state.highlights, state.shadows, state.saturation, state.vibrance,
                    state.curvePoints.toFloatArray(),
                    filmLut, FilmSimLut.LUT_SIZE, state.filmSimStrength,
                    state.cropLeft, state.cropTop, state.cropRight, state.cropBottom,
                )
            }.getOrNull()
        }
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
                .weight(1f)
                .pointerInput(Unit) {
                    detectTransformGestures { _, pan, gestureZoom, _ ->
                        zoom = (zoom * gestureZoom).coerceIn(1f, MAX_PREVIEW_ZOOM)
                        // 팬 이동량이 확대 배율과 무관하게 화면 픽셀 이동량과 일치하도록
                        // NDC 단위로 변환한다(화면 y는 아래로, NDC y는 위로 증가하므로 부호 반전).
                        panX += pan.x / (size.width / 2f)
                        panY -= pan.y / (size.height / 2f)
                        renderer.viewZoom = zoom
                        renderer.viewPanX = panX
                        renderer.viewPanY = panY
                        glView?.requestRender()
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onDoubleTap = {
                        zoom = 1f
                        panX = 0f
                        panY = 0f
                        renderer.viewZoom = 1f
                        renderer.viewPanX = 0f
                        renderer.viewPanY = 0f
                        glView?.requestRender()
                    })
                },
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

        HistogramView(
            bins = histogramBins,
            modifier = Modifier
                .fillMaxWidth()
                .height(80.dp),
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 360.dp)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            Text(stringResource(R.string.editor_export_settings), style = MaterialTheme.typography.labelMedium)
            Row {
                ExportFormat.entries.forEach { format ->
                    val selected = uiState.exportFormat == format
                    TextButton(onClick = { viewModel.setExportFormat(format) }) {
                        Text(
                            text = format.name,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                }
            }
            Text(
                text = uiState.exportFolderName ?: stringResource(R.string.editor_export_folder_default),
                style = MaterialTheme.typography.bodySmall,
            )
            Row {
                TextButton(onClick = { folderPicker.launch(null) }) {
                    Text(stringResource(R.string.editor_export_folder_pick))
                }
                if (uiState.exportFolderUri != null) {
                    TextButton(onClick = { viewModel.setExportFolder(null) }) {
                        Text(stringResource(R.string.editor_export_folder_reset))
                    }
                }
            }

            Text(stringResource(R.string.editor_film_simulation), style = MaterialTheme.typography.labelMedium)
            Row(modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())) {
                FilmSimLut.NAMES.forEach { name ->
                    val selected = uiState.editState.filmSimulation == name
                    TextButton(onClick = {
                        viewModel.updateEditState(uiState.editState.copy(filmSimulation = name))
                    }) {
                        Text(
                            text = FilmSimLut.displayName(name),
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.primary else Color.Unspecified,
                        )
                    }
                }
            }
            if (uiState.editState.filmSimulation != FilmSimLut.NONE) {
                AdjustSlider(
                    label = stringResource(R.string.editor_film_strength),
                    value = uiState.editState.filmSimStrength,
                    valueRange = 0f..1f,
                ) { viewModel.updateEditState(uiState.editState.copy(filmSimStrength = it)) }
            }

            Text(stringResource(R.string.editor_curve), style = MaterialTheme.typography.labelMedium)
            CurveEditor(
                points = uiState.editState.curvePoints,
                onPointsChange = { viewModel.updateEditState(uiState.editState.copy(curvePoints = it)) },
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .padding(bottom = 8.dp),
            )

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
