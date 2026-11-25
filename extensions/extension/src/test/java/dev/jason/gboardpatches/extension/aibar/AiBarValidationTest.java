package dev.jason.gboardpatches.extension.aibar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.List;
import org.junit.Test;

public class AiBarValidationTest {
    private final AiBarShortcut home = new AiBarShortcut("1", ";home", "1 Main St", AiBarShortcut.ExpandOn.IMMEDIATELY);

    @Test public void labelsAndPromptsMustNotBeBlank() {
        assertNull(AiBarValidation.label("ES"));
        assertEquals("Add a label", AiBarValidation.label("  "));
        assertNull(AiBarValidation.prompt("Translate {text}"));
        assertEquals("Add a prompt", AiBarValidation.prompt(""));
    }

    @Test public void triggersNeedTextNoSpacesAndNoDuplicates() {
        assertNull(AiBarValidation.trigger(";addr", "2", List.of(home)));
        assertEquals("Add a trigger", AiBarValidation.trigger("", "2", List.of(home)));
        assertEquals("A trigger can't contain spaces", AiBarValidation.trigger("my home", "2", List.of(home)));
        assertEquals("Another shortcut already uses ;home", AiBarValidation.trigger(";home", "2", List.of(home)));
        assertNull(AiBarValidation.trigger(";home", "1", List.of(home)));
    }

    @Test public void parsesPackageLists() {
        assertEquals(List.of("com.a", "com.b"), AiBarValidation.packages(" com.a, com.b ,,com.a\ncom.b"));
        assertEquals(List.of(), AiBarValidation.packages("  "));
    }
}
