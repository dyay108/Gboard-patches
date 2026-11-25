package dev.jason.gboardpatches.extension.aibar;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Everything AI Bar stores except the API keys. The JSON layout matches the AI Bar app's backups,
 * so an export from the app imports here unchanged.
 */
public final class AiBarConfig {
    public enum Provider { ANTHROPIC, OPENAI_COMPATIBLE }

    public Provider provider = Provider.ANTHROPIC;
    public String anthropicModel = "";
    public String openAiBaseUrl = "";
    public String openAiModel = "";
    public List<AiBarButton> buttons = new ArrayList<>();
    public List<AiBarShortcut> shortcuts = new ArrayList<>();
    public boolean expanderEnabled = true;
    public boolean revertOnBackspace = true;
    public List<String> excludedPackages = new ArrayList<>();

    public static AiBarConfig defaults() {
        AiBarConfig config = new AiBarConfig();
        config.buttons.add(new AiBarButton("default-es", "ES", translate("Spanish"), AiBarButton.Mode.REPLACE, null, null));
        config.buttons.add(new AiBarButton("default-fr", "FR", translate("French"), AiBarButton.Mode.REPLACE, null, null));
        config.buttons.add(new AiBarButton("default-en", "EN", translate("English"), AiBarButton.Mode.REPLACE, null, null));
        config.buttons.add(new AiBarButton("default-improve", "Improve",
                "Fix the grammar, spelling and clarity of the text inside the <text> tags. Keep its language, "
                        + "tone, meaning, formatting, line breaks and emoji. Return ONLY the improved text: no quotes, "
                        + "no preamble, no explanations.\n\n<text>{text}</text>",
                AiBarButton.Mode.REPLACE, AiBarButton.ICON_WAND, null));
        config.buttons.add(new AiBarButton("default-ask", "ASK",
                "Treat the text inside the <text> tags as a question and answer it concisely, in the "
                        + "language it is written in.\n\n<text>{text}</text>",
                AiBarButton.Mode.POPUP, null, null));
        return config;
    }

    private static String translate(String language) {
        return "Translate the text inside the <text> tags to " + language + ". Return ONLY the translated text: no quotes, "
                + "no preamble, no notes. Keep the original formatting, line breaks and emoji. If it is already "
                + "in " + language + ", return it unchanged.\n\n<text>{text}</text>";
    }

    public AiBarButton button(String id) {
        for (AiBarButton button : buttons) if (button.id.equals(id)) return button;
        return null;
    }

    // --- JSON ---

