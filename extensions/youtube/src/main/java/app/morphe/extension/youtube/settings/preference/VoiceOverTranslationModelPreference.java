package app.morphe.extension.youtube.settings.preference;

import static app.morphe.extension.shared.StringRef.str;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RoundRectShape;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.AttributeSet;
import android.util.Pair;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.annotation.Nullable;

import java.util.function.Function;

import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.preference.CustomDialogListPreference;
import app.morphe.extension.shared.ui.CustomDialog;
import app.morphe.extension.shared.ui.Dim;
import app.morphe.extension.youtube.settings.Settings;

@SuppressWarnings({"unused", "deprecation"})
public class VoiceOverTranslationModelPreference extends CustomDialogListPreference {

    private static final String CUSTOM_SENTINEL = "custom";

    private static final String[] PRESET_IDS = {
            "mistralai/mistral-nemo",
            "google/gemma-3-4b-it",
            "google/gemma-3-27b-it"
    };

    private EditText editText;
    private ListPreferenceArrayAdapter adapter;

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public VoiceOverTranslationModelPreference(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public VoiceOverTranslationModelPreference(Context context) {
        super(context);
    }

    private static boolean isPreset(String modelId) {
        for (String preset : PRESET_IDS) {
            if (preset.equals(modelId)) return true;
        }
        return false;
    }

    private void updateEntries() {
        setEntries(new CharSequence[]{
                str("morphe_vot_openrouter_model_mistral_nemo"),
                str("morphe_vot_openrouter_model_gemma3_4b"),
                str("morphe_vot_openrouter_model_gemma3_27b"),
                str("morphe_vot_openrouter_model_custom")
        });
        setEntryValues(new CharSequence[]{
                "mistralai/mistral-nemo",
                "google/gemma-3-4b-it",
                "google/gemma-3-27b-it",
                CUSTOM_SENTINEL
        });
    }

    @Override
    public void setSummary(CharSequence summary) {
        // Suppress auto-summary set by the preference fragment.
    }

    @Override
    protected void showDialog(@Nullable Bundle state) {
        updateEntries();

        Context context = getContext();
        String currentModel = Settings.VOT_OPENROUTER_MODEL.get();
        boolean isCustom = !isPreset(currentModel);
        String initialSelection = isCustom ? CUSTOM_SENTINEL : currentModel;

        LinearLayout contentLayout = new LinearLayout(context);
        contentLayout.setOrientation(LinearLayout.VERTICAL);

        ListView listView = new ListView(context);
        listView.setId(android.R.id.list);
        listView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);

        adapter = new ListPreferenceArrayAdapter(
                context, LAYOUT_MORPHE_CUSTOM_LIST_ITEM_CHECKED,
                getEntries(), getEntryValues(), initialSelection);
        listView.setAdapter(adapter);

        Function<String, Void> syncListSelection = typedValue -> {
            String selection = isPreset(typedValue) ? typedValue : CUSTOM_SENTINEL;
            adapter.setSelectedValue(selection);
            adapter.notifyDataSetChanged();
            return null;
        };

        listView.setOnItemClickListener((parent, view, position, id) -> {
            String selectedValue = getEntryValues()[position].toString();
            if (selectedValue.equals(CUSTOM_SENTINEL)) {
                String saved = Settings.VOT_OPENROUTER_MODEL.get();
                editText.setText(isPreset(saved) ? "" : saved);
                editText.setEnabled(true);
                editText.requestFocus();
            } else {
                editText.setText(selectedValue);
                editText.setEnabled(false);
            }
            editText.setSelection(editText.getText().length());
            adapter.setSelectedValue(selectedValue);
            adapter.notifyDataSetChanged();
        });

        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        listParams.bottomMargin = Dim.dp16;
        contentLayout.addView(listView, listParams);

        editText = createEditText(context, currentModel, isCustom, syncListSelection);
        contentLayout.addView(editText);

        Pair<Dialog, LinearLayout> dialogPair = CustomDialog.create(
                context,
                getTitle() != null ? getTitle().toString() : "",
                null, null, null,
                () -> {
                    String newValue = editText.getText().toString().trim();
                    if (newValue.isEmpty()) return;
                    if (callChangeListener(newValue)) setValue(newValue);
                },
                () -> {},
                null, null, false
        );

        LinearLayout.LayoutParams contentParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1.0f);
        dialogPair.second.addView(contentLayout, dialogPair.second.getChildCount() - 1, contentParams);
        dialogPair.first.show();
    }

    private EditText createEditText(Context context, String initialValue, boolean isCustom,
                                    Function<String, Void> textChangeCallback) {
        EditText editText = new EditText(context);
        editText.setText(initialValue);
        editText.setSelection(initialValue.length());
        editText.setHint(str("morphe_vot_openrouter_model_hint"));
        editText.setSingleLine(true);
        editText.setTextSize(16);
        editText.setEnabled(isCustom);

        editText.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable edit) {
                textChangeCallback.apply(edit.toString().trim());
            }
        });

        ShapeDrawable background = new ShapeDrawable(new RoundRectShape(
                Dim.roundedCorners(10), null, null));
        background.getPaint().setColor(Utils.getEditTextBackground());
        editText.setPadding(Dim.dp8, Dim.dp8, Dim.dp8, Dim.dp8);
        editText.setBackground(background);
        editText.setClipToOutline(true);

        return editText;
    }
}
