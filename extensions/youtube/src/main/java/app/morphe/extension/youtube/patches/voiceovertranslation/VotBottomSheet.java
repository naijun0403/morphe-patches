/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
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
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.SeekBar;
import android.widget.TextView;

import java.util.Locale;

import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.shared.ui.SheetBottomDialog;
import app.morphe.extension.youtube.settings.Settings;

public final class VotBottomSheet {

    public static void show(Context context) {
        SheetBottomDialog.DraggableLinearLayout root =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        root.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);

        int fg = Utils.getAppForegroundColor();

        String[] langEntries = context.getResources().getStringArray(
                ResourceUtils.getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entries"));
        String[] langValues = context.getResources().getStringArray(
                ResourceUtils.getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entry_values"));

        LinearLayout engineRow = makeValueRow(context, fg, str("morphe_vot_tts_engine_label"));

        Runnable refresh = () -> refreshEngineRow(engineRow);

        engineRow.setOnClickListener(v -> showEnginePicker(context, refresh));
        refresh.run();

        root.addView(makeTitle(context, str("morphe_vot_enabled_title"), fg));
        root.addView(makeLanguageRow(context, str("morphe_vot_caption_language_title"), fg,
                langEntries, langValues, refresh));
        root.addView(engineRow);
        root.addView(makeDivider(context, fg));
        root.addView(makeSliderRow(context,
                str("morphe_vot_original_audio_label"),
                Settings.VOT_ORIGINAL_AUDIO_VOLUME.get(),
                fg,
                Settings.VOT_ORIGINAL_AUDIO_VOLUME::save));

        SheetBottomDialog.SlideDialog dialog =
                SheetBottomDialog.createSlideDialog(context, root, fadeInDuration);
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
        labelView.setTextSize(15);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        TextView valueView = new TextView(context);
        valueView.setTextColor(secondaryColor(fg));
        valueView.setTextSize(15);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        row.setTag(valueView);
        return row;
    }

    private static void refreshEngineRow(LinearLayout row) {
        TextView valueView = (TextView) row.getTag();
        String lang = Settings.VOT_CAPTION_LANGUAGE.get();
        if ("auto".equals(lang)) lang = VoiceOverTranslationPatch.detectedSourceLang;

        boolean preferFemale = Settings.VOT_PREFER_FEMALE_VOICE.get();
        String voice = VoiceCatalog.resolve(lang, !preferFemale);

        if (voice == null || Settings.VOT_USE_NATIVE_TTS.get()) {
            valueView.setText(str("morphe_vot_tts_system"));
        } else {
            valueView.setText(VoiceCatalog.shortName(voice));
        }

        // Dim and disable when no Edge voice is available for the current language.
        boolean edgeAvailable = voice != null;
        row.setAlpha(edgeAvailable ? 1f : 0.4f);
        row.setClickable(edgeAvailable);
    }

    private static void showEnginePicker(Context context, Runnable onChanged) {
        String lang = Settings.VOT_CAPTION_LANGUAGE.get();
        if ("auto".equals(lang)) lang = VoiceOverTranslationPatch.detectedSourceLang;

        String maleVoice   = VoiceCatalog.resolve(lang, true);
        String femaleVoice = VoiceCatalog.resolve(lang, false);

        String[] entries = {
                VoiceCatalog.shortName(maleVoice)   + " (" + str("morphe_vot_voice_male")   + ")",
                VoiceCatalog.shortName(femaleVoice)  + " (" + str("morphe_vot_voice_female") + ")",
                str("morphe_vot_tts_system")
        };
        String[] values = {"male", "female", "system"};

        String selected;
        if (Settings.VOT_USE_NATIVE_TTS.get()) {
            selected = "system";
        } else {
            selected = Settings.VOT_PREFER_FEMALE_VOICE.get() ? "female" : "male";
        }

        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        pickerRoot.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        pickerRoot.addView(makeTitle(context, str("morphe_vot_tts_engine_label"), Utils.getAppForegroundColor()));

        ListView listView = new ListView(context);
        listView.setDivider(null);
        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context, LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, entries, values, selected);
        listView.setAdapter(adapter);

        SheetBottomDialog.SlideDialog pickerDialog =
                SheetBottomDialog.createSlideDialog(context, pickerRoot, fadeInDuration);

        listView.setOnItemClickListener((parent, view, position, id) -> {
            switch (values[position]) {
                case "male":
                    Settings.VOT_USE_NATIVE_TTS.save(false);
                    Settings.VOT_PREFER_FEMALE_VOICE.save(false);
                    break;
                case "female":
                    Settings.VOT_USE_NATIVE_TTS.save(false);
                    Settings.VOT_PREFER_FEMALE_VOICE.save(true);
                    break;
                default:
                    Settings.VOT_USE_NATIVE_TTS.save(true);
                    break;
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
        labelView.setTextSize(15);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        TextView valueView = new TextView(context);
        valueView.setText(getLangDisplayName(Settings.VOT_CAPTION_LANGUAGE.get(), langEntries, langValues));
        valueView.setTextColor(secondaryColor(fgColor));
        valueView.setTextSize(15);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

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
        for (int i = 0; i < langValues.length; i++) {
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

    private static LinearLayout makeSliderRow(Context context, String label, int initialValue,
                                               int fgColor, IntConsumer onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(0, Dim.dp8, 0, 0);
        row.setLayoutParams(rowParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(15);
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
        valueView.setTextSize(15);
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
