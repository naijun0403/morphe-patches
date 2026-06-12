/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps language codes (same values as {@code morphe_vot_caption_language_entry_values})
 * to voice display names shown in the settings UI.
 */
final class VoiceCatalog {

    // { male voice, female voice } pairs. Keys must match morphe_vot_caption_language_entry_values (arrays.xml), excluding "auto".
    private static final Map<String, String[]> VOICES = new HashMap<>();

    static {
        VOICES.put("en", new String[]{ "en-US-ChristopherNeural", "en-US-JennyNeural" });
        VOICES.put("uk", new String[]{ "uk-UA-OstapNeural",       "uk-UA-PolinaNeural" });
        VOICES.put("ru", new String[]{ "ru-RU-DmitryNeural",      "ru-RU-SvetlanaNeural" });
        VOICES.put("de", new String[]{ "de-DE-KillianNeural",     "de-DE-KatjaNeural" });
        VOICES.put("fr", new String[]{ "fr-FR-HenriNeural",       "fr-FR-DeniseNeural" });
        VOICES.put("es", new String[]{ "es-ES-AlvaroNeural",      "es-ES-ElviraNeural" });
        VOICES.put("pl", new String[]{ "pl-PL-MarekNeural",       "pl-PL-ZofiaNeural" });
        VOICES.put("pt", new String[]{ "pt-BR-AntonioNeural",     "pt-BR-FranciscaNeural" });
        VOICES.put("it", new String[]{ "it-IT-DiegoNeural",       "it-IT-ElsaNeural" });
        VOICES.put("tr", new String[]{ "tr-TR-AhmetNeural",       "tr-TR-EmelNeural" });
        VOICES.put("ja", new String[]{ "ja-JP-KeitaNeural",       "ja-JP-NanamiNeural" });
        VOICES.put("ko", new String[]{ "ko-KR-InJoonNeural",      "ko-KR-SunHiNeural" });
        VOICES.put("zh", new String[]{ "zh-CN-YunxiNeural",       "zh-CN-XiaoxiaoNeural" });
    }

    /**
     * Returns a voice name for {@code lang} and the requested gender,
     * or {@code null} if the language is not supported.
     */
    @Nullable
    static String resolve(String lang, boolean preferMale) {
        String[] pair = VOICES.get(lang);
        if (pair == null) return null;
        return preferMale ? pair[0] : pair[1];
    }

    /**
     * Returns a human-readable short name for a voice.
     */
    static String shortName(String voiceName) {
        if (voiceName == null || voiceName.isEmpty()) return "";
        int dash = voiceName.lastIndexOf('-');
        String name = dash >= 0 ? voiceName.substring(dash + 1) : voiceName;
        if (name.endsWith("Neural")) name = name.substring(0, name.length() - 6);
        return name;
    }
}
