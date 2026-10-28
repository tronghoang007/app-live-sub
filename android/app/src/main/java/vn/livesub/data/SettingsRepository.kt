package vn.livesub.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {

    private object Keys {
        val targetLang = stringPreferencesKey("target_lang")
        val sourceLang = stringPreferencesKey("source_lang")
        val asrModel = stringPreferencesKey("asr_model")
        val textSize = floatPreferencesKey("text_size_sp")
        // "_v2": bỏ giá trị cũ (mặc định 45%) để mọi máy chuyển sang nền đen đặc.
        val bgAlpha = floatPreferencesKey("bg_alpha_v2")
        val bottomOffset = floatPreferencesKey("bottom_offset_pct")
        val horizontalOffset = floatPreferencesKey("horizontal_offset_pct")
        val showSource = booleanPreferencesKey("show_source")
        val stackSize = intPreferencesKey("stack_size")
        val showPartials = booleanPreferencesKey("show_partials")
        val touchThrough = booleanPreferencesKey("touch_through")
        val alwaysShowBox = booleanPreferencesKey("always_show_box")
        val clearAfter = longPreferencesKey("clear_after_ms")
        val wifiOnly = booleanPreferencesKey("wifi_only")
        val useTranslationServer = booleanPreferencesKey("use_translation_server")
        val translationServer = stringPreferencesKey("translation_server")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { e -> if (e is IOException) emit(emptyPreferences()) else throw e }
        .map { it.toSettings() }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val s = transform(prefs.toSettings())
            prefs[Keys.targetLang] = s.targetLang
            prefs[Keys.sourceLang] = s.sourceLang
            prefs[Keys.asrModel] = s.asrModel.name
            prefs[Keys.textSize] = s.textSizeSp
            prefs[Keys.bgAlpha] = s.backgroundAlpha
            prefs[Keys.bottomOffset] = s.bottomOffsetPercent
            prefs[Keys.horizontalOffset] = s.horizontalOffsetPercent
            prefs[Keys.showSource] = s.showSourceText
            prefs[Keys.stackSize] = s.stackSize
            prefs[Keys.showPartials] = s.showPartials
            prefs[Keys.touchThrough] = s.touchThrough
            prefs[Keys.alwaysShowBox] = s.alwaysShowBox
            prefs[Keys.clearAfter] = s.clearAfterMs
            prefs[Keys.wifiOnly] = s.wifiOnlyDownloads
            prefs[Keys.useTranslationServer] = s.useTranslationServer
            prefs[Keys.translationServer] = s.translationServer
        }
    }

    private fun Preferences.toSettings(): AppSettings {
        val d = AppSettings()
        return AppSettings(
            targetLang = this[Keys.targetLang] ?: d.targetLang,
            sourceLang = this[Keys.sourceLang] ?: d.sourceLang,
            asrModel = this[Keys.asrModel]
                ?.let { name -> AsrModel.entries.firstOrNull { it.name == name } }
                ?: d.asrModel,
            textSizeSp = this[Keys.textSize] ?: d.textSizeSp,
            backgroundAlpha = this[Keys.bgAlpha] ?: d.backgroundAlpha,
            bottomOffsetPercent = this[Keys.bottomOffset] ?: d.bottomOffsetPercent,
            horizontalOffsetPercent = this[Keys.horizontalOffset] ?: d.horizontalOffsetPercent,
            showSourceText = this[Keys.showSource] ?: d.showSourceText,
            stackSize = this[Keys.stackSize] ?: d.stackSize,
            showPartials = this[Keys.showPartials] ?: d.showPartials,
            touchThrough = this[Keys.touchThrough] ?: d.touchThrough,
            alwaysShowBox = this[Keys.alwaysShowBox] ?: d.alwaysShowBox,
            clearAfterMs = this[Keys.clearAfter] ?: d.clearAfterMs,
            wifiOnlyDownloads = this[Keys.wifiOnly] ?: d.wifiOnlyDownloads,
            useTranslationServer = this[Keys.useTranslationServer] ?: d.useTranslationServer,
            translationServer = this[Keys.translationServer] ?: d.translationServer,
        )
    }
}
