package dev.jason.gboardpatches.extension.aibar;

import java.util.List;
import java.util.function.Supplier;

/**
 * Finds a completed trigger in the text before the cursor and says how to expand it. Works only
 * on the text before the cursor, which is all the keyboard needs to rewrite it.
 */
public final class AiBarExpander {
    private static final String DELIMITERS = ".,!?;:)]}\"'";
    private static final String DATE = "{date}";
    private static final String TIME = "{time}";
    private static final String CURSOR = "{cursor}";
    private static final String CLIPBOARD = "{clipboard}";
    private static final String[] PLACEHOLDERS = {DATE, TIME, CURSOR, CLIPBOARD};

    private final Supplier<String> date;
    private final Supplier<String> time;
    private final Supplier<String> clipboard;

    public AiBarExpander(Supplier<String> date, Supplier<String> time, Supplier<String> clipboard) {
        this.date = date;
        this.time = time;
        this.clipboard = clipboard;
    }

    /** Delete {@code deleteBefore} characters before the cursor, insert {@code insertion}, then step back. */
    public static final class Plan {
        public final int deleteBefore;
        public final String insertion;
        /** How far before the end of {@code insertion} the cursor goes ({cursor}); 0 = at the end. */
        public final int cursorBackFromEnd;
        public final String trigger;

        Plan(int deleteBefore, String insertion, int cursorBackFromEnd, String trigger) {
            this.deleteBefore = deleteBefore;
            this.insertion = insertion;
            this.cursorBackFromEnd = cursorBackFromEnd;
            this.trigger = trigger;
        }
    }

    public static final class Revert {
        public final int deleteBefore;
        public final String insertion;

        Revert(int deleteBefore, String insertion) {
            this.deleteBefore = deleteBefore;
            this.insertion = insertion;
        }
    }

    public Plan plan(String before, List<AiBarShortcut> shortcuts) {
        if (before == null || before.isEmpty()) return null;
        AiBarShortcut best = null;
        String bestDelimiter = "";
        for (AiBarShortcut shortcut : shortcuts) {
            String trigger = shortcut.trigger;
            if (trigger.isEmpty()) continue;
            String delimiter;
            String body;
            if (shortcut.expandOn == AiBarShortcut.ExpandOn.IMMEDIATELY) {
                delimiter = "";
                body = before;
            } else {
                char last = before.charAt(before.length() - 1);
                if (!Character.isWhitespace(last) && DELIMITERS.indexOf(last) < 0) continue;
                delimiter = String.valueOf(last);
                body = before.substring(0, before.length() - 1);
            }
            if (!body.endsWith(trigger) || !startsWord(body, body.length() - trigger.length())) continue;
            if (best == null || trigger.length() > best.trigger.length()) {
                best = shortcut;
                bestDelimiter = delimiter;
            }
        }
        if (best == null) return null;
        StringBuilder rendered = new StringBuilder();
        int cursorAt = render(best.expansion, rendered);
        String insertion = rendered + bestDelimiter;
        int back = cursorAt < 0 ? 0 : insertion.length() - cursorAt;
        return new Plan(best.trigger.length() + bestDelimiter.length(), insertion, back, best.trigger);
    }

    /**
     * If {@code before} is what the expansion left with only its last character deleted, the edit
     * that puts the trigger back. Expansions that moved the cursor ({cursor}) don't revert.
     */
    public static Revert revert(Plan plan, String before) {
        if (plan == null || plan.cursorBackFromEnd != 0 || plan.insertion.isEmpty() || before == null) return null;
        String afterBackspace = plan.insertion.substring(0, plan.insertion.length() - 1);
        if (!before.endsWith(afterBackspace)) return null;
        return new Revert(afterBackspace.length(), plan.trigger);
    }

    /** A trigger must not be the tail of a longer word ("abtw" doesn't expand "btw"). */
    private static boolean startsWord(String text, int start) {
        return start == 0 || !Character.isLetterOrDigit(text.charAt(start - 1));
    }

    /** Appends the expansion with placeholders filled; returns the {cursor} offset or -1. */
    private int render(String template, StringBuilder out) {
        int cursorAt = -1;
        int i = 0;
        while (i < template.length()) {
            String placeholder = null;
            for (String p : PLACEHOLDERS) if (template.startsWith(p, i)) placeholder = p;
            if (placeholder == null) {
                out.append(template.charAt(i));
                i++;
                continue;
            }
            switch (placeholder) {
                case DATE -> out.append(date.get());
                case TIME -> out.append(time.get());
                case CLIPBOARD -> out.append(clipboard.get());
                default -> { if (cursorAt < 0) cursorAt = out.length(); }
            }
            i += placeholder.length();
        }
        return cursorAt;
    }
}
