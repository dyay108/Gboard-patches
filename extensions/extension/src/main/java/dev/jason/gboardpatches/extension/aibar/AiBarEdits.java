package dev.jason.gboardpatches.extension.aibar;

/** Which part of the field a button acts on, and the edits that change it. Pure, no Android. */
public final class AiBarEdits {
    private AiBarEdits() {}

    /** [start, end) of {@code fullText}. */
    public static final class Target {
        public final String fullText;
        public final int start;
        public final int end;

        Target(String fullText, int start, int end) {
            this.fullText = fullText;
            this.start = start;
            this.end = end;
        }

        public String text() {
            return fullText.substring(start, end);
        }

        public boolean isEmpty() {
            return text().isBlank();
        }
    }

    /** {@code original} becomes {@code newText} by replacing [start, end) with {@code insertion}. */
    public static final class Edit {
        public final String original;
        public final String newText;
        public final int start;
        public final int end;
        public final String insertion;

        Edit(String original, int start, int end, String insertion) {
            this.original = original;
            this.start = start;
            this.end = end;
            this.insertion = insertion;
            this.newText = original.substring(0, start) + insertion + original.substring(end);
        }

        /** Where the cursor goes: right after the inserted text. */
        public int cursor() {
            return start + insertion.length();
        }
    }

    /** The selection when there is one, otherwise the whole field. */
    public static Target target(String text, int selectionStart, int selectionEnd) {
        int start = Math.min(selectionStart, selectionEnd);
        int end = Math.max(selectionStart, selectionEnd);
        boolean hasSelection = start >= 0 && end <= text.length() && start < end;
        return hasSelection ? new Target(text, start, end) : new Target(text, 0, text.length());
    }

    public static Edit replace(Target target, String result) {
        return new Edit(target.fullText, target.start, target.end, result);
    }

    /** Adds {@code result} on a new line right below the target. */
    public static Edit append(Target target, String result) {
        String insertion = target.fullText.isEmpty() ? result : "\n" + result;
        return new Edit(target.fullText, target.end, target.end, insertion);
    }

    /** The edit that turns the result of {@code edit} back into its original text. */
    public static Edit undo(Edit edit) {
        return new Edit(edit.newText, edit.start, edit.start + edit.insertion.length(),
                edit.original.substring(edit.start, edit.end));
    }
}
