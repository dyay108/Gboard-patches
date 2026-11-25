package dev.jason.gboardpatches.extension.aibar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class AiBarConfigTest {
    @Test
    public void defaultsHaveTheFiveButtonsAndNoModel() {
        AiBarConfig config = AiBarConfig.defaults();
        List<String> labels = config.buttons.stream().map(b -> b.label).toList();
        assertEquals(List.of("ES", "FR", "EN", "Improve", "ASK"), labels);
        assertEquals(AiBarButton.Mode.POPUP, config.buttons.get(4).mode);
        assertEquals(AiBarButton.ICON_WAND, config.buttons.get(3).icon);
        assertEquals("", config.anthropicModel);
        assertEquals("", config.openAiModel);
        assertEquals(AiBarConfig.Provider.ANTHROPIC, config.provider);
        assertTrue(config.buttons.get(0).prompt.contains("ONLY the translated text"));
    }

    @Test
    public void roundTripsThroughJson() throws Exception {
        AiBarConfig config = AiBarConfig.defaults();
        config.openAiModel = "gpt-x";
        config.provider = AiBarConfig.Provider.OPENAI_COMPATIBLE;
        config.shortcuts.add(new AiBarShortcut("s1", ";home", "1 Main St", AiBarShortcut.ExpandOn.IMMEDIATELY));
        config.excludedPackages.add("com.bank");
        config.revertOnBackspace = false;
        AiBarConfig copy = AiBarConfig.fromJson(config.toJson());
        assertEquals(config.toJson(), copy.toJson());
    }

    @Test
    public void importsAnAiBarAppBackup() throws Exception {
        String backup = "{\"version\":1,\"buttons\":["
                + "{\"id\":\"default-es\",\"label\":\"ES\",\"prompt\":\"To Spanish {text}\",\"mode\":\"REPLACE\",\"icon\":null,\"model\":null,\"kind\":\"AI\"},"
                + "{\"id\":\"default-improve\",\"label\":\"Improve\",\"prompt\":\"Fix {text}\",\"mode\":\"REPLACE\",\"icon\":\"WAND\",\"model\":\"m2\"},"
                + "{\"id\":\"settings\",\"label\":\"AI Bar settings\",\"prompt\":\"\",\"mode\":\"REPLACE\",\"kind\":\"SETTINGS\"}],"
                + "\"provider\":{\"active\":\"OPENAI_COMPATIBLE\",\"anthropicModel\":\"a\",\"openAiBaseUrl\":\"http://x/v1\",\"openAiModel\":\"o\"},"
                + "\"shortcuts\":[{\"id\":\"s\",\"trigger\":\"btw\",\"expansion\":\"by the way\"}],"
                + "\"expander\":{\"enabled\":false,\"revertOnBackspace\":true,\"excludedPackages\":[\"com.x\"]},\"future\":1}";
        AiBarConfig current = AiBarConfig.defaults();
        AiBarConfig.ImportResult result = AiBarConfig.importBackup(backup, current);
        AiBarConfig config = result.config;
        assertEquals(2, config.buttons.size());
        assertEquals("m2", config.buttons.get(1).model);
        assertEquals(AiBarButton.ICON_WAND, config.buttons.get(1).icon);
        assertNull(config.buttons.get(0).icon);
        assertEquals(AiBarConfig.Provider.OPENAI_COMPATIBLE, config.provider);
        assertEquals("http://x/v1", config.openAiBaseUrl);
        assertEquals(AiBarShortcut.ExpandOn.AFTER_SPACE_OR_PUNCTUATION, config.shortcuts.get(0).expandOn);
        assertFalse(config.expanderEnabled);
        assertEquals(List.of("com.x"), config.excludedPackages);
        assertEquals(List.of("buttons", "provider", "shortcuts", "text shortcut settings"), result.sections);
    }

    @Test
    public void importReplacesOnlyTheSectionsPresent() throws Exception {
        AiBarConfig current = AiBarConfig.defaults();
        current.anthropicModel = "keep-me";
        AiBarConfig.ImportResult result = AiBarConfig.importBackup(
                "{\"shortcuts\":[{\"id\":\"a\",\"trigger\":\";x\",\"expansion\":\"X\",\"expandOn\":\"IMMEDIATELY\"}]}", current);
        assertEquals("keep-me", result.config.anthropicModel);
        assertEquals(5, result.config.buttons.size());
        assertEquals(1, result.config.shortcuts.size());
        assertEquals(List.of("shortcuts"), result.sections);
    }

    @Test
    public void importRejectsBadFiles() {
        assertThrows(AiBarException.class, () -> AiBarConfig.importBackup("not json", AiBarConfig.defaults()));
        assertThrows(AiBarException.class, () -> AiBarConfig.importBackup("{\"hello\":1}", AiBarConfig.defaults()));
        assertThrows(AiBarException.class, () -> AiBarConfig.importBackup(
                "{\"shortcuts\":[{\"id\":\"a\",\"trigger\":\"\",\"expansion\":\"X\"}]}", AiBarConfig.defaults()));
    }

    @Test
    public void toolbarTokensAreStableAndUnique() {
        AiBarConfig config = AiBarConfig.defaults();
        // Gboard keeps only snake_case Access Point tokens.
        assertEquals("aibar_default_es", config.buttons.get(0).toolbarToken());
        assertEquals("aibar_3f2a_9b", new AiBarButton("3F2A-9b", "X", "{text}", AiBarButton.Mode.REPLACE, null, null).toolbarToken());
        assertEquals(5, config.buttons.stream().map(AiBarButton::toolbarToken).distinct().count());
    }

    @Test
    public void iconNameFallsBackToALabelBadge() {
        assertEquals("aibar_icon_wand", new AiBarButton("i", "Improve", "{text}", AiBarButton.Mode.REPLACE, AiBarButton.ICON_WAND, null).iconResourceName());
        assertEquals("aibar_badge_es", new AiBarButton("e", "ES", "{text}", AiBarButton.Mode.REPLACE, null, null).iconResourceName());
        assertEquals("aibar_badge_a", new AiBarButton("a", "ask", "{text}", AiBarButton.Mode.POPUP, null, null).iconResourceName());
        assertEquals("aibar_icon_sparkle", new AiBarButton("x", "??", "{text}", AiBarButton.Mode.POPUP, null, null).iconResourceName());
        assertEquals("aibar_badge_de", new AiBarButton("d", "De-DE", "{text}", AiBarButton.Mode.POPUP, null, null).iconResourceName());
    }
}
