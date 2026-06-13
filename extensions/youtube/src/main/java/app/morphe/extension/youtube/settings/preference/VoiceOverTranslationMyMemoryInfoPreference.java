package app.morphe.extension.youtube.settings.preference;

import android.content.Context;
import android.util.AttributeSet;

import app.morphe.extension.shared.settings.preference.URLLinkPreference;

/**
 * Allows tapping the MyMemory info preference to open the MyMemory website.
 */
@SuppressWarnings("unused")
public class VoiceOverTranslationMyMemoryInfoPreference extends URLLinkPreference {
    {
        externalURL = "https://mymemory.translated.net";
    }

    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }
    public VoiceOverTranslationMyMemoryInfoPreference(Context context) {
        super(context);
    }
}
