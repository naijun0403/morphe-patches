/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.fadeInDuration;
import static app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.getDialogBackgroundColor;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.util.Pair;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PipDismissHelper;

public final class VotBottomSheet {

    private static final int DRAWABLE_CHEVRON_RIGHT = ResourceUtils.getIdentifier(
            ResourceType.DRAWABLE, "ic_keyboard_arrow_right_black_24dp");
    private static final int DRAWABLE_CHEVRON_RIGHT_BOLD = ResourceUtils.getIdentifier(
            ResourceType.DRAWABLE, "yt_outline_experimental_chevron_right_vd_theme_18");

    public static void show(Context context) {
        SheetBottomDialog.DraggableLinearLayout root = SheetBottomDialog
                .createMainLayout(context, getDialogBackgroundColor());
        root.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);

        final int fg = Utils.getAppForegroundColor();

        String[] langEntries = context.getResources().getStringArray(ResourceUtils
                .getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entries"));
        String[] langValues = context.getResources().getStringArray(ResourceUtils
                .getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entry_values"));

        if (langEntries.length > 1) {
            List<Pair<String, String>> list = new ArrayList<>();
            for (int i = 1; i < langEntries.length; i++) {
                list.add(new Pair<>(langEntries[i], langValues[i]));
            }
            list.sort((p1, p2) -> p1.first.compareToIgnoreCase(p2.first));
            for (int i = 1; i < langEntries.length; i++) {
                langEntries[i] = list.get(i - 1).first;
                langValues[i] = list.get(i - 1).second;
            }
        }

        LinearLayout engineRow = makeValueRow(context, fg, str("morphe_vot_tts_engine_label"));
        Runnable refreshEngine = () -> refreshEngineRow(engineRow);
        engineRow.setOnClickListener(v -> showEnginePicker(context, refreshEngine));
        refreshEngine.run();

        LinearLayout translationRow = makeValueRow(context, fg, str("morphe_vot_translation_service_title"));
        Runnable refreshTranslation = () -> {
            String service = Settings.VOT_TRANSLATION_SERVICE.get();
            String label;
            if ("libretranslate".equals(service)) label = str("morphe_vot_service_libretranslate");
            else if ("mymemory".equals(service))    label = str("morphe_vot_service_mymemory");
            else                                  label = str("morphe_vot_service_google");
            ((TextView) translationRow.getTag()).setText(label);
        };
        translationRow.setOnClickListener(v -> showTranslationServicePicker(context, refreshTranslation));
        refreshTranslation.run();

        root.addView(makeTitle(context, str("morphe_vot_enabled_title"), fg));
        root.addView(makeLanguageRow(context, str("morphe_vot_caption_language_title"), fg,
                langEntries, langValues, refreshEngine));
        root.addView(translationRow);
        root.addView(engineRow);
        root.addView(makeDivider(context, fg));
        root.addView(makeSliderRow(context,
                str("morphe_vot_original_audio_volume_title"),
                Settings.VOT_ORIGINAL_AUDIO_VOLUME.get(),
                fg,
                Settings.VOT_ORIGINAL_AUDIO_VOLUME::save));
        root.addView(makeRateSliderRow(context,
                str("morphe_vot_max_speech_rate_title"),
                Settings.VOT_MAX_SPEECH_RATE.get(),
                fg,
                Settings.VOT_MAX_SPEECH_RATE::save));
        SheetBottomDialog.SlideDialog dialog =
                SheetBottomDialog.createSlideDialog(context, root, fadeInDuration);
        PipDismissHelper.dismissOnPip(dialog);
        dialog.show();
    }

    // Creates a label + value row; value TextView is stored in the row tag for refreshing.
    private static LinearLayout makeValueRow(Context context, int fg, String label) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fg);
        labelView.setTextSize(16);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        TextView valueView = new TextView(context);
        valueView.setTextColor(secondaryColor(fg));
        valueView.setTextSize(14);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(Utils.appIsUsingBoldIcons() ? DRAWABLE_CHEVRON_RIGHT_BOLD : DRAWABLE_CHEVRON_RIGHT);
        chevron.setColorFilter(new PorterDuffColorFilter(secondaryColor(fg), PorterDuff.Mode.SRC_IN));
        chevron.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(chevron);

