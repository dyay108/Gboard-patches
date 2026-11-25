package dev.jason.gboardpatches.extension.aibar;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.inputmethodservice.InputMethodService;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Small windows floating just above the keyboard: a chip (progress or Undo) at the right, and the
 * answer card for popup buttons. They are panels of Gboard's own window, never focusable, so the
 * app's text field keeps focus.
 */
final class AiBarOverlay {
    interface AnswerActions {
        void insert(String answer);
    }

    private final InputMethodService service;
    private PopupWindow chip;
    private PopupWindow card;

    AiBarOverlay(InputMethodService service) {
        this.service = service;
    }

    boolean isFor(InputMethodService other) {
        return service == other;
    }

    void showProgress(String label) {
        LinearLayout content = pill();
        ProgressBar spinner = new ProgressBar(service);
        spinner.setIndeterminate(true);
        spinner.setIndeterminateTintList(ColorStateList.valueOf(accent()));
        content.addView(spinner, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView text = label(label + "…");
        text.setPadding(dp(8), 0, 0, 0);
        content.addView(text);
        showChip(content);
    }

    void showUndo(Runnable onUndo) {
        LinearLayout content = pill();
        TextView text = label("↶  Undo");
        content.addView(text);
        content.setOnClickListener(v -> onUndo.run());
        showChip(content);
    }

    void hideChip() {
        dismiss(chip);
        chip = null;
    }

    void showAnswer(String answer, AnswerActions actions) {
        dismiss(card);
        LinearLayout box = new LinearLayout(service);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(16), dp(12), dp(8), dp(4));
        box.setBackground(rounded(surface(), dp(16), true));
        box.setElevation(dp(4));

        TextView text = label(answer);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        text.setGravity(Gravity.START);
        text.setLineSpacing(0, 1.15f);
        ScrollView scroll = new ScrollView(service) {
            @Override protected void onMeasure(int widthSpec, int heightSpec) {
                super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(dp(220), MeasureSpec.AT_MOST));
            }
        };
        scroll.addView(text);
        box.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout row = new LinearLayout(service);
        row.setGravity(Gravity.END);
        row.addView(action("Copy", () -> {
            ClipboardManager clipboard = service.getSystemService(ClipboardManager.class);
            if (clipboard != null) clipboard.setPrimaryClip(ClipData.newPlainText("AI Bar", answer));
            hideAnswer();
        }));
        row.addView(action("Insert", () -> {
            hideAnswer();
            actions.insert(answer);
        }));
        row.addView(action("Close", this::hideAnswer));
        box.addView(row, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        int width = anchorWidth() - dp(16);
        card = show(box, width, dp(8), true);
    }

    void hideAnswer() {
        dismiss(card);
        card = null;
    }

    void dismissAll() {
        hideChip();
        hideAnswer();
    }

    private void showChip(View content) {
        hideChip();
        chip = show(content, ViewGroup.LayoutParams.WRAP_CONTENT, -1, false);
    }

    /**
     * Shows {@code content} above the keyboard. {@code x} < 0 aligns it to the right edge. Cards sit
     * above the chip row so both can show at once.
     */
    private PopupWindow show(View content, int width, int x, boolean aboveChipRow) {
        View anchor = anchor();
        if (anchor == null || anchor.getWindowToken() == null) return null;
        content.measure(View.MeasureSpec.makeMeasureSpec(width > 0 ? width : anchorWidth(),
                        width > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        PopupWindow popup = new PopupWindow(content, width > 0 ? width : content.getMeasuredWidth(),
                content.getMeasuredHeight(), false);
        popup.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        popup.setClippingEnabled(false);
        popup.setOutsideTouchable(false);
        popup.setTouchable(true);
        int left = x >= 0 ? x : anchorWidth() - content.getMeasuredWidth() - dp(8);
        int lift = aboveChipRow ? dp(48) : dp(6);
        int top = keyboardTopInWindow() - content.getMeasuredHeight() - lift;
        try {
            popup.showAtLocation(anchor, Gravity.TOP | Gravity.START, left, Math.max(top, 0));
            return popup;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private View anchor() {
        try {
            return service.getWindow().getWindow().getDecorView();
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int anchorWidth() {
        View anchor = anchor();
        return anchor == null || anchor.getWidth() == 0 ? service.getResources().getDisplayMetrics().widthPixels : anchor.getWidth();
    }

    /**
     * The top of the visible keyboard (Gboard's toolbar included), relative to Gboard's window:
     * popups of the keyboard window are positioned in that window's coordinates.
     */
    private int keyboardTopInWindow() {
        try {
            InputMethodService.Insets insets = new InputMethodService.Insets();
            service.onComputeInsets(insets);
            if (insets.contentTopInsets > 0) return insets.contentTopInsets;
        } catch (RuntimeException ignored) {
            // Fall back to the input area below.
        }
        View anchor = anchor();
        View inputArea = anchor == null ? null : anchor.findViewById(android.R.id.inputArea);
        if (inputArea != null) {
            int[] window = new int[2];
            int[] area = new int[2];
            anchor.getLocationOnScreen(window);
            inputArea.getLocationOnScreen(area);
            return area[1] - window[1];
        }
        return service.getResources().getDisplayMetrics().heightPixels / 2;
    }

    private LinearLayout pill() {
        LinearLayout pill = new LinearLayout(service);
        pill.setOrientation(LinearLayout.HORIZONTAL);
        pill.setGravity(Gravity.CENTER_VERTICAL);
        pill.setPadding(dp(14), dp(8), dp(16), dp(8));
        pill.setBackground(rounded(surface(), dp(20), true));
        pill.setElevation(dp(3));
        return pill;
    }

    private TextView label(String value) {
        TextView text = new TextView(service);
        text.setText(value);
        text.setTextColor(onSurface());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        return text;
    }

    private TextView action(String value, Runnable onTap) {
        TextView text = label(value);
        text.setTextColor(accent());
        text.setPadding(dp(14), dp(10), dp(14), dp(10));
        text.setOnClickListener(v -> onTap.run());
        return text;
    }

    private GradientDrawable rounded(int color, int radius, boolean stroke) {
        GradientDrawable shape = new GradientDrawable();
        shape.setCornerRadius(radius);
        shape.setColor(color);
        if (stroke) shape.setStroke(dp(1), dark() ? 0xFF44474A : 0xFFD3D7DB);
        return shape;
    }

    private boolean dark() {
        return (service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    private int surface() { return dark() ? 0xFF2B2C2F : 0xFFFFFFFF; }
    private int onSurface() { return dark() ? 0xFFE3E3E3 : 0xFF1F1F1F; }
    private int accent() { return dark() ? 0xFFA8C7FA : 0xFF0B57D0; }

    private int dp(int value) {
        return Math.round(value * service.getResources().getDisplayMetrics().density);
    }

    private static void dismiss(PopupWindow popup) {
        try {
            if (popup != null && popup.isShowing()) popup.dismiss();
        } catch (RuntimeException ignored) {
            // The keyboard window is already gone.
        }
    }

}
