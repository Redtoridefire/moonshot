package tv.moonshot;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Ui {
  static final int BG = Color.rgb(11, 16, 24),
      PANEL = Color.rgb(18, 28, 40),
      TEXT = Color.rgb(238, 244, 249),
      MUTED = Color.rgb(148, 165, 184),
      MINT = Color.rgb(120, 227, 197);

  static int dp(android.content.Context c, int n) {
    return (int) (n * c.getResources().getDisplayMetrics().density + .5f);
  }

  static GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static StateListDrawable focus(android.content.Context c) {
    StateListDrawable d = new StateListDrawable();
    GradientDrawable focused = shape(Color.rgb(34, 64, 72), dp(c, 12));
    focused.setStroke(dp(c, 2), MINT);
    d.addState(new int[] {android.R.attr.state_focused}, focused);
    d.addState(new int[] {android.R.attr.state_pressed}, focused);
    d.addState(new int[] {android.R.attr.state_selected}, shape(Color.rgb(28, 48, 59), dp(c, 12)));
    d.addState(new int[] {}, shape(PANEL, dp(c, 12)));
    return d;
  }

  static TextView text(android.content.Context c, String value, int sp, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    return t;
  }

  static Button button(android.content.Context c, String label, Runnable action) {
    Button b = new Button(c);
    b.setText(label);
    b.setAllCaps(false);
    b.setTextSize(14);
    b.setTextColor(TEXT);
    b.setMinHeight(0);
    b.setMinimumHeight(0);
    b.setMinWidth(0);
    b.setMinimumWidth(0);
    b.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
    b.setBackground(focus(c));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  static LinearLayout column(android.content.Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  static LinearLayout row(android.content.Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  static EditText input(android.content.Context c, String hint, boolean secret) {
    EditText e = new EditText(c);
    e.setSingleLine(true);
    e.setTextColor(TEXT);
    e.setTextSize(16);
    e.setHintTextColor(MUTED);
    e.setHint(hint);
    e.setInputType(
        secret
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            : android.text.InputType.TYPE_CLASS_TEXT);
    if (secret) {
      e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
      e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    }
    return e;
  }

  static LinearLayout dialogBody(android.content.Context c) {
    LinearLayout l = column(c);
    int p = dp(c, 24);
    l.setPadding(p, p, p, p);
    return l;
  }

  static void immersive(Activity a) {
    a.getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
  }
}
