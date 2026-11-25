package dev.jason.gboardpatches.extension.aibar;

/** A text shortcut: typing {@code trigger} expands to {@code expansion}. */
public final class AiBarShortcut {
    public enum ExpandOn { IMMEDIATELY, AFTER_SPACE_OR_PUNCTUATION }

    public final String id;
    public final String trigger;
    public final String expansion;
    public final ExpandOn expandOn;

    public AiBarShortcut(String id, String trigger, String expansion, ExpandOn expandOn) {
        this.id = id;
        this.trigger = trigger;
        this.expansion = expansion;
        this.expandOn = expandOn;
    }

    AiBarShortcut withId(String newId) {
        return new AiBarShortcut(newId, trigger, expansion, expandOn);
    }
}
