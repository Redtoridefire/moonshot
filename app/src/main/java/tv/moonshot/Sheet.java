package tv.moonshot;

import android.app.Activity;
import android.app.Dialog;
import android.graphics.Color;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * A panel that slides in from the right edge, Google TV style. Rows are remote-friendly: a single
 * vertical focus path, accent focus ring, and values that refresh when the sheet regains focus
 * (for example after a nested dialog changes a setting).
 */
final class Sheet {
  final Activity activity;
  final Dialog dialog;
  final LinearLayout list;
  final LinearLayout panel;
  private final List<Runnable> refreshers = new ArrayList<>();
  private View firstFocusable;

  Sheet(Activity a, String eyebrow, String title, String subtitle) {
    activity = a;
    dialog = new Dialog(a, R.style.MoonshotSheet);
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.getWindow().setLayout(-1, -1);
    FrameLayout root = new FrameLayout(a);
    root.setOnClickListener(v -> dialog.dismiss());
    panel = Ui.column(a);
    panel.setClickable(true);
    panel.setBackground(Ui.vertical(Ui.mix(Ui.PANEL, Ui.ACCENT, .06f), Ui.BG_RAISED, 0));
    panel.setElevation(Ui.dp(a, 24));
    int pad = Ui.dp(a, 28);
    panel.setPadding(pad, pad, Ui.dp(a, 22), Ui.dp(a, 12));
    root.addView(panel, new FrameLayout.LayoutParams(Ui.dp(a, 470), -1, Gravity.END));
    View edge = new View(a);
    edge.setBackground(
        new android.graphics.drawable.GradientDrawable(
            android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM,
            new int[] {Ui.ACCENT, Ui.alpha(Ui.ACCENT, 0)}));
    root.addView(edge, new FrameLayout.LayoutParams(Ui.dp(a, 2), -1, Gravity.END));
    ((FrameLayout.LayoutParams) edge.getLayoutParams()).rightMargin = Ui.dp(a, 470);
    if (eyebrow != null) panel.addView(Ui.eyebrow(a, eyebrow, Ui.ACCENT));
    TextView heading = Ui.heading(a, title, 26);
    heading.setPadding(0, Ui.dp(a, 8), 0, 0);
    panel.addView(heading);
    if (subtitle != null) {
      TextView sub = Ui.text(a, subtitle, 13, Ui.MUTED);
      sub.setLineSpacing(0, 1.2f);
      sub.setPadding(0, Ui.dp(a, 8), 0, 0);
      panel.addView(sub);
    }
    ScrollView scroll = new ScrollView(a);
    scroll.setVerticalScrollBarEnabled(false);
    scroll.setClipToPadding(false);
    scroll.setPadding(0, Ui.dp(a, 10), Ui.dp(a, 6), Ui.dp(a, 24));
    list = Ui.column(a);
    scroll.addView(list, new FrameLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, 0, 1);
    sp.topMargin = Ui.dp(a, 12);
    panel.addView(scroll, sp);
    dialog.setContentView(root);
    dialog
        .getWindow()
        .getDecorView()
        .getViewTreeObserver()
        .addOnWindowFocusChangeListener(
            focused -> {
              if (focused) refresh();
            });
  }

