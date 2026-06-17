package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the OpenRouter info preference to open the OpenRouter website.
 */
@SuppressWarnings("unused")
public class VoiceOverTranslationOpenRouterInfoPreference extends URLLinkPreference {
    {
        externalURL = "https://openrouter.ai";
    }

    public VoiceOverTranslationOpenRouterInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public VoiceOverTranslationOpenRouterInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public VoiceOverTranslationOpenRouterInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public VoiceOverTranslationOpenRouterInfoPreference(Context context) {
        super(context);
    }
}
