package dev.jason.gboardpatches.extension.aibar;

import java.util.ArrayList;
import java.util.List;

/** Checks for values typed in AI Bar settings. Each returns an error message, or null when valid. */
final class AiBarValidation {
    private AiBarValidation() {}

    static String label(String value) {
        return value == null || value.isBlank() ? "Add a label" : null;
    }

    static String prompt(String value) {
        return value == null || value.isBlank() ? "Add a prompt" : null;
    }

    static String trigger(String value, String ownId, List<AiBarShortcut> all) {
        if (value == null || value.isEmpty()) return "Add a trigger";
        for (char c : value.toCharArray()) if (Character.isWhitespace(c)) return "A trigger can't contain spaces";
        for (AiBarShortcut other : all) {
            if (!other.id.equals(ownId) && other.trigger.equals(value)) return "Another shortcut already uses " + value;
        }
        return null;
    }

    /** Package names separated by commas, spaces or new lines; duplicates and blanks removed. */
    static List<String> packages(String value) {
        List<String> result = new ArrayList<>();
        for (String part : value.split("[,\\s]+")) {
            String name = part.trim();
            if (!name.isEmpty() && !result.contains(name)) result.add(name);
        }
        return result;
    }
}
