package dev.jason.gboardpatches.extension.aibar;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jason.gboardpatches.extension.R;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.CommandRow;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.DangerRow;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.FeatureHost;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.NavigationRow;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.Row;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.Screen;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.Section;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.SelectorRow;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.SwitchRow;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract.ToggleRow;
import dev.jason.gboardpatches.extension.settings.GboardSettingsText;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Settings → Keyboard tools → AI Bar: provider, toolbar buttons, text shortcuts and backup. */
public final class AiBarSettingsFeature implements GboardPatchesSettingsContract.Feature {
    private final Context context;

    public AiBarSettingsFeature(Context context) {
        this.context = context;
    }

    @Override public String getEntryTitle() { return t(R.string.gboard_ai_bar_title); }

    @Override public String getEntrySummary() { return t(R.string.gboard_ai_bar_summary); }

    @Override public boolean isAvailable(Context host) {
        return GboardPatchesFeatureAvailability.hasFeature(host, GboardPatchesFeatureAvailability.FEATURE_AI_BAR);
    }

    @Override public Screen buildScreen(FeatureHost host) {
        Context owner = host.getContext();
        AiBarConfig config = AiBarStore.config(owner);
        boolean on = AiBarStore.enabled(owner);
        List<Section> sections = new ArrayList<>();

        sections.add(new Section(t(R.string.gboard_ai_bar_section_feature), List.of(
                new ToggleRow(getEntryTitle(), t(R.string.gboard_ai_bar_toggle_summary), true, on,
                        value -> AiBarStore.setEnabled(owner, value))
                        .setRestartRequired(AiBarStore.KEY_ENABLED)
                        .setMasterToggle(true))));

        sections.add(new Section(t(R.string.gboard_ai_bar_section_provider), providerRows(host, config, on)));
        sections.add(new Section(t(R.string.gboard_ai_bar_section_buttons), buttonRows(host, config, on)));
        sections.add(new Section(t(R.string.gboard_ai_bar_section_shortcuts), shortcutRows(host, config, on)));
        sections.add(new Section(t(R.string.gboard_ai_bar_section_backup), List.of(
                new CommandRow(t(R.string.gboard_ai_bar_import), t(R.string.gboard_ai_bar_import_summary), true,
                        () -> importBackup(host)),
                new CommandRow(t(R.string.gboard_ai_bar_export), t(R.string.gboard_ai_bar_export_summary), true,
                        () -> GboardPatchesSettingsContract.createTextDocument(host, "ai-bar-backup.json",
                                "application/json", AiBarStore.config(owner).toJson(), null)))));

        return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), getEntryTitle(),
                getEntrySummary(), List.of(), sections, GboardPatchesSettingsContract.RefreshPolicy.none(),
                GboardPatchesSettingsContract.PanelStyle.FLAT);
    }

    // --- Provider ---

    private List<Row> providerRows(FeatureHost host, AiBarConfig config, boolean on) {
        Context owner = host.getContext();
        boolean anthropic = config.provider == AiBarConfig.Provider.ANTHROPIC;
        List<Row> rows = new ArrayList<>();
        rows.add(new SelectorRow(t(R.string.gboard_ai_bar_provider), "",
                t(anthropic ? R.string.gboard_ai_bar_provider_anthropic : R.string.gboard_ai_bar_provider_openai), on,
                () -> GboardPatchesSettingsContract.showChoiceDialog(host, t(R.string.gboard_ai_bar_provider),
                        new String[] {t(R.string.gboard_ai_bar_provider_anthropic), t(R.string.gboard_ai_bar_provider_openai)},
                        new String[] {AiBarConfig.Provider.ANTHROPIC.name(), AiBarConfig.Provider.OPENAI_COMPATIBLE.name()},
                        config.provider.name(), null, null,
                        value -> update(host, c -> c.provider = AiBarConfig.Provider.valueOf(value)))));
        String model = anthropic ? config.anthropicModel : config.openAiModel;
        rows.add(new SelectorRow(t(R.string.gboard_ai_bar_model), t(R.string.gboard_ai_bar_model_summary),
                model.isEmpty() ? t(R.string.gboard_ai_bar_not_set) : model, on,
                () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_model), "", model,
                        value -> update(host, c -> {
                            if (c.provider == AiBarConfig.Provider.ANTHROPIC) c.anthropicModel = value.trim();
                            else c.openAiModel = value.trim();
                        }))));
        if (!anthropic) {
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_endpoint),
                    t(config.openAiBaseUrl.trim().startsWith("http://")
                            ? R.string.gboard_ai_bar_endpoint_insecure : R.string.gboard_ai_bar_endpoint_summary),
                    config.openAiBaseUrl.isEmpty() ? t(R.string.gboard_ai_bar_not_set) : config.openAiBaseUrl, on,
                    () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_endpoint),
                            "https://api.openai.com/v1", config.openAiBaseUrl,
                            value -> update(host, c -> c.openAiBaseUrl = value.trim()))));
        }
        boolean hasKey = AiBarStore.hasApiKey(owner, config.provider);
        rows.add(new SelectorRow(t(R.string.gboard_ai_bar_api_key), t(R.string.gboard_ai_bar_api_key_summary),
                t(hasKey ? R.string.gboard_ai_bar_saved : R.string.gboard_ai_bar_not_set), on,
                () -> AiBarDialogs.secret(host, t(R.string.gboard_ai_bar_api_key), t(R.string.gboard_ai_bar_api_key_hint), value -> {
                    try {
                        AiBarStore.setApiKey(owner, config.provider, value);
                    } catch (Exception e) {
                        throw new IllegalArgumentException(t(R.string.gboard_ai_bar_api_key_failed));
                    }
                })));
        rows.add(new CommandRow(t(R.string.gboard_ai_bar_test), t(R.string.gboard_ai_bar_test_summary), on,
                () -> testConnection(host)));
        return rows;
    }

    private void testConnection(FeatureHost host) {
        Context owner = host.getContext();
        AiBarLlm.Client client = AiBarStore.client(owner, AiBarStore.config(owner), null);
        Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                String answer = client.complete(AiBarPrompts.SYSTEM, "Reply with exactly: OK").trim();
                message = t(R.string.gboard_ai_bar_test_ok) + " " + (answer.length() > 60 ? answer.substring(0, 60) : answer);
            } catch (AiBarException e) {
                message = e.getMessage();
            }
            String result = message;
            main.post(() -> GboardPatchesSettingsContract.showMessage(host, result));
        }, "AiBarTest").start();
    }

    // --- Buttons ---

    private List<Row> buttonRows(FeatureHost host, AiBarConfig config, boolean on) {
        List<Row> rows = new ArrayList<>();
        for (AiBarButton button : config.buttons) {
            rows.add(new NavigationRow(button.label, modeLabel(button.mode) + (button.model == null ? "" : " · " + button.model), on,
                    () -> GboardPatchesSettingsContract.openFeature(host, new ButtonScreen(button.id))));
        }
        rows.add(new CommandRow(t(R.string.gboard_ai_bar_add_button), "", on, () -> {
            AiBarButton created = new AiBarButton(AiBarConfig.newId(), t(R.string.gboard_ai_bar_new_button), "{text}",
                    AiBarButton.Mode.REPLACE, null, null);
            update(host, c -> c.buttons.add(created));
            GboardPatchesSettingsContract.openFeature(host, new ButtonScreen(created.id));
        }));
        rows.add(new CommandRow(t(R.string.gboard_ai_bar_restart), t(R.string.gboard_ai_bar_restart_summary), on,
                () -> GboardPatchesSettingsContract.requestTargetRestart(host)));
        rows.add(new DangerRow(t(R.string.gboard_ai_bar_reset_buttons), t(R.string.gboard_ai_bar_reset_buttons_summary), on,
                () -> update(host, c -> c.buttons = AiBarConfig.defaults().buttons),
                t(R.string.gboard_ai_bar_reset_buttons), t(R.string.gboard_ai_bar_reset_buttons_confirm)));
        return rows;
    }

    private String modeLabel(AiBarButton.Mode mode) {
        return switch (mode) {
            case REPLACE -> t(R.string.gboard_ai_bar_mode_replace);
            case APPEND -> t(R.string.gboard_ai_bar_mode_append);
            case POPUP -> t(R.string.gboard_ai_bar_mode_popup);
        };
    }

    private String iconLabel(String icon) {
        if (icon == null) return t(R.string.gboard_ai_bar_icon_letters);
        return switch (icon) {
            case AiBarButton.ICON_WAND -> t(R.string.gboard_ai_bar_icon_wand);
            case AiBarButton.ICON_SPARKLE -> t(R.string.gboard_ai_bar_icon_sparkle);
            case AiBarButton.ICON_QUESTION -> t(R.string.gboard_ai_bar_icon_question);
            case AiBarButton.ICON_TRANSLATE -> t(R.string.gboard_ai_bar_icon_translate);
            default -> t(R.string.gboard_ai_bar_icon_lines);
        };
    }

    /** One AI button's settings; every change saves right away. */
    private final class ButtonScreen implements GboardPatchesSettingsContract.Feature {
        private final String id;

        ButtonScreen(String id) { this.id = id; }

        @Override public String getEntryTitle() { return t(R.string.gboard_ai_bar_button_title); }

        @Override public String getEntrySummary() { return ""; }

        @Override public Screen buildScreen(FeatureHost host) {
            AiBarButton b = AiBarStore.config(host.getContext()).button(id);
            if (b == null) {
                return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), getEntryTitle(),
                        t(R.string.gboard_ai_bar_button_deleted), List.of(), List.of());
            }
            List<Row> rows = new ArrayList<>();
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_label), t(R.string.gboard_ai_bar_label_summary), b.label, true,
                    () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_label), "", b.label,
                            value -> {
                                check(AiBarValidation.label(value));
                                replaceButton(host, new AiBarButton(b.id, value.trim(), b.prompt, b.mode, b.icon, b.model));
                            })));
            String[] iconValues = {"", AiBarButton.ICON_WAND, AiBarButton.ICON_SPARKLE, AiBarButton.ICON_QUESTION,
                    AiBarButton.ICON_TRANSLATE, AiBarButton.ICON_LINES};
            String[] iconLabels = new String[iconValues.length];
            for (int i = 0; i < iconValues.length; i++) iconLabels[i] = iconLabel(iconValues[i].isEmpty() ? null : iconValues[i]);
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_icon), t(R.string.gboard_ai_bar_icon_summary), iconLabel(b.icon), true,
                    () -> GboardPatchesSettingsContract.showChoiceDialog(host, t(R.string.gboard_ai_bar_icon), iconLabels, iconValues,
                            b.icon == null ? "" : b.icon, null, null,
                            value -> replaceButton(host, new AiBarButton(b.id, b.label, b.prompt, b.mode,
                                    value.isEmpty() ? null : value, b.model)))));
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_prompt), t(R.string.gboard_ai_bar_prompt_summary),
                    b.prompt.length() > 60 ? b.prompt.substring(0, 60) + "…" : b.prompt, true,
                    () -> AiBarDialogs.multiLine(host, t(R.string.gboard_ai_bar_prompt), "{text}", b.prompt, value -> {
                        check(AiBarValidation.prompt(value));
                        replaceButton(host, new AiBarButton(b.id, b.label, value, b.mode, b.icon, b.model));
                    })));
            AiBarButton.Mode[] modes = AiBarButton.Mode.values();
            String[] modeLabels = new String[modes.length];
            String[] modeValues = new String[modes.length];
            for (int i = 0; i < modes.length; i++) {
                modeLabels[i] = modeLabel(modes[i]);
                modeValues[i] = modes[i].name();
            }
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_result), "", modeLabel(b.mode), true,
                    () -> GboardPatchesSettingsContract.showChoiceDialog(host, t(R.string.gboard_ai_bar_result), modeLabels,
                            modeValues, b.mode.name(), null, null,
                            value -> replaceButton(host, new AiBarButton(b.id, b.label, b.prompt,
                                    AiBarButton.Mode.valueOf(value), b.icon, b.model)))));
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_model_override), t(R.string.gboard_ai_bar_model_override_summary),
                    b.model == null ? t(R.string.gboard_ai_bar_not_set) : b.model, true,
                    () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_model_override), "",
                            b.model == null ? "" : b.model,
                            value -> replaceButton(host, new AiBarButton(b.id, b.label, b.prompt, b.mode, b.icon, value)))));
            rows.add(new DangerRow(t(R.string.gboard_ai_bar_delete_button), t(R.string.gboard_ai_bar_restart_summary), true,
                    () -> update(host, c -> c.buttons.removeIf(x -> x.id.equals(b.id))),
                    t(R.string.gboard_ai_bar_delete_button), b.label));
            return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), b.label,
                    t(R.string.gboard_ai_bar_restart_summary), List.of(), List.of(new Section("", rows)));
        }
    }

    private void replaceButton(FeatureHost host, AiBarButton updated) {
        update(host, c -> c.buttons.replaceAll(x -> x.id.equals(updated.id) ? updated : x));
    }

    // --- Shortcuts ---

    private List<Row> shortcutRows(FeatureHost host, AiBarConfig config, boolean on) {
        List<Row> rows = new ArrayList<>();
        rows.add(new SwitchRow(t(R.string.gboard_ai_bar_expand), t(R.string.gboard_ai_bar_expand_summary), on,
                config.expanderEnabled, value -> update(host, c -> c.expanderEnabled = value)));
        rows.add(new SwitchRow(t(R.string.gboard_ai_bar_revert), t(R.string.gboard_ai_bar_revert_summary),
                on && config.expanderEnabled, config.revertOnBackspace, value -> update(host, c -> c.revertOnBackspace = value)));
        rows.add(new NavigationRow(t(R.string.gboard_ai_bar_shortcuts),
                config.shortcuts.size() + " · " + t(R.string.gboard_ai_bar_shortcuts_summary), on,
                () -> GboardPatchesSettingsContract.openFeature(host, new ShortcutsScreen())));
        rows.add(new SelectorRow(t(R.string.gboard_ai_bar_excluded), t(R.string.gboard_ai_bar_excluded_summary),
                config.excludedPackages.isEmpty() ? t(R.string.gboard_ai_bar_none) : String.join(", ", config.excludedPackages), on,
                () -> AiBarDialogs.multiLine(host, t(R.string.gboard_ai_bar_excluded), "com.example.app",
                        String.join("\n", config.excludedPackages),
                        value -> update(host, c -> c.excludedPackages = AiBarValidation.packages(value)))));
        return rows;
    }

    private final class ShortcutsScreen implements GboardPatchesSettingsContract.Feature {
        @Override public String getEntryTitle() { return t(R.string.gboard_ai_bar_shortcuts); }

        @Override public String getEntrySummary() { return ""; }

        @Override public Screen buildScreen(FeatureHost host) {
            AiBarConfig config = AiBarStore.config(host.getContext());
            List<Row> rows = new ArrayList<>();
            List<AiBarShortcut> sorted = new ArrayList<>(config.shortcuts);
            sorted.sort((a, b) -> a.trigger.compareToIgnoreCase(b.trigger));
            for (AiBarShortcut s : sorted) {
                String preview = s.expansion.replace('\n', ' ');
                rows.add(new NavigationRow(s.trigger, preview.length() > 80 ? preview.substring(0, 80) + "…" : preview, true,
                        () -> GboardPatchesSettingsContract.openFeature(host, new ShortcutScreen(s.id))));
            }
            rows.add(new CommandRow(t(R.string.gboard_ai_bar_add_shortcut), t(R.string.gboard_ai_bar_add_shortcut_summary), true,
                    () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_trigger), ";addr", "",
                            value -> {
                                String trigger = value.trim();
                                check(AiBarValidation.trigger(trigger, "", AiBarStore.config(host.getContext()).shortcuts));
                                AiBarShortcut created = new AiBarShortcut(AiBarConfig.newId(), trigger, "",
                                        AiBarShortcut.ExpandOn.AFTER_SPACE_OR_PUNCTUATION);
                                update(host, c -> c.shortcuts.add(created));
                                new Handler(Looper.getMainLooper()).post(() ->
                                        GboardPatchesSettingsContract.openFeature(host, new ShortcutScreen(created.id)));
                            })));
            return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), getEntryTitle(),
                    t(R.string.gboard_ai_bar_shortcuts_summary), List.of(), List.of(new Section("", rows)));
        }
    }

    private final class ShortcutScreen implements GboardPatchesSettingsContract.Feature {
        private final String id;

        ShortcutScreen(String id) { this.id = id; }

        @Override public String getEntryTitle() { return t(R.string.gboard_ai_bar_shortcut_title); }

        @Override public String getEntrySummary() { return ""; }

        @Override public Screen buildScreen(FeatureHost host) {
            AiBarShortcut s = null;
            for (AiBarShortcut x : AiBarStore.config(host.getContext()).shortcuts) if (x.id.equals(id)) s = x;
            if (s == null) {
                return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), getEntryTitle(),
                        t(R.string.gboard_ai_bar_shortcut_deleted), List.of(), List.of());
            }
            AiBarShortcut shortcut = s;
            List<Row> rows = new ArrayList<>();
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_trigger), "", shortcut.trigger, true,
                    () -> GboardPatchesSettingsContract.showTextInputDialog(host, t(R.string.gboard_ai_bar_trigger), "",
                            shortcut.trigger, value -> {
                                String trigger = value.trim();
                                check(AiBarValidation.trigger(trigger, shortcut.id, AiBarStore.config(host.getContext()).shortcuts));
                                replaceShortcut(host, new AiBarShortcut(shortcut.id, trigger, shortcut.expansion, shortcut.expandOn));
                            })));
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_expansion), t(R.string.gboard_ai_bar_expansion_summary),
                    shortcut.expansion.isEmpty() ? t(R.string.gboard_ai_bar_not_set) : shortcut.expansion.replace('\n', ' '), true,
                    () -> AiBarDialogs.multiLine(host, t(R.string.gboard_ai_bar_expansion), "", shortcut.expansion,
                            value -> replaceShortcut(host, new AiBarShortcut(shortcut.id, shortcut.trigger, value, shortcut.expandOn)))));
            boolean immediately = shortcut.expandOn == AiBarShortcut.ExpandOn.IMMEDIATELY;
            rows.add(new SelectorRow(t(R.string.gboard_ai_bar_when), "",
                    t(immediately ? R.string.gboard_ai_bar_when_immediately : R.string.gboard_ai_bar_when_delimiter), true,
                    () -> GboardPatchesSettingsContract.showChoiceDialog(host, t(R.string.gboard_ai_bar_when),
                            new String[] {t(R.string.gboard_ai_bar_when_immediately), t(R.string.gboard_ai_bar_when_delimiter)},
                            new String[] {AiBarShortcut.ExpandOn.IMMEDIATELY.name(), AiBarShortcut.ExpandOn.AFTER_SPACE_OR_PUNCTUATION.name()},
                            shortcut.expandOn.name(), null, null,
                            value -> replaceShortcut(host, new AiBarShortcut(shortcut.id, shortcut.trigger, shortcut.expansion,
                                    AiBarShortcut.ExpandOn.valueOf(value))))));
            rows.add(new DangerRow(t(R.string.gboard_ai_bar_delete_shortcut), "", true,
                    () -> update(host, c -> c.shortcuts.removeIf(x -> x.id.equals(shortcut.id))),
                    t(R.string.gboard_ai_bar_delete_shortcut), shortcut.trigger));
            return new Screen(getEntryTitle(), t(R.string.gboard_patches_header_badge), shortcut.trigger, "",
                    List.of(), List.of(new Section("", rows)));
        }
    }

    private void replaceShortcut(FeatureHost host, AiBarShortcut updated) {
        update(host, c -> c.shortcuts.replaceAll(x -> x.id.equals(updated.id) ? updated : x));
    }

    // --- Backup ---

    private void importBackup(FeatureHost host) {
        GboardPatchesSettingsContract.openTextDocument(host, new String[] {"application/json", "text/plain", "application/octet-stream"},
                text -> {
                    Context owner = host.getContext();
                    try {
                        AiBarConfig.ImportResult result = AiBarConfig.importBackup(text, AiBarStore.config(owner));
                        AiBarStore.save(owner, result.config);
                        GboardPatchesSettingsContract.showMessage(host,
                                t(R.string.gboard_ai_bar_imported) + " " + String.join(", ", result.sections));
                    } catch (AiBarException e) {
                        GboardPatchesSettingsContract.showMessage(host, e.getMessage());
                    }
                    GboardPatchesSettingsContract.refresh(host);
                });
    }

    // --- Helpers ---

    private static void update(FeatureHost host, Consumer<AiBarConfig> change) {
        Context owner = host.getContext();
        AiBarConfig config = AiBarStore.config(owner);
        change.accept(config);
        AiBarStore.save(owner, config);
        GboardPatchesSettingsContract.refresh(host);
    }

    private static void check(String problem) {
        if (problem != null) throw new IllegalArgumentException(problem);
    }

    private String t(int id) {
        return GboardSettingsText.get(context, id);
    }
}
