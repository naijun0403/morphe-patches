package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the LibreTranslate info preference to open the API key registration page.
 */
@SuppressWarnings("unused")
public class VoiceOverTranslationLibreTranslateInfoPreference extends URLLinkPreference {
    {
        externalURL = "https://libretranslate.com";
    }

    public VoiceOverTranslationLibreTranslateInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public VoiceOverTranslationLibreTranslateInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public VoiceOverTranslationLibreTranslateInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public VoiceOverTranslationLibreTranslateInfoPreference(Context context) {
        super(context);
    }
}
