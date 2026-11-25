package dev.jason.gboardpatches.extension.aibar;

import java.util.Locale;

/** One AI toolbar button. {@code prompt} uses {text}; {@code model} overrides the provider's model. */
public final class AiBarButton {
    public enum Mode { REPLACE, APPEND, POPUP }

    public static final String ICON_WAND = "WAND";
    public static final String ICON_SPARKLE = "SPARKLE";
    public static final String ICON_QUESTION = "QUESTION";
    public static final String ICON_TRANSLATE = "TRANSLATE";
    public static final String ICON_LINES = "LINES";
    public static final String[] ICONS = {ICON_WAND, ICON_SPARKLE, ICON_QUESTION, ICON_TRANSLATE, ICON_LINES};

    static final String TOKEN_PREFIX = "aibar_";

    public final String id;
    public final String label;
    public final String prompt;
    public final Mode mode;
    /** One of {@link #ICONS}, or null to show the label's first letters as a badge. */
    public final String icon;
    public final String model;

    public AiBarButton(String id, String label, String prompt, Mode mode, String icon, String model) {
        this.id = id;
        this.label = label;
        this.prompt = prompt;
        this.mode = mode;
        this.icon = icon;
        this.model = model == null || model.isBlank() ? null : model.trim();
    }

    /** The Gboard Access Point token for this button: snake_case, which is all Gboard keeps. */
    public String toolbarToken() {
        return TOKEN_PREFIX + id.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "_");
    }

    /**
     * Name of the drawable the toolbar shows: the chosen symbol; else a badge with the label's
     * leading letters when there are one or two of them ("ES", "De-DE" → DE), or just the first
     * letter of a longer word ("ASK" → A); else the sparkle.
     */
    public String iconResourceName() {
        if (icon != null) return "aibar_icon_" + icon.toLowerCase(Locale.ROOT);
        StringBuilder run = new StringBuilder();
        for (char c : label.trim().toCharArray()) {
            char lower = Character.toLowerCase(c);
            if (lower < 'a' || lower > 'z') break;
            run.append(lower);
        }
        if (run.length() == 0) return "aibar_icon_sparkle";
        return "aibar_badge_" + (run.length() <= 2 ? run : run.substring(0, 1));
    }

    AiBarButton withId(String newId) {
        return new AiBarButton(newId, label, prompt, mode, icon, model);
    }
}
