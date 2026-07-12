package com.rawlab.editor.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rawlab.editor.R
import com.rawlab.editor.export.Exporter
import com.rawlab.editor.raw.DecodedRaw
import com.rawlab.editor.raw.EditState
import com.rawlab.editor.raw.RawDecoder
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
)

class EditorViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(RawLabUiState())
    val uiState: StateFlow<RawLabUiState> = _uiState

    fun openRaw(uri: Uri) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        viewModelScope.launch {
            val context = getApplication<Application>()
            val displayName = queryDisplayName(uri) ?: "rawlab"
            val decoded = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                        RawDecoder.decode(pfd.fd, PREVIEW_MAX_DIMENSION)
                    }
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
                runCatching { Exporter.export(context, uri, state.editState, state.sourceDisplayName) }
            }
            _uiState.update {
                it.copy(
                    isExporting = false,
                    exportMessage = context.getString(
                        if (result.isSuccess) R.string.export_success else R.string.export_failure
                    ),
                )
            }
        }
    }

    fun consumeExportMessage() {
        _uiState.update { it.copy(exportMessage = null) }
    }
}