  void section(String label) {
    TextView t = Ui.eyebrow(activity, label, Ui.FAINT);
    t.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 18), 0, Ui.dp(activity, 8));
    list.addView(t);
  }

  /** A focusable action row. {@code value} may be null; it is re-read on refresh. */
  LinearLayout row(String icon, String title, Supplier<String> value, Runnable action) {
    Activity a = activity;
    LinearLayout row = Ui.row(a);
    row.setFocusable(true);
    row.setClickable(true);
    row.setBackground(Ui.surface(a, Color.TRANSPARENT, true));
    row.setPadding(Ui.dp(a, 14), Ui.dp(a, 12), Ui.dp(a, 14), Ui.dp(a, 12));
    ImageView glyph = new ImageView(a);
    glyph.setImageDrawable(Ui.icon(a, icon, 20, Ui.ACCENT));
    FrameLayout badge = new FrameLayout(a);
    badge.setBackground(Ui.shape(Ui.mix(Ui.PANEL, Ui.ACCENT, .14f), Ui.dp(a, 10)));
    badge.addView(
        glyph, new FrameLayout.LayoutParams(Ui.dp(a, 20), Ui.dp(a, 20), Gravity.CENTER));
    row.addView(badge, new LinearLayout.LayoutParams(Ui.dp(a, 38), Ui.dp(a, 38)));
    LinearLayout words = Ui.column(a);
    words.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 8), 0);
    TextView name = Ui.text(a, title, 15, Ui.TEXT);
    name.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    words.addView(name);
    TextView detail = Ui.text(a, "", 12, Ui.MUTED);
    detail.setPadding(0, Ui.dp(a, 4), 0, 0);
    detail.setMaxLines(2);
    detail.setEllipsize(android.text.TextUtils.TruncateAt.END);
    words.addView(detail);
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    TextView chevron = Ui.text(a, "›", 22, Ui.FAINT);
    row.addView(chevron);
    Runnable refresh =
        () -> {
          String v = value == null ? null : value.get();
          detail.setText(v == null ? "" : v);
          detail.setVisibility(v == null || v.isEmpty() ? View.GONE : View.VISIBLE);
        };
    refresh.run();
    refreshers.add(refresh);
    row.setContentDescription(title);
    row.setOnFocusChangeListener(
        (v, focused) -> {
          Ui.animateFocus(v, focused, 1.02f);
          chevron.setTextColor(focused ? Ui.ACCENT : Ui.FAINT);
        });
    row.setOnClickListener(
        v -> {
          action.run();
          refresh();
        });
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
    lp.bottomMargin = Ui.dp(a, 4);
    list.addView(row, lp);
    if (firstFocusable == null) firstFocusable = row;
    return row;
  }

  /** A static status line, e.g. one step of a connection test. */
  void line(String state, String title, String detail) {
    Activity a = activity;
    LinearLayout row = Ui.row(a);
    row.setGravity(Gravity.TOP);
    row.setPadding(Ui.dp(a, 6), Ui.dp(a, 8), Ui.dp(a, 6), Ui.dp(a, 8));
    int color =
        state.equals("ok")
            ? Ui.GOOD
            : state.equals("warn") ? Ui.WARN : state.equals("fail") ? Ui.LIVE : Ui.MUTED;
    TextView mark =
        Ui.text(
            a,
            state.equals("ok") ? "✓" : state.equals("warn") ? "!" : state.equals("fail") ? "✕" : "•",
            13,
            Ui.onAccent());
    mark.setGravity(Gravity.CENTER);
    mark.setTypeface(null, android.graphics.Typeface.BOLD);
    mark.setBackground(Ui.shape(color, Ui.dp(a, 999)));
    row.addView(mark, new LinearLayout.LayoutParams(Ui.dp(a, 22), Ui.dp(a, 22)));
    LinearLayout words = Ui.column(a);
    words.setPadding(Ui.dp(a, 14), Ui.dp(a, 2), 0, 0);
    TextView name = Ui.text(a, title, 14, Ui.TEXT);
    name.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    words.addView(name);
    if (detail != null && !detail.isEmpty()) {
      TextView d = Ui.text(a, detail, 12, Ui.MUTED);
      d.setLineSpacing(0, 1.2f);
      d.setPadding(0, Ui.dp(a, 4), 0, 0);
      words.addView(d);
    }
    row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
    list.addView(row);
  }

  /** A note paragraph inside the list. */
  void note(String text) {
    TextView t = Ui.text(activity, text, 13, Ui.MUTED);
    t.setLineSpacing(0, 1.25f);
    t.setPadding(Ui.dp(activity, 6), Ui.dp(activity, 10), Ui.dp(activity, 6), Ui.dp(activity, 6));
    list.addView(t);
  }

  Button button(String label, boolean primary, Runnable action) {
    Button b =
        primary ? Ui.primary(activity, label, action) : Ui.button(activity, label, action);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, Ui.dp(activity, 46));
    lp.topMargin = Ui.dp(activity, 10);
    list.addView(b, lp);
    if (firstFocusable == null) firstFocusable = b;
    return b;
  }

  void clear() {
    list.removeAllViews();
    refreshers.clear();
    firstFocusable = null;
  }

  void focusFirst() {
    if (firstFocusable != null) firstFocusable.requestFocus();
  }

  void refresh() {
    for (Runnable r : refreshers) r.run();
  }

  void dismiss() {
    if (dialog.isShowing()) dialog.dismiss();
  }

  void show() {
    if (activity.isFinishing() || activity.isDestroyed()) return;
    dialog.show();
    Ui.immersive(activity);
    panel.setTranslationX(Ui.dp(activity, 470));
    panel.setAlpha(.4f);
    panel
        .animate()
        .translationX(0)
        .alpha(1f)
        .setDuration(240)
        .setInterpolator(new DecelerateInterpolator(2.2f))
        .start();
    focusFirst();
  }
}
