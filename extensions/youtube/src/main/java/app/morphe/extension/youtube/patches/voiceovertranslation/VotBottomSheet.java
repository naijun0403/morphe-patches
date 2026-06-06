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

        int fg = Utils.getAppForegroundColor();

        String[] langEntries = context.getResources().getStringArray(
                ResourceUtils.getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entries"));
        String[] langValues = context.getResources().getStringArray(
                ResourceUtils.getIdentifierOrThrow(ResourceType.ARRAY, "morphe_vot_caption_language_entry_values"));

        root.addView(makeTitle(context, str("morphe_vot_enabled_title"), fg));
        root.addView(makeLanguageRow(context, str("morphe_vot_caption_language_title"), fg, langEntries, langValues));
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

    private static TextView makeTitle(Context context, String text, int fgColor) {
        TextView tv = new TextView(context);
        tv.setText(text);
        tv.setTextSize(18);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setTextColor(fgColor);
        tv.setGravity(Gravity.CENTER_HORIZONTAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(Dim.dp16, Dim.dp16, Dim.dp16, Dim.dp8);
        tv.setLayoutParams(params);
        return tv;
    }

    private static View makeDivider(Context context, int fgColor) {
        View divider = new View(context);
        divider.setBackgroundColor(Color.argb(30,
                Color.red(fgColor), Color.green(fgColor), Color.blue(fgColor)));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Dim.dp1);
        params.setMargins(Dim.dp16, Dim.dp4, Dim.dp16, Dim.dp4);
        divider.setLayoutParams(params);
        return divider;
    }

    private static LinearLayout makeLanguageRow(Context context, String label, int fgColor,
                                                String[] langEntries, String[] langValues) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(Dim.dp48);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(Dim.dp16, 0, Dim.dp16, 0);
        row.setLayoutParams(rowParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(15);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(labelView);

        int secondaryColor = Color.argb(153,
                Color.red(fgColor), Color.green(fgColor), Color.blue(fgColor));
        TextView valueView = new TextView(context);
        valueView.setText(getLangDisplayName(Settings.VOT_CAPTION_LANGUAGE.get(), langEntries, langValues));
        valueView.setTextColor(secondaryColor);
        valueView.setTextSize(15);
        valueView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        row.addView(valueView);

        row.setOnClickListener(v -> showLanguagePicker(context, label, langEntries, langValues, valueView));

        return row;
    }

    private static void showLanguagePicker(Context context, String title,
                                            String[] langEntries, String[] langValues, TextView valueView) {
        SheetBottomDialog.DraggableLinearLayout pickerRoot =
                SheetBottomDialog.createMainLayout(context, getDialogBackgroundColor());

        pickerRoot.addView(makeTitle(context, title, Utils.getAppForegroundColor()));

        ListView listView = new ListView(context);
        listView.setDivider(null);

        CustomDialogListPreference.ListPreferenceArrayAdapter adapter =
                new CustomDialogListPreference.ListPreferenceArrayAdapter(
                        context,
                        LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED,
                        langEntries,
                        langValues,
                        Settings.VOT_CAPTION_LANGUAGE.get()
                );
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

    private static LinearLayout makeSliderRow(Context context, String label, int initialValue,
                                               int fgColor, IntConsumer onChanged) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        rowParams.setMargins(Dim.dp16, Dim.dp8, Dim.dp16, Dim.dp16);
        row.setLayoutParams(rowParams);

        TextView labelView = new TextView(context);
        labelView.setText(label);
        labelView.setTextColor(fgColor);
        labelView.setTextSize(15);
        labelView.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
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

    @FunctionalInterface
    private interface IntConsumer {
        void accept(int value);
    }
}
