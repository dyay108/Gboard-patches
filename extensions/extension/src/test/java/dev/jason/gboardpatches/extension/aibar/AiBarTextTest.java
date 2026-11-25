package dev.jason.gboardpatches.extension.aibar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.List;
import org.junit.Test;

public class AiBarTextTest {
    // --- prompts ---
    @Test public void fillsEveryPlaceholderOrAppends() {
        assertEquals("A x B x", AiBarPrompts.fill("A {text} B {text}", "x"));
        assertEquals("Sum.\n\nlong", AiBarPrompts.fill("Sum.", "long"));
        assertEquals("T: lit {text}", AiBarPrompts.fill("T: {text}", "lit {text}"));
    }

    @Test public void cleansWrappedAnswers() {
        assertEquals("Hola mundo", AiBarPrompts.clean("\"Hola mundo\"", "Hello world"));
        assertEquals("Hola", AiBarPrompts.clean("“Hola”", "Hello"));
        assertEquals("\"Hola\"", AiBarPrompts.clean("\"Hola\"", "\"Hello\""));
        assertEquals("Line 1\n\nLine 2 😀", AiBarPrompts.clean("<text>\nLine 1\n\nLine 2 😀\n</text>\n", "x"));
    }

    // --- targets and edits ---
    @Test public void targetsSelectionOrWholeField() {
        assertEquals("world", AiBarEdits.target("Hello world", 6, 11).text());
        assertEquals("Hello world", AiBarEdits.target("Hello world", 5, 5).text());
        AiBarEdits.Target reversed = AiBarEdits.target("Hello world", 11, 6);
        assertEquals(6, reversed.start);
        assertEquals("abc", AiBarEdits.target("abc", -1, -1).text());
        assertTrue(AiBarEdits.target("  \n", 0, 0).isEmpty());
    }

    @Test public void replaceAppendInsertAndUndo() {
        AiBarEdits.Edit replace = AiBarEdits.replace(AiBarEdits.target("I said hola to her", 7, 11), "hello");
        assertEquals("I said hello to her", replace.newText);
        assertEquals(12, replace.cursor());
        AiBarEdits.Edit append = AiBarEdits.append(AiBarEdits.target("Question?", 0, 0), "Answer.");
        assertEquals("Question?\nAnswer.", append.newText);
        assertEquals(9, append.start);
        assertEquals("Hi", AiBarEdits.append(AiBarEdits.target("", 0, 0), "Hi").newText);
        AiBarEdits.Edit undo = AiBarEdits.undo(replace);
        assertEquals("I said hola to her", undo.newText);
        assertEquals(7, undo.start);
        assertEquals(12, undo.end);
        assertEquals("hola", undo.insertion);
    }

    // --- expander ---
    private final AiBarExpander expander = new AiBarExpander(() -> "Nov 25, 2026", () -> "12:30", () -> "CLIP");
    private final List<AiBarShortcut> shortcuts = List.of(
            new AiBarShortcut("1", ";home", "1 Main St", AiBarShortcut.ExpandOn.IMMEDIATELY),
            new AiBarShortcut("2", "btw", "by the way", AiBarShortcut.ExpandOn.AFTER_SPACE_OR_PUNCTUATION),
            new AiBarShortcut("3", ";sig", "Best,\n{cursor}\nSam", AiBarShortcut.ExpandOn.IMMEDIATELY),
            new AiBarShortcut("4", ";d", "On {date} at {time}: {clipboard}", AiBarShortcut.ExpandOn.IMMEDIATELY));

    @Test public void expandsImmediateTriggersBeforeTheCursor() {
        AiBarExpander.Plan plan = expander.plan("Send to ;home", shortcuts);
        assertEquals(5, plan.deleteBefore);
        assertEquals("1 Main St", plan.insertion);
        assertEquals(0, plan.cursorBackFromEnd);
        assertNull(expander.plan("Send to ;hom", shortcuts));
        assertNull(expander.plan("x;home", shortcuts));
    }

    @Test public void delimiterTriggersKeepTheDelimiter() {
        assertNull(expander.plan("btw", shortcuts));
        AiBarExpander.Plan plan = expander.plan("ok btw,", shortcuts);
        assertEquals(4, plan.deleteBefore);
        assertEquals("by the way,", plan.insertion);
        assertNull(expander.plan("abtw ", shortcuts));
    }

    @Test public void fillsPlaceholdersAndCursor() {
        AiBarExpander.Plan sig = expander.plan(";sig", shortcuts);
        assertEquals("Best,\n\nSam", sig.insertion);
        assertEquals(4, sig.cursorBackFromEnd);
        assertEquals("On Nov 25, 2026 at 12:30: CLIP", expander.plan(";d", shortcuts).insertion);
    }

    @Test public void longestTriggerWins() {
        List<AiBarShortcut> both = List.of(
                new AiBarShortcut("a", ";h", "short", AiBarShortcut.ExpandOn.IMMEDIATELY),
                new AiBarShortcut("b", "x;h", "long", AiBarShortcut.ExpandOn.IMMEDIATELY));
        assertEquals("long", expander.plan("x;h", both).insertion);
    }

    @Test public void revertsOnASingleBackspaceAtTheEnd() {
        AiBarExpander.Plan plan = expander.plan("Send to ;home", shortcuts);
        // After the expansion the text before the cursor ends with the insertion; one backspace removes its last char.
        AiBarExpander.Revert revert = AiBarExpander.revert(plan, "Send to 1 Main S");
        assertEquals(8, revert.deleteBefore);
        assertEquals(";home", revert.insertion);
        assertNull(AiBarExpander.revert(plan, "Send to 1 Main St x"));
        assertNull(AiBarExpander.revert(plan, "Send to 1 Main "));
    }

    @Test public void revertOverTheKeptSpaceRestoresTheTrigger() {
        AiBarExpander.Plan plan = expander.plan("btw ", shortcuts);
        AiBarExpander.Revert revert = AiBarExpander.revert(plan, "by the way");
        assertEquals(10, revert.deleteBefore);
        assertEquals("btw", revert.insertion);
    }
}