        row.setTag(valueView);
        return row;
    }

    private static void refreshEngineRow(LinearLayout row) {
        TextView valueView = (TextView) row.getTag();
        String lang = Settings.VOT_CAPTION_LANGUAGE.get();
        if ("auto".equals(lang)) lang = VoiceOverTranslationPatch.detectedSourceLang;

        String voiceId = Settings.VOT_TTS_VOICE_TYPE.get();
        VoiceCatalog.Voice voice = VoiceCatalog.getVoice(voiceId);

        if (Settings.VOT_USE_NATIVE_TTS.get() || (voice == null && VoiceCatalog.resolve(lang, null) == null)) {
            valueView.setText(str("morphe_vot_tts_system"));
        } else if (voice != null && voice.id.startsWith(lang)) {
            valueView.setText(voice.dialogDisplayName);
        } else {
            String defaultVoiceId = VoiceCatalog.resolve(lang, null);
            VoiceCatalog.Voice defaultVoice = VoiceCatalog.getVoice(defaultVoiceId);
            valueView.setText(defaultVoice != null ? defaultVoice.dialogDisplayName : str("morphe_vot_tts_system"));
        }

        // Dim and disable when no Edge voice is available for the current language.
        final boolean edgeAvailable = VoiceCatalog.resolve(lang, null) != null;
        row.setAlpha(edgeAvailable ? 1f : 0.4f);
        row.setClickable(edgeAvailable);
    }

    private static void showTranslationServicePicker(Context context, Runnable onChanged) {
        String[] entries = {str("morphe_vot_service_google"), str("morphe_vot_service_mymemory"), str("morphe_vot_service_libretranslate")};
        String[] values = {"google", "mymemory", "libretranslate"};

        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        pickerRoot.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        pickerRoot.addView(makeTitle(context, str("morphe_vot_translation_service_title"),
                Utils.getAppForegroundColor()));

        ListView listView = new ListView(context);
        listView.setDivider(null);
        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context, LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, entries, values,
                        Settings.VOT_TRANSLATION_SERVICE.get());
        listView.setAdapter(adapter);

        SheetBottomDialog.SlideDialog pickerDialog =
                SheetBottomDialog.createSlideDialog(context, pickerRoot, fadeInDuration);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Settings.VOT_TRANSLATION_SERVICE.save(values[position]);
            VoiceOverTranslationPatch.reloadTranscript();
            onChanged.run();
            pickerDialog.dismiss();
        });

        pickerRoot.addView(listView);
        pickerDialog.show();
    }

    private static void showEnginePicker(Context context, Runnable onChanged) {
        String lang = Settings.VOT_CAPTION_LANGUAGE.get();
        if ("auto".equals(lang)) lang = VoiceOverTranslationPatch.detectedSourceLang;

        List<VoiceCatalog.Voice> voices = VoiceCatalog.getVoicesForLang(lang);
        if (voices == null) {
            voices = Collections.emptyList();
        } else {
            voices = new ArrayList<>(voices);
            voices.sort((v1, v2) -> {
                if (v1.isMale != v2.isMale) return v1.isMale ? -1 : 1;
                return v1.shortName.compareToIgnoreCase(v2.shortName);
            });
        }

        int voicesSize = voices.size();
        final int entryValuesSize = voicesSize + 1;
        String[] entries = new String[entryValuesSize];
        String[] values = new String[entryValuesSize];

        for (int i = 0; i < voicesSize; i++) {
            VoiceCatalog.Voice v = voices.get(i);
            entries[i] = v.dialogDisplayName;
            values[i] = v.id;
        }
        entries[entryValuesSize - 1] = str("morphe_vot_tts_system");
        values[entryValuesSize - 1] = "system";

        String selectedValue = Settings.VOT_USE_NATIVE_TTS.get()
                ? "system"
                : Settings.VOT_TTS_VOICE_TYPE.get();

        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        pickerRoot.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        pickerRoot.addView(makeTitle(context, str("morphe_vot_tts_engine_label"), Utils.getAppForegroundColor()));

        ListView listView = new ListView(context);
        listView.setDivider(null);
        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context, LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, entries, values, selectedValue);
        listView.setAdapter(adapter);

        SheetBottomDialog.SlideDialog pickerDialog =
                SheetBottomDialog.createSlideDialog(context, pickerRoot, fadeInDuration);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            if ("system".equals(values[position])) {
                Settings.VOT_USE_NATIVE_TTS.save(true);
            } else {
                Settings.VOT_USE_NATIVE_TTS.save(false);
                Settings.VOT_TTS_VOICE_TYPE.save(values[position]);
            }
            onChanged.run();
            pickerDialog.dismiss();
        });

        pickerRoot.addView(listView);
        pickerDialog.show();
    }

    private static LinearLayout makeLanguageRow(Context context, String label, int fgColor,
                                                String[] langEntries, String[] langValues,
                                                Runnable onLanguageChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48);
        row.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(16);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        TextView valueView = new TextView(context);
        valueView.setText(getLangDisplayName(Settings.VOT_CAPTION_LANGUAGE.get(), langEntries, langValues));
        valueView.setTextColor(secondaryColor(fgColor));
        valueView.setTextSize(14);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        ImageView chevron = new ImageView(context);
        chevron.setImageResource(Utils.appIsUsingBoldIcons() ? DRAWABLE_CHEVRON_RIGHT_BOLD : DRAWABLE_CHEVRON_RIGHT);
        chevron.setColorFilter(new PorterDuffColorFilter(secondaryColor(fgColor), PorterDuff.Mode.SRC_IN));
        chevron.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(chevron);

        row.setOnClickListener(v -> showLanguagePicker(context, label, langEntries, langValues,
                valueView, onLanguageChanged));
        return row;
    }

    private static void showLanguagePicker(Context context, String title,
                                            String[] langEntries, String[] langValues,
                                            TextView valueView, Runnable onLanguageChanged) {
        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        pickerRoot.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        pickerRoot.addView(makeTitle(context, title, Utils.getAppForegroundColor()));

        ListView listView = new ListView(context);
        listView.setDivider(null);

        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context, LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED,
                        langEntries, langValues, Settings.VOT_CAPTION_LANGUAGE.get());
        listView.setAdapter(adapter);

        SheetBottomDialog.SlideDialog pickerDialog =
                SheetBottomDialog.createSlideDialog(context, pickerRoot, fadeInDuration);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selected = langValues[position];
            Settings.VOT_CAPTION_LANGUAGE.save(selected);
            valueView.setText(langEntries[position]);
            adapter.setSelectedValue(selected);
            adapter.notifyDataSetChanged();
            VoiceOverTranslationPatch.reloadTranscript();
            onLanguageChanged.run();
            pickerDialog.dismiss();
        });

        pickerRoot.addView(listView);
        pickerDialog.show();
    }

    private static String getLangDisplayName(String code, String[] langEntries, String[] langValues) {
        for (int i = 0, length = langValues.length; i < length; i++) {
            if (langValues[i].equals(code)) return langEntries[i];
        }
        return langEntries[0];
    }

    private static TextView makeTitle(Context context, String text, int fgColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(fgColor);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, Dim.dp8, 0, Dim.dp16);
        tv.setLayoutParams(params);
        return tv;
    }

    private static View makeDivider(Context context, int fgColor) {
        View divider = new View(context);
        divider.setBackgroundColor(Color.argb(30,
                Color.red(fgColor), Color.green(fgColor), Color.blue(fgColor)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.setMargins(0, Dim.dp4, 0, Dim.dp4);
        divider.setLayoutParams(params);
        return divider;
    }

    // storedValue encodes rate × 10: 10 = 1.0x, 18 = 1.8x, 20 = 2.0x.
    private static LinearLayout makeRateSliderRow(Context context, String label, int storedValue,
                                                   int fgColor, IntConsumer onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp8, 0, 0);
        row.setLayoutParams(rowParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(16);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(labelView);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(10);
        seekBar.setProgress(storedValue - 10);
        seekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
        seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        seekParams.setMargins(Dim.dp12, 0, Dim.dp12, 0);
        seekBar.setLayoutParams(seekParams);
        row.addView(seekBar);

        TextView valueView = new TextView(context);
        valueView.setText(String.format(Locale.ROOT, "%.1fx", storedValue / 10.0f));
        valueView.setTextColor(fgColor);
        valueView.setTextSize(14);
        valueView.setMinWidth(Dim.dp40);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    final int stored = progress + 10;
                    valueView.setText(String.format(Locale.ROOT, "%.1fx", stored / 10.0f));
                    onChanged.accept(stored);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        return row;
    }

    private static LinearLayout makeSliderRow(Context context, String label, int initialValue,
                                               int fgColor, IntConsumer onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp8, 0, 0);
        row.setLayoutParams(rowParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(16);
        labelView.setTypeface(Typeface.DEFAULT_BOLD);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(labelView);

        SeekBar seekBar = new SeekBar(context);
        seekBar.setMax(100);
        seekBar.setProgress(initialValue);
        seekBar.getProgressDrawable().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
        seekBar.getThumb().setColorFilter(new PorterDuffColorFilter(fgColor, PorterDuff.Mode.SRC_IN));
        LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        seekParams.setMargins(Dim.dp12, 0, Dim.dp12, 0);
        seekBar.setLayoutParams(seekParams);
        row.addView(seekBar);

        TextView valueView = new TextView(context);
        valueView.setText(String.format(Locale.ROOT, "%d%%", initialValue));
        valueView.setTextColor(fgColor);
        valueView.setTextSize(14);
        valueView.setMinWidth(Dim.dp40);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                if (fromUser) {
                    valueView.setText(String.format(Locale.ROOT, "%d%%", progress));
                    onChanged.accept(progress);
                }
            }
            @Override public void onStartTrackingTouch(SeekBar bar) { }
            @Override public void onStopTrackingTouch(SeekBar bar) { }
        });

        return row;
    }

    private static int secondaryColor(int fg) {
        return Color.argb(153, Color.red(fg), Color.green(fg), Color.blue(fg));
    }

    @FunctionalInterface
    private interface IntConsumer {
        void accept(int value);
    }
}
