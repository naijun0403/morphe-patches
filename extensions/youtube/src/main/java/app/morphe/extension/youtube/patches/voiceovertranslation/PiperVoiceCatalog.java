/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Maps BCP-47 language codes to downloadable Piper (VITS) TTS models
 * hosted in the sherpa-onnx tts-models GitHub release.
 */
final class PiperVoiceCatalog {

    private static final String BASE_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/";

    record Model(String tarName, String displayName, int sizeMb) {
        String downloadUrl() {
            return BASE_URL + tarName + ".tar.bz2";
        }
    }

    private static final Map<String, Model> CATALOG = new HashMap<>();

    static {
        CATALOG.put("uk", new Model("vits-piper-uk_UA-lada-x_low",            "Lada (x-low)",            6));
        CATALOG.put("ru", new Model("vits-piper-ru_RU-irina-medium",          "Irina (medium)",         64));
        CATALOG.put("en", new Model("vits-piper-en_US-ryan-low",              "Ryan (low)",             64));
        CATALOG.put("de", new Model("vits-piper-de_DE-thorsten-low",          "Thorsten (low)",         65));
        CATALOG.put("fr", new Model("vits-piper-fr_FR-upmc-medium",           "UPMC Pierre (medium)",   58));
        CATALOG.put("es", new Model("vits-piper-es_ES-carlfm-x_low",          "Carlfm (x-low)",          6));
        CATALOG.put("pl", new Model("vits-piper-pl_PL-darkman-medium",        "Darkman (medium)",       67));
        CATALOG.put("it", new Model("vits-piper-it_IT-riccardo-x_low",        "Riccardo (x-low)",        6));
        CATALOG.put("pt", new Model("vits-piper-pt_BR-faber-medium",          "Faber (medium)",         45));
        CATALOG.put("nl", new Model("vits-piper-nl_NL-mls-medium",            "MLS (medium)",           55));
        CATALOG.put("cs", new Model("vits-piper-cs_CZ-jirka-medium",          "Jirka (medium)",         78));
        CATALOG.put("sk", new Model("vits-piper-sk_SK-lili-medium",           "Lili (medium)",          80));
        CATALOG.put("ro", new Model("vits-piper-ro_RO-mihai-medium",          "Mihai (medium)",         67));
        CATALOG.put("hu", new Model("vits-piper-hu_HU-anna-medium",           "Anna (medium)",          43));
        CATALOG.put("fi", new Model("vits-piper-fi_FI-harri-medium",          "Harri (medium)",         66));
        CATALOG.put("tr", new Model("vits-piper-tr_TR-dfki-medium",           "DFKI (medium)",          56));
        CATALOG.put("zh", new Model("vits-piper-zh_CN-huayan-medium",         "Huayan (medium)",        64));
        CATALOG.put("ja", new Model("vits-piper-ja_JP-roboko-medium",         "Roboko (medium)",        56));
        CATALOG.put("ko", new Model("vits-piper-ko_KR-kss-medium",            "KSS (medium)",           55));
        CATALOG.put("ar", new Model("vits-piper-ar_JO-kareem-medium",         "Kareem (medium)",        63));
        CATALOG.put("vi", new Model("vits-piper-vi_VN-25hours_single-low",    "25Hours (low)",          64));
        CATALOG.put("fa", new Model("vits-piper-fa-haaniye_low",               "Haaniye (low)",          20));
        CATALOG.put("ka", new Model("vits-piper-ka_GE-natia-medium",           "Natia (medium)",         65));
        CATALOG.put("kk", new Model("vits-piper-kk_KZ-issai-high",             "Issai (high)",           85));
        CATALOG.put("no", new Model("vits-piper-no_NO-talesyntese-medium",     "Talesyntese (medium)",   60));
        CATALOG.put("sl", new Model("vits-piper-sl_SI-artur-medium",           "Artur (medium)",         65));
        CATALOG.put("ml", new Model("vits-piper-ml_IN-meera-medium",           "Meera (medium)",         55));
        CATALOG.put("sr", new Model("vits-piper-sr_RS-serbski_institut-medium-fp16", "Serbski Institut (medium)", 60));
        CATALOG.put("lv", new Model("vits-piper-lv_LV-aivars-medium",          "Aivars (medium)",        60));
        CATALOG.put("ne", new Model("vits-piper-ne_NP-google-medium",          "Google (medium)",        45));
        CATALOG.put("lb", new Model("vits-piper-lb_LU-marylux-medium",         "Marylux (medium)",       60));
        CATALOG.put("sw", new Model("vits-piper-sw_CD-lanfrica-medium",        "Lanfrica (medium)",      55));
    }

    static Collection<Model> allModels() {
        return CATALOG.values();
    }

    /** Returns the Piper model for a given BCP-47 language tag, or {@code null} if unsupported. */
    static Model forLang(String lang) {
        if (lang == null) return null;
        Model m = CATALOG.get(lang);
        if (m == null && lang.contains("-")) {
            m = CATALOG.get(lang.split("-")[0]);
        }
        return m;
    }

    private PiperVoiceCatalog() {}
}
