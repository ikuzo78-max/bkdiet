package com.rawlab.editor.export

import android.content.Context
import android.net.Uri
import androidx.core.content.edit

/** 저장 포맷/폴더 선택을 앱 재실행 후에도 기억해두기 위한 간단한 저장소. */
object ExportPrefs {
    private const val PREFS_NAME = "export_prefs"
    private const val KEY_FORMAT = "format"
    private const val KEY_TREE_URI = "tree_uri"

    fun getFormat(context: Context): ExportFormat {
        val name = prefs(context).getString(KEY_FORMAT, null) ?: return ExportFormat.JPEG
        return runCatching { ExportFormat.valueOf(name) }.getOrDefault(ExportFormat.JPEG)
    }

    fun setFormat(context: Context, format: ExportFormat) {
        prefs(context).edit { putString(KEY_FORMAT, format.name) }
    }

    fun getTreeUri(context: Context): Uri? {
        val value = prefs(context).getString(KEY_TREE_URI, null) ?: return null
        return Uri.parse(value)
    }

    fun setTreeUri(context: Context, uri: Uri?) {
        prefs(context).edit { putString(KEY_TREE_URI, uri?.toString()) }
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
