package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the Lingva info preference to open the Lingva Translate website.
 */
@SuppressWarnings("unused")
public class VoiceOverTranslationLingvaInfoPreference extends URLLinkPreference {
    {
        externalURL = "https://lingva.ml";
    }

    public VoiceOverTranslationLingvaInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public VoiceOverTranslationLingvaInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public VoiceOverTranslationLingvaInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public VoiceOverTranslationLingvaInfoPreference(Context context) {
        super(context);
    }
}
