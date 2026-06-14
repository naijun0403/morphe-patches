/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to this code.
 */

package app.morphe.extension.youtube.patches.voiceovertranslation;

import static app.morphe.extension.shared.StringRef.str;
import static app.morphe.extension.youtube.patches.voiceovertranslation.VoiceOverTranslationPatch.TTS_ENGINE_SYSTEM;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_GOOGLE;
import static app.morphe.extension.youtube.patches.voiceovertranslation.TranscriptTranslator.TRANSLATION_SERVICE_MY_MEMORY;
import static app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.fadeInDuration;
import static app.morphe.extension.youtube.videoplayer.LegacyPlayerControlButton.getDialogBackgroundColor;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.DRAWABLE_CHECKMARK;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.DRAWABLE_CHECKMARK_BOLD;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_CHECK_ICON;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_CHECK_ICON_PLACEHOLDER;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.ID_MORPHE_ITEM_TEXT;
import static app.morphe.extension.shared.settings.preference.CustomDialogListPreference.LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED;

import android.content.Context;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Typeface;
import android.util.Pair;
import android.content.res.ColorStateList;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.OvalShape;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
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
            ResourceType.DRAWABLE, "yt_outline_chevron_right_black_18");
    private static final int DRAWABLE_CHEVRON_RIGHT_BOLD = ResourceUtils.getIdentifier(
            ResourceType.DRAWABLE, "yt_outline_experimental_chevron_right_vd_theme_18");
    private static final int DRAWABLE_SPEAKER = ResourceUtils.getIdentifier(
            ResourceType.DRAWABLE, "yt_outline_speaker_vd_theme_24");
    private static final int DRAWABLE_SPEAKER_BOLD = ResourceUtils.getIdentifier(
            ResourceType.DRAWABLE, "yt_outline_experimental_speaker_vd_theme_24");

    public static void show(Context context) {
        VoiceOverTranslationPatch.preloadTestVoices();

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

        LinearLayout engineRow = makeValueRow(context, fg, str("morphe_vot_tts_voice_type"));
        Runnable refreshEngine = () -> refreshEngineRow(engineRow);
        engineRow.setOnClickListener(v -> showEnginePicker(context, refreshEngine));
        refreshEngine.run();

        LinearLayout translationRow = makeValueRow(context, fg, str("morphe_vot_translation_service_title"));
        Runnable refreshTranslation = () -> {
            String label = str(
                    Settings.VOT_TRANSLATION_SERVICE.get().equals(TRANSLATION_SERVICE_MY_MEMORY)
                            ? "morphe_vot_service_mymemory"
                            : "morphe_vot_service_google");
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
        if ("app".equals(lang)) lang = VoiceOverTranslationPatch.resolveTargetLang();

        String voiceId = Settings.VOT_TTS_VOICE_TYPE.get();
        VoiceCatalog.Voice voice = VoiceCatalog.getVoice(voiceId);

        if (Settings.VOT_USE_NATIVE_TTS.get() || (voice == null && VoiceCatalog.resolve(lang, null) == null)) {
            valueView.setText(str("morphe_vot_tts_system"));
        } else if (voice != null && (voice.id.startsWith(lang) || voice.isMultilingual)) {
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
        String[] entries = {str("morphe_vot_service_google"), str("morphe_vot_service_mymemory")};
        String[] values = { TRANSLATION_SERVICE_GOOGLE, TRANSLATION_SERVICE_MY_MEMORY };

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
        if ("app".equals(lang)) lang = VoiceOverTranslationPatch.resolveTargetLang();

        List<VoiceCatalog.Voice> allVoices = VoiceCatalog.getVoicesForLang(lang);
        List<VoiceCatalog.Voice> nativeVoices = new ArrayList<>();
        List<VoiceCatalog.Voice> multilingualVoices = new ArrayList<>();

        if (allVoices != null) {
            for (VoiceCatalog.Voice v : allVoices) {
                if (v.isMultilingual && !v.languageTag.equals(lang)) {
                    multilingualVoices.add(v);
                } else {
                    nativeVoices.add(v);
                }
            }
        }

        Collections.sort(nativeVoices);
        Collections.sort(multilingualVoices);

        String selectedValue = Settings.VOT_USE_NATIVE_TTS.get()
                ? TTS_ENGINE_SYSTEM
                : Settings.VOT_TTS_VOICE_TYPE.get();

        final int fg = Utils.getAppForegroundColor();

        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());
        pickerRoot.setPadding(Dim.dp16, 0, Dim.dp16, Dim.dp16);
        pickerRoot.addView(makeTitle(context, str("morphe_vot_tts_voice_type"), fg));

        SheetBottomDialog.SlideDialog pickerDialog =
                SheetBottomDialog.createSlideDialog(context, pickerRoot, fadeInDuration);

        ScrollView scroll = new ScrollView(context);
        LinearLayout listLayout = new LinearLayout(context);
        listLayout.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(listLayout);

        LayoutInflater inflater = LayoutInflater.from(context);
        int checkmarkRes = Utils.appIsUsingBoldIcons() ? DRAWABLE_CHECKMARK_BOLD : DRAWABLE_CHECKMARK;
        int speakerRes = Utils.appIsUsingBoldIcons() ? DRAWABLE_SPEAKER_BOLD : DRAWABLE_SPEAKER;
        final int rippleColor = Color.argb(60, Color.red(fg), Color.green(fg), Color.blue(fg));

        for (VoiceCatalog.Voice voice : nativeVoices) {
            addVoiceRow(context, inflater, listLayout, voice.id, voice.dialogDisplayName, false,
                    selectedValue, fg, rippleColor, checkmarkRes, speakerRes, onChanged, pickerDialog);
        }

        addVoiceRow(context, inflater, listLayout, TTS_ENGINE_SYSTEM, str("morphe_vot_tts_system"), true,
                selectedValue, fg, rippleColor, checkmarkRes, speakerRes, onChanged, pickerDialog);

        if (!multilingualVoices.isEmpty()) {
            listLayout.addView(makeSectionHeader(context, str("morphe_vot_voice_multilingual"), fg));
            for (VoiceCatalog.Voice voice : multilingualVoices) {
                addVoiceRow(context, inflater, listLayout, voice.id, voice.dialogDisplayName, false,
                        selectedValue, fg, rippleColor, checkmarkRes, speakerRes, onChanged, pickerDialog);
            }
        }

        pickerRoot.addView(scroll);
        pickerDialog.show();
    }

    private static void addVoiceRow(Context context, LayoutInflater inflater, LinearLayout listLayout,
                                    String value, String label, boolean isSystem,
                                    String selectedValue, int fg, int rippleColor,
                                    int checkmarkRes, int speakerRes,
                                    Runnable onChanged, SheetBottomDialog.SlideDialog pickerDialog) {
        View row = inflater.inflate(LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED, listLayout, false);

        ImageView check = row.findViewById(ID_MORPHE_CHECK_ICON);
        check.setImageResource(checkmarkRes);
        check.setColorFilter(fg);
        boolean isSelected = value.equals(selectedValue);
        check.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        row.findViewById(ID_MORPHE_CHECK_ICON_PLACEHOLDER)
                .setVisibility(isSelected ? View.GONE : View.VISIBLE);
        TextView itemText = row.findViewById(ID_MORPHE_ITEM_TEXT);
        itemText.setText(label);
        itemText.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        FrameLayout speakerButton = new FrameLayout(context);
        LinearLayout.LayoutParams buttonLp = new LinearLayout.LayoutParams(Dim.dp40, Dim.dp40);
        buttonLp.setMarginStart(Dim.dp8);
        speakerButton.setLayoutParams(buttonLp);

        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(Color.argb(20, Color.red(fg), Color.green(fg), Color.blue(fg)));
        speakerButton.setBackground(new RippleDrawable(
                ColorStateList.valueOf(rippleColor), circle, new ShapeDrawable(new OvalShape())));

        ImageView speaker = new ImageView(context);
        speaker.setImageResource(speakerRes);
        speaker.setColorFilter(new PorterDuffColorFilter(secondaryColor(fg), PorterDuff.Mode.SRC_IN));
        speaker.setLayoutParams(new FrameLayout.LayoutParams(Dim.dp24, Dim.dp24, Gravity.CENTER));
        speakerButton.addView(speaker);
        speakerButton.setOnClickListener(v -> VoiceOverTranslationPatch.testSpeak(value));
        ((LinearLayout) row).addView(speakerButton);

        row.setOnClickListener(v -> {
            if (isSystem) {
                Settings.VOT_USE_NATIVE_TTS.save(true);
            } else {
                Settings.VOT_USE_NATIVE_TTS.save(false);
                Settings.VOT_TTS_VOICE_TYPE.save(value);
            }
            onChanged.run();
            pickerDialog.dismiss();
        });

        listLayout.addView(row);
    }

    private static View makeSectionHeader(Context context, String label, int fg) {
        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.HORIZONTAL);
        container.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams containerParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        containerParams.setMargins(0, Dim.dp8, 0, 0);
        container.setLayoutParams(containerParams);

        int lineColor = Color.argb(30, Color.red(fg), Color.green(fg), Color.blue(fg));

        View lineStart = new View(context);
        lineStart.setBackgroundColor(lineColor);
        LinearLayout.LayoutParams lineStartParams = new LinearLayout.LayoutParams(0, Dim.dp1, 1f);
        lineStartParams.setMarginEnd(Dim.dp8);
        lineStart.setLayoutParams(lineStartParams);
        container.addView(lineStart);

        TextView tv = new TextView(context);
        tv.setText(label);
        tv.setTextSize(11);
        tv.setTextColor(secondaryColor(fg));
        tv.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        container.addView(tv);

        View lineEnd = new View(context);
        lineEnd.setBackgroundColor(lineColor);
        LinearLayout.LayoutParams lineEndParams = new LinearLayout.LayoutParams(0, Dim.dp1, 1f);
        lineEndParams.setMarginStart(Dim.dp8);
        lineEnd.setLayoutParams(lineEndParams);
        container.addView(lineEnd);

        return container;
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
            VoiceOverTranslationPatch.preloadTestVoices();
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
