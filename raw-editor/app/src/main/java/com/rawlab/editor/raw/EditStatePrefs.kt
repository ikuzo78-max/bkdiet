package com.rawlab.editor.raw

import android.content.Context
import androidx.core.content.edit
import kotlinx.serialization.json.Json

/**
 * 현재 편집 설정(EditState)을 하나 저장해뒀다가, 나중에(다른 사진을 열었을 때 포함)
 * 다시 불러와 적용할 수 있게 하는 간단한 저장소. 프리셋 여러 개가 아니라 "마지막으로
 * 저장한 설정" 한 슬롯만 기억한다(라이트룸의 "설정 복사/붙여넣기"와 비슷한 용도).
 */
object EditStatePrefs {
    private const val PREFS_NAME = "edit_state_prefs"
    private const val KEY_SAVED_STATE = "saved_state"

    private val json = Json { ignoreUnknownKeys = true }

    fun hasSaved(context: Context): Boolean =
        prefs(context).contains(KEY_SAVED_STATE)

    fun save(context: Context, state: EditState) {
        prefs(context).edit { putString(KEY_SAVED_STATE, json.encodeToString(EditState.serializer(), state)) }
    }

    /** 저장된 설정이 없거나 읽는 데 실패하면 null. */
    fun load(context: Context): EditState? {
        val raw = prefs(context).getString(KEY_SAVED_STATE, null) ?: return null
        return runCatching { json.decodeFromString(EditState.serializer(), raw) }.getOrNull()
    }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
}
