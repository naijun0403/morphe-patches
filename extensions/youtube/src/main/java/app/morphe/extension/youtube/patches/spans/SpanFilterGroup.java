/*
 * Copyright 2026 Morphe.
 * https://github.com/MorpheApp/morphe-patches
 *
 * Original hard forked code:
 * https://github.com/ReVanced/revanced-patches/commit/724e6d61b2ecd868c1a9a37d465a688e83a74799
 *
 * See the included NOTICE file for GPLv3 §7(b) and §7(c) terms that apply to Morphe contributions.
 */

package app.morphe.extension.youtube.patches.spans;

import androidx.annotation.NonNull;

import app.morphe.extension.shared.settings.BooleanSetting;

abstract class SpanFilterGroup<T> {
    final static class FilterGroupResult {
        private BooleanSetting setting;
        private int matchedIndex;

        FilterGroupResult() {
            this(null, -1);
        }

        FilterGroupResult(BooleanSetting setting, int matchedIndex) {
            setValues(setting, matchedIndex);
        }

        public void setValues(BooleanSetting setting, int matchedIndex) {
            this.setting = setting;
            this.matchedIndex = matchedIndex;
        }

        public BooleanSetting getSetting() {
            return setting;
        }

        public boolean isFiltered() {
            return matchedIndex >= 0;
        }
    }

    protected final BooleanSetting setting;
    protected final T[] filters;

    @SafeVarargs
    public SpanFilterGroup(final BooleanSetting setting, final T... filters) {
        this.setting = setting;
        this.filters = filters;
        if (filters.length == 0) {
            throw new IllegalArgumentException("Must use one or more filter patterns (zero specified)");
        }
    }

    public boolean isEnabled() {
        return setting == null || setting.get();
    }

    @SuppressWarnings("BooleanMethodIsAlwaysInverted")
    public boolean includeInSearch() {
        return isEnabled() || !setting.rebootApp;
    }

    @NonNull
    @Override
    public String toString() {
        return getClass().getSimpleName() + ": " + (setting == null ? "(null setting)" : setting);
    }

    public abstract FilterGroupResult check(final T stack);
}

class StringSpanFilterGroup extends SpanFilterGroup<String> {

    public StringSpanFilterGroup(final BooleanSetting setting, final String... filters) {
        super(setting, filters);
    }

    @Override
    public FilterGroupResult check(final String string) {
        int matchedIndex = -1;
        if (isEnabled()) {
            for (String pattern : filters) {
                if (!string.isEmpty()) {
                    final int indexOf = string.indexOf(pattern);
                    if (indexOf >= 0) {
                        matchedIndex = indexOf;
                        break;
                    }
                }
            }
        }
        return new FilterGroupResult(setting, matchedIndex);
    }
}