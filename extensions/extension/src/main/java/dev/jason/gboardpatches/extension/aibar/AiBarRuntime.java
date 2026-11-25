package dev.jason.gboardpatches.extension.aibar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.inputmethodservice.InputMethodService;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.InputType;
import android.util.Log;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.ExtractedText;
import android.view.inputmethod.ExtractedTextRequest;
import android.view.inputmethod.InputConnection;
import android.widget.Toast;
import dev.jason.gboardpatches.extension.settings.GboardPatchesFeatureAvailability;
import java.lang.ref.WeakReference;
import java.text.DateFormat;
import java.util.Date;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Runs AI Bar inside Gboard: the toolbar buttons' AI requests, the answer card and Undo, and the
 * text-shortcut expander that runs after each cursor update. Every entry point swallows its own
 * failures so nothing can escape into Gboard.
 */
public final class AiBarRuntime {
    private static final String TAG = "GboardPatchesAiBar";
    private static final long UNDO_MILLIS = 5_000L;
    private static final long EXPAND_DELAY_MILLIS = 40L;
    private static final long OWN_EDIT_QUIET_MILLIS = 300L;
    private static final int BEFORE_CURSOR_CHARS = 256;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "AiBar");
        thread.setDaemon(true);
        return thread;
    });

    private static WeakReference<InputMethodService> serviceRef = new WeakReference<>(null);
    private static AiBarOverlay overlay;
    private static boolean busy;
    private static int requestGeneration;
    private static Runnable hideUndo;

    // Expander state: the last expansion (for revert) and a quiet period after our own edits.
    private static AiBarExpander.Plan lastExpansion;
    private static int lastExpansionEnd = -1;
    private static long quietUntil;
    private static int latestStart = -1;
    private static int latestEnd = -1;
    private static final Runnable EXPAND = AiBarRuntime::expandIfTriggered;

    private AiBarRuntime() {}

    // --- Gboard lifecycle hooks ---

    public static void start(Object receiver) {
        try {
            if (!(receiver instanceof InputMethodService service)) return;
            serviceRef = new WeakReference<>(service);
            if (overlay == null || !overlay.isFor(service)) overlay = new AiBarOverlay(service);
            resetTyping();
        } catch (Throwable failure) {
            log("start", failure);
        }
    }

    public static void end() {
        try {
            requestGeneration++;
            busy = false;
            clearUndo();
            if (overlay != null) overlay.dismissAll();
            resetTyping();
        } catch (Throwable failure) {
            log("end", failure);
        }
    }

    public static void onSelectionUpdated(Object receiver, int selectionStart, int selectionEnd) {
        try {
            if (!(receiver instanceof InputMethodService service)) return;
            serviceRef = new WeakReference<>(service);
            latestStart = selectionStart;
            latestEnd = selectionEnd;
            MAIN.removeCallbacks(EXPAND);
            MAIN.postDelayed(EXPAND, EXPAND_DELAY_MILLIS);
        } catch (Throwable failure) {
            log("selection", failure);
        }
    }

    // --- Toolbar buttons ---

    static boolean active(android.content.Context context) {
        return GboardPatchesFeatureAvailability.hasFeature(context, GboardPatchesFeatureAvailability.FEATURE_AI_BAR)
                && AiBarStore.enabled(context);
    }

    static void click(String buttonId) {
        try {
            InputMethodService service = serviceRef.get();
            if (service == null || busy || Looper.myLooper() != Looper.getMainLooper()) return;
            AiBarConfig config = AiBarStore.config(service);
            AiBarButton button = config.button(buttonId);
            if (button == null) return;
            InputConnection connection = service.getCurrentInputConnection();
            if (connection == null || isPassword(service.getCurrentInputEditorInfo())) {
                toast(service, "No text field to work on");
                return;
            }
            Field field = Field.read(connection);
            AiBarEdits.Target target = AiBarEdits.target(field.text, field.selectionStart, field.selectionEnd);
            if (target.isEmpty()) {
                toast(service, "The field is empty");
                return;
            }
            clearUndo();
            if (overlay != null) overlay.hideAnswer();
            busy = true;
            int generation = ++requestGeneration;
            if (overlay != null) overlay.showProgress(button.label);
            AiBarLlm.Client client = AiBarStore.client(service, config, button.model);
            String prompt = AiBarPrompts.fill(button.prompt, target.text());
            WORKER.execute(() -> {
                String answer = null;
                String error = null;
                try {
                    answer = AiBarPrompts.clean(client.complete(AiBarPrompts.SYSTEM, prompt), target.text());
                    if (answer.isEmpty()) error = "The AI returned an empty answer";
                } catch (AiBarException e) {
                    error = e.getMessage();
                } catch (Throwable failure) {
                    error = "The AI request failed";
                    log("request", failure);
                }
                String finalAnswer = answer;
                String finalError = error;
                MAIN.post(() -> finish(generation, button, field, target, finalAnswer, finalError));
            });
        } catch (Throwable failure) {
            busy = false;
            log("click", failure);
        }
    }

    private static void finish(int generation, AiBarButton button, Field field, AiBarEdits.Target target,
            String answer, String error) {
        try {
            if (generation != requestGeneration) return; // The keyboard closed meanwhile.
            busy = false;
            if (overlay != null) overlay.hideChip();
            InputMethodService service = serviceRef.get();
            if (service == null) return;
            if (error != null) {
                toast(service, error);
                return;
            }
            if (button.mode == AiBarButton.Mode.POPUP) {
                if (overlay != null) overlay.showAnswer(answer, AiBarRuntime::insertAnswer);
                return;
            }
            AiBarEdits.Edit edit = button.mode == AiBarButton.Mode.REPLACE
                    ? AiBarEdits.replace(target, answer)
                    : AiBarEdits.append(target, answer);
            write(service, field, edit);
        } catch (Throwable failure) {
            log("finish", failure);
        }
    }

    /** Applies {@code edit} if the field still holds what we read, then offers Undo. */
    private static void write(InputMethodService service, Field field, AiBarEdits.Edit edit) {
        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) return;
        Field now = Field.read(connection);
        if (!now.text.equals(field.text) || now.offset != field.offset) {
            toast(service, "The text changed, so AI Bar left it alone");
            return;
        }
        replace(connection, field.offset + edit.start, field.offset + edit.end, edit.insertion);
        Field after = new Field(edit.newText, edit.cursor(), edit.cursor(), field.offset);
        offerUndo(service, after, AiBarEdits.undo(edit));
    }

    private static void offerUndo(InputMethodService service, Field field, AiBarEdits.Edit undo) {
        if (overlay == null) return;
        clearUndo();
        overlay.showUndo(() -> {
            clearUndo();
            write(service, field, undo);
            clearUndo();
        });
        hideUndo = AiBarRuntime::clearUndo;
        MAIN.postDelayed(hideUndo, UNDO_MILLIS);
    }

    private static void clearUndo() {
        if (hideUndo != null) MAIN.removeCallbacks(hideUndo);
        hideUndo = null;
        if (overlay != null) overlay.hideChip();
    }

    /** Insert from the answer card: on a new line below the cursor's paragraph end, or at the cursor. */
    private static void insertAnswer(String answer) {
        InputMethodService service = serviceRef.get();
        if (service == null) return;
        InputConnection connection = service.getCurrentInputConnection();
        if (connection == null) return;
        Field field = Field.read(connection);
        int at = Math.max(field.selectionStart, field.selectionEnd);
        if (at < 0) at = field.text.length();
        AiBarEdits.Edit edit = AiBarEdits.append(new AiBarEdits.Target(field.text, at, at), answer);
        write(service, field, edit);
    }

    private static void replace(InputConnection connection, int start, int end, String insertion) {
        quietUntil = SystemClock.uptimeMillis() + OWN_EDIT_QUIET_MILLIS;
        lastExpansion = null;
        connection.beginBatchEdit();
        try {
            connection.finishComposingText();
            connection.setSelection(start, end);
            connection.commitText(insertion, 1);
        } finally {
            connection.endBatchEdit();
        }
    }

    // --- Text shortcuts ---

    private static void resetTyping() {
        MAIN.removeCallbacks(EXPAND);
        lastExpansion = null;
        lastExpansionEnd = -1;
    }

    private static void expandIfTriggered() {
        try {
            InputMethodService service = serviceRef.get();
            if (service == null || SystemClock.uptimeMillis() < quietUntil) return;
            int cursor = latestStart;
            if (cursor < 0 || cursor != latestEnd) {
                lastExpansion = null;
                return;
            }
            if (!active(service)) return;
            AiBarConfig config = AiBarStore.config(service);
            if (!config.expanderEnabled || config.shortcuts.isEmpty()) return;
            EditorInfo editor = service.getCurrentInputEditorInfo();
            if (editor == null || isPassword(editor)) return;
            String app = editor.packageName;
            // Never expand inside Gboard's own settings, where shortcuts are edited.
            if (app == null || app.equals(service.getPackageName()) || config.excludedPackages.contains(app)) return;
            InputConnection connection = service.getCurrentInputConnection();
            if (connection == null) return;
            CharSequence beforeChars = connection.getTextBeforeCursor(BEFORE_CURSOR_CHARS, 0);
            if (beforeChars == null) return;
            String before = beforeChars.toString();

            AiBarExpander.Plan previous = lastExpansion;
            int previousEnd = lastExpansionEnd;
            lastExpansion = null;
            if (previous != null && config.revertOnBackspace && cursor == previousEnd - 1) {
                AiBarExpander.Revert revert = AiBarExpander.revert(previous, before);
                if (revert != null) {
                    swapBeforeCursor(connection, revert.deleteBefore, revert.insertion, 0);
                    return;
                }
            }
            AiBarExpander.Plan plan = expander(service).plan(before, config.shortcuts);
            if (plan == null) return;
            swapBeforeCursor(connection, plan.deleteBefore, plan.insertion, plan.cursorBackFromEnd);
            lastExpansion = plan;
            lastExpansionEnd = cursor - plan.deleteBefore + plan.insertion.length();
        } catch (Throwable failure) {
            log("expand", failure);
        }
    }

    private static void swapBeforeCursor(InputConnection connection, int deleteBefore, String insertion, int cursorBack) {
        quietUntil = SystemClock.uptimeMillis() + OWN_EDIT_QUIET_MILLIS;
        connection.beginBatchEdit();
        try {
            // Settle Gboard's composing word first so it can't restore or duplicate the trigger.
            connection.finishComposingText();
            connection.deleteSurroundingText(deleteBefore, 0);
            connection.commitText(insertion, 1);
            if (cursorBack > 0) {
                int end = latestStart - deleteBefore + insertion.length();
                connection.setSelection(end - cursorBack, end - cursorBack);
            }
        } finally {
            connection.endBatchEdit();
        }
    }

    private static AiBarExpander expander(InputMethodService service) {
        return new AiBarExpander(
                () -> DateFormat.getDateInstance(DateFormat.MEDIUM).format(new Date()),
                () -> android.text.format.DateFormat.getTimeFormat(service).format(new Date()),
                () -> clipboardText(service));
    }

    /** Keyboards may read the clipboard, so {clipboard} is filled in directly. */
    private static String clipboardText(InputMethodService service) {
        try {
            ClipboardManager clipboard = service.getSystemService(ClipboardManager.class);
            ClipData clip = clipboard == null ? null : clipboard.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return "";
            CharSequence text = clip.getItemAt(0).coerceToText(service);
            return text == null ? "" : text.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }

    // --- Helpers ---

    static boolean isPassword(EditorInfo editor) {
        if (editor == null) return false;
        int type = editor.inputType;
        int variation = type & InputType.TYPE_MASK_VARIATION;
        int typeClass = type & InputType.TYPE_MASK_CLASS;
        if (typeClass == InputType.TYPE_CLASS_TEXT) {
            return variation == InputType.TYPE_TEXT_VARIATION_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                    || variation == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD;
        }
        return typeClass == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD;
    }

    private static void toast(InputMethodService service, String message) {
        try {
            Toast.makeText(service, message, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException ignored) {
            // Toasts are best effort.
        }
    }

    private static void log(String where, Throwable failure) {
        try {
            Log.w(TAG, "AI Bar " + where + " failed", failure);
        } catch (Throwable ignored) {
            // Logging must not affect Gboard.
        }
    }

    /** The field's text (or the part the app shares), its selection, and where that text starts. */
    static final class Field {
        final String text;
        final int selectionStart;
        final int selectionEnd;
        final int offset;

        Field(String text, int selectionStart, int selectionEnd, int offset) {
            this.text = text;
            this.selectionStart = selectionStart;
            this.selectionEnd = selectionEnd;
            this.offset = offset;
        }

        static Field read(InputConnection connection) {
            ExtractedText extracted = connection.getExtractedText(new ExtractedTextRequest(), 0);
            if (extracted != null && extracted.text != null) {
                return new Field(extracted.text.toString(), extracted.selectionStart, extracted.selectionEnd,
                        Math.max(extracted.startOffset, 0));
            }
            // Some apps don't support extracted text: rebuild it around the cursor.
            CharSequence before = connection.getTextBeforeCursor(10_000, 0);
            CharSequence selected = connection.getSelectedText(0);
            CharSequence after = connection.getTextAfterCursor(10_000, 0);
            String b = before == null ? "" : before.toString();
            String s = selected == null ? "" : selected.toString();
            String a = after == null ? "" : after.toString();
            // Absolute positions come from the last cursor update Gboard reported.
            int offset = Math.max(0, latestStart - b.length());
            return new Field(b + s + a, b.length(), b.length() + s.length(), offset);
        }
    }
}