    public String toJson() {
        try {
            JSONObject root = new JSONObject();
            root.put("version", 1);
            JSONArray buttonArray = new JSONArray();
            for (AiBarButton b : buttons) {
                buttonArray.put(new JSONObject()
                        .put("id", b.id).put("label", b.label).put("prompt", b.prompt)
                        .put("mode", b.mode.name()).put("icon", b.icon == null ? JSONObject.NULL : b.icon)
                        .put("model", b.model == null ? JSONObject.NULL : b.model).put("kind", "AI"));
            }
            root.put("buttons", buttonArray);
            root.put("provider", new JSONObject()
                    .put("active", provider.name()).put("anthropicModel", anthropicModel)
                    .put("openAiBaseUrl", openAiBaseUrl).put("openAiModel", openAiModel));
            JSONArray shortcutArray = new JSONArray();
            for (AiBarShortcut s : shortcuts) {
                shortcutArray.put(new JSONObject().put("id", s.id).put("trigger", s.trigger)
                        .put("expansion", s.expansion).put("expandOn", s.expandOn.name()));
            }
            root.put("shortcuts", shortcutArray);
            root.put("expander", new JSONObject().put("enabled", expanderEnabled)
                    .put("revertOnBackspace", revertOnBackspace)
                    .put("excludedPackages", new JSONArray(excludedPackages)));
            return root.toString(2);
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Reads stored settings; anything missing keeps its default. */
    public static AiBarConfig fromJson(String json) throws AiBarException {
        return importBackup(json, defaults(), false).config;
    }

    /** The merged settings, and the user-facing names of the sections the file replaced. */
    public static final class ImportResult {
        public final AiBarConfig config;
        public final List<String> sections;

        ImportResult(AiBarConfig config, List<String> sections) {
            this.config = config;
            this.sections = sections;
        }
    }

    /** Replaces only the sections present in {@code json}, so a shortcuts-only file keeps your buttons. */
    public static ImportResult importBackup(String json, AiBarConfig current) throws AiBarException {
        return importBackup(json, current, true);
    }

    private static ImportResult importBackup(String json, AiBarConfig current, boolean requireSection) throws AiBarException {
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException | NullPointerException e) {
            throw new AiBarException("This file isn't a valid AI Bar backup");
        }
        Map<String, String> known = new LinkedHashMap<>();
        known.put("buttons", "buttons");
        known.put("provider", "provider");
        known.put("shortcuts", "shortcuts");
        known.put("expander", "text shortcut settings");
        List<String> sections = new ArrayList<>();
        for (Map.Entry<String, String> entry : known.entrySet()) if (root.has(entry.getKey())) sections.add(entry.getValue());
        if (requireSection && sections.isEmpty()) throw new AiBarException("This file has no buttons, provider or shortcuts to import");

        AiBarConfig config = current.copy();
        try {
            if (root.has("buttons")) config.buttons = readButtons(root.getJSONArray("buttons"));
            if (root.has("provider")) readProvider(root.getJSONObject("provider"), config);
            if (root.has("shortcuts")) config.shortcuts = readShortcuts(root.getJSONArray("shortcuts"));
            if (root.has("expander")) {
                JSONObject expander = root.getJSONObject("expander");
                config.expanderEnabled = expander.optBoolean("enabled", true);
                config.revertOnBackspace = expander.optBoolean("revertOnBackspace", true);
                config.excludedPackages = new ArrayList<>();
                JSONArray packages = expander.optJSONArray("excludedPackages");
                if (packages != null) for (int i = 0; i < packages.length(); i++) config.excludedPackages.add(packages.getString(i));
            }
        } catch (JSONException | IllegalArgumentException e) {
            throw new AiBarException("This file isn't a valid AI Bar backup");
        }
        return new ImportResult(config, sections);
    }

    private static List<AiBarButton> readButtons(JSONArray array) throws JSONException, AiBarException {
        List<AiBarButton> buttons = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject b = array.getJSONObject(i);
            // The AI Bar app's settings button has no meaning inside Gboard.
            if ("SETTINGS".equals(b.optString("kind", "AI"))) continue;
            String label = b.optString("label", "").trim();
            String prompt = b.optString("prompt", "");
            if (label.isEmpty()) throw new AiBarException("A button in this backup has no label");
            if (prompt.isBlank()) throw new AiBarException("Button \"" + label + "\" has no prompt");
            String icon = b.isNull("icon") ? null : b.optString("icon", null);
            if (icon != null && !java.util.Arrays.asList(AiBarButton.ICONS).contains(icon)) icon = null;
            String model = b.isNull("model") ? null : b.optString("model", null);
            AiBarButton button = new AiBarButton(uniqueId(b.optString("id", ""), ids), label, prompt,
                    AiBarButton.Mode.valueOf(b.optString("mode", "REPLACE")), icon, model);
            buttons.add(button);
        }
        return buttons;
    }

    private static List<AiBarShortcut> readShortcuts(JSONArray array) throws JSONException, AiBarException {
        List<AiBarShortcut> shortcuts = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject s = array.getJSONObject(i);
            String trigger = s.optString("trigger", "");
            if (trigger.isBlank()) throw new AiBarException("A shortcut in this backup has no trigger");
            shortcuts.add(new AiBarShortcut(uniqueId(s.optString("id", ""), ids), trigger, s.optString("expansion", ""),
                    AiBarShortcut.ExpandOn.valueOf(s.optString("expandOn", "AFTER_SPACE_OR_PUNCTUATION"))));
        }
        return shortcuts;
    }

    private static void readProvider(JSONObject p, AiBarConfig config) {
        config.provider = Provider.valueOf(p.optString("active", "ANTHROPIC"));
        config.anthropicModel = p.optString("anthropicModel", "");
        config.openAiBaseUrl = p.optString("openAiBaseUrl", "");
        config.openAiModel = p.optString("openAiModel", "");
    }

    private static String uniqueId(String id, Set<String> seen) {
        String result = id == null || id.isBlank() || seen.contains(id) ? newId() : id;
        seen.add(result);
        return result;
    }

    public static String newId() {
        return UUID.randomUUID().toString();
    }

    public AiBarConfig copy() {
        AiBarConfig copy = new AiBarConfig();
        copy.provider = provider;
        copy.anthropicModel = anthropicModel;
        copy.openAiBaseUrl = openAiBaseUrl;
        copy.openAiModel = openAiModel;
        copy.buttons = new ArrayList<>(buttons);
        copy.shortcuts = new ArrayList<>(shortcuts);
        copy.expanderEnabled = expanderEnabled;
        copy.revertOnBackspace = revertOnBackspace;
        copy.excludedPackages = new ArrayList<>(excludedPackages);
        return copy;
    }
}
