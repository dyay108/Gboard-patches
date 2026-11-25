package dev.jason.gboardpatches.extension.aibar;

import android.app.AlertDialog;
import android.content.Context;
import android.text.InputType;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import dev.jason.gboardpatches.extension.settings.GboardPatchesSettingsContract;

/** Text dialogs the shared settings dialogs don't cover: multi-line text and a masked API key. */
final class AiBarDialogs {
    interface Save {
        /** Throw IllegalArgumentException to show the message on the field and keep the dialog open. */
        void accept(String value);
    }

    private AiBarDialogs() {}

    static void multiLine(GboardPatchesSettingsContract.FeatureHost host, String title, String hint, String initial, Save save) {
        show(host, title, hint, initial, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, 4, save);
    }

    static void secret(GboardPatchesSettingsContract.FeatureHost host, String title, String hint, Save save) {
        show(host, title, hint, "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 1, save);
    }

    private static void show(GboardPatchesSettingsContract.FeatureHost host, String title, String hint,
            String initial, int inputType, int minLines, Save save) {
        GboardPatchesSettingsContract.showManagedDialog(host, onDismiss -> {
            Context context = host.getContext();
            EditText input = new EditText(context);
            input.setInputType(inputType);
            input.setHint(hint);
            input.setText(initial == null ? "" : initial);
            if (minLines > 1) {
                input.setMinLines(minLines);
                input.setMaxLines(10);
            }
            LinearLayout container = new LinearLayout(context);
            container.setOrientation(LinearLayout.VERTICAL);
            int pad = Math.round(24 * context.getResources().getDisplayMetrics().density);
            container.setPadding(pad, pad / 3, pad, 0);
            container.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            AlertDialog dialog = new AlertDialog.Builder(context)
                    .setTitle(title)
                    .setView(container)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNegativeButton(android.R.string.cancel, null)
                    .create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                try {
                    save.accept(input.getText() == null ? "" : input.getText().toString());
                    dialog.dismiss();
                    GboardPatchesSettingsContract.refresh(host);
                } catch (IllegalArgumentException e) {
                    input.setError(e.getMessage());
                }
            }));
            dialog.setOnDismissListener(ignored -> onDismiss.run());
            dialog.show();
            return true;
        });
    }
}
