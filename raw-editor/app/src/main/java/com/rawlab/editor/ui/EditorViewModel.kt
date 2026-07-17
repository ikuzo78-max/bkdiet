package com.rawlab.editor.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawlab.editor.R
import com.rawlab.editor.export.ExportFormat
import com.rawlab.editor.export.ExportPrefs
import com.rawlab.editor.export.Exporter
import com.rawlab.editor.raw.DecodedRaw
import com.rawlab.editor.raw.EditState
import com.rawlab.editor.raw.SourceImageDecoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 프리뷰 디코드 시 긴 변 최대 크기(px). GFX100RF 같은 1억 화소급 RAW도 실시간 편집이
 *  가능하도록 축소 프록시로 디코드한다 — 전체 해상도는 export 시에만 다시 디코드한다. */
private const val PREVIEW_MAX_DIMENSION = 2048

data class RawLabUiState(
    val decoded: DecodedRaw? = null,
    val editState: EditState = EditState(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val isExporting: Boolean = false,
    val exportMessage: String? = null,
    val sourceUri: Uri? = null,
    val sourceDisplayName: String = "rawlab",
    val exportFormat: ExportFormat = ExportFormat.JPEG,
    /** null이면 기본 위치(Pictures/RawLab, MediaStore)에 저장. */
    val exportFolderUri: Uri? = null,
    val exportFolderName: String? = null,
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(
        RawLabUiState(
            exportFormat = ExportPrefs.getFormat(application),
            exportFolderUri = ExportPrefs.getTreeUri(application),
            exportFolderName = ExportPrefs.getTreeUri(application)?.let { treeUri ->
                runCatching { DocumentFile.fromTreeUri(application, treeUri)?.name }.getOrNull()
            },
        )
    )
    val uiState: StateFlow<RawLabUiState> = _uiState

    fun openRaw(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val displayName = queryDisplayName(uri) ?: "rawlab"
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    SourceImageDecoder.decode(context, uri, displayName, PREVIEW_MAX_DIMENSION)
                }.getOrNull()
            }
            if (decoded == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = context.getString(R.string.decode_failure))
                }
            } else {
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        decoded = decoded,
                        editState = EditState(),
                        sourceUri = uri,
                        sourceDisplayName = displayName,
                    )
                }
            }
        }
    }

    private fun queryDisplayName(uri: Uri): String? {
        val resolver = getApplication<Application>().contentResolver
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0) return cursor.getString(idx)
            }
        }
        return null
    }

    fun updateEditState(editState: EditState) {
        _uiState.update { it.copy(editState = editState) }
    }

    fun resetEditState() {
        _uiState.update { it.copy(editState = EditState()) }
    }

    fun export() {
        val state = _uiState.value
        val uri = state.sourceUri ?: return
        _uiState.update { it.copy(isExporting = true, exportMessage = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            // 전체 해상도는 여기서 다시 디코드한다 (프리뷰는 PREVIEW_MAX_DIMENSION으로
            // 축소된 프록시라 그대로 저장하면 원본 화소를 잃는다).
            val result = withContext(Dispatchers.Default) {
                runCatching {
                    Exporter.export(
                        context, uri, state.editState, state.sourceDisplayName,
                        state.exportFormat, state.exportFolderUri,
                    )
                }
            }
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportMessage = result.fold(
                        onSuccess = { location -> "${context.getString(R.string.export_success)}: $location" },
                        onFailure = { context.getString(R.string.export_failure) },
                    ),
                )
            }
        }
    }

    fun consumeExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }

    fun setExportFormat(format: ExportFormat) {
        ExportPrefs.setFormat(getApplication<Application>(), format)
        _uiState.update { it.copy(exportFormat = format) }
    }

    /** SAF 폴더 선택 결과(uri)를 반영한다. null이면 기본 위치(Pictures/RawLab)로 되돌린다. */
    fun setExportFolder(uri: Uri?) {
        val context = getApplication<Application>()
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                )
            }
        }
        ExportPrefs.setTreeUri(context, uri)
        val name = uri?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }
        _uiState.update { it.copy(exportFolderUri = uri, exportFolderName = name) }
    }
}
