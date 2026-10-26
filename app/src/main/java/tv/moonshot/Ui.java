package tv.moonshot;

import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;

final class Ui {
  static final int BG = Color.rgb(11, 16, 24),
      PANEL = Color.rgb(21, 29, 42),
      BORDER = Color.rgb(43, 56, 74),
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

  static GradientDrawable gradient(int first, int last, int radius) {
    GradientDrawable d =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {first, last});
    d.setCornerRadius(radius);
    return d;
  }

  static StateListDrawable focus(android.content.Context c) {
    return surface(c, PANEL, false);
  }

  static StateListDrawable surface(android.content.Context c, int tint, boolean navigation) {
    StateListDrawable d = new StateListDrawable();
    GradientDrawable focused = gradient(Color.rgb(38, 75, 79), Color.rgb(26, 46, 60), dp(c, 12));
    focused.setStroke(dp(c, 2), MINT);
    d.addState(new int[] {android.R.attr.state_focused}, focused);
    d.addState(new int[] {android.R.attr.state_pressed}, focused);
    GradientDrawable selected = gradient(Color.rgb(29, 65, 65), Color.rgb(23, 43, 53), dp(c, 12));
    selected.setStroke(dp(c, 1), Color.rgb(59, 112, 107));
    d.addState(new int[] {android.R.attr.state_selected}, selected);
    GradientDrawable rest = gradient(tint, navigation ? BG : PANEL, dp(c, 12));
    if (!navigation) rest.setStroke(dp(c, 1), BORDER);
    d.addState(new int[] {}, rest);
    return d;
  }

  static void animateFocus(View v, boolean focused) {
    v.animate().cancel();
    v.animate().translationZ(dp(v.getContext(), focused ? 4 : 0)).setDuration(120).start();
  }

  static Button navButton(android.content.Context c, String label, String icon, Runnable action) {
    Button b = button(c, label, action);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setTextSize(13);
    b.setBackground(surface(c, BG, true));
    b.setCompoundDrawablePadding(dp(c, 12));
    android.graphics.drawable.Drawable glyph = new TvIcon(icon);
    glyph.setBounds(0, 0, dp(c, 19), dp(c, 19));
    b.setCompoundDrawables(glyph, null, null, null);
    return b;
  }

  /** Small original vector icons: no font-glyph or remote artwork dependency. */
  static final class TvIcon extends android.graphics.drawable.Drawable {
    final String kind;
    final android.graphics.Paint paint = new android.graphics.Paint(3);

    TvIcon(String value) {
      kind = value;
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
      canvas.save();
      canvas.translate(getBounds().left, getBounds().top);
      canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      paint.setColor(MINT);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(1.7f);
      paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
      if (kind.equals("favorites")) {
        android.graphics.Path path = new android.graphics.Path();
        path.moveTo(12, 21);
        path.cubicTo(8, 17, 2, 13, 2, 8);
        path.cubicTo(2, 2, 9, 1, 12, 6);
        path.cubicTo(15, 1, 22, 2, 22, 8);
        path.cubicTo(22, 13, 16, 17, 12, 21);
        canvas.drawPath(path, paint);
      } else if (kind.equals("profiles")) {
        canvas.drawCircle(12, 7, 4, paint);
        canvas.drawRoundRect(4, 14, 20, 22, 5, 5, paint);
      } else if (kind.equals("settings")) {
        for (int y = 5; y <= 19; y += 7) {
          canvas.drawLine(3, y, 21, y, paint);
          canvas.drawCircle(y == 12 ? 8 : 16, y, 2.5f, paint);
        }
      } else if (kind.equals("guide")) {
        canvas.drawRoundRect(2, 3, 22, 21, 3, 3, paint);
        canvas.drawLine(2, 9, 22, 9, paint);
        canvas.drawLine(8, 9, 8, 21, paint);
        canvas.drawLine(8, 15, 22, 15, paint);
      } else if (kind.equals("movie")) {
        canvas.drawRoundRect(2, 3, 22, 21, 2, 2, paint);
        canvas.drawLine(7, 3, 7, 21, paint);
        canvas.drawLine(17, 3, 17, 21, paint);
        for (int y = 8; y <= 16; y += 8) {
          canvas.drawLine(2, y, 7, y, paint);
          canvas.drawLine(17, y, 22, y, paint);
        }
      } else if (kind.equals("series")) {
        canvas.drawRoundRect(3, 7, 21, 22, 3, 3, paint);
        canvas.drawLine(6, 3, 18, 3, paint);
        canvas.drawLine(8, 11, 8, 18, paint);
        canvas.drawLine(8, 11, 16, 15, paint);
        canvas.drawLine(8, 18, 16, 15, paint);
      } else {
        canvas.drawRoundRect(2, 3, 22, 18, 3, 3, paint);
        canvas.drawLine(8, 22, 16, 22, paint);
        canvas.drawLine(12, 18, 12, 22, paint);
        canvas.drawCircle(12, 10.5f, 2.5f, paint);
      }
      canvas.restore();
    }

    @Override
    public void setAlpha(int alpha) {
      paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(android.graphics.ColorFilter filter) {
      paint.setColorFilter(filter);
    }

    @Override
    public int getOpacity() {
      return android.graphics.PixelFormat.TRANSLUCENT;
    }
  }

  static TextView text(android.content.Context c, String value, int sp, int color) {
    TextView t = new TextView(c);
    t.setText(value);
    t.setTextSize(sp);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setTypeface(android.graphics.Typeface.create("sans-serif", android.graphics.Typeface.NORMAL));
    t.setIncludeFontPadding(false);
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
    b.setStateListAnimator(null);
    b.setOnFocusChangeListener((v, focused) -> animateFocus(v, focused));
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

  static EditText address(android.content.Context c, String hint) {
    EditText e = input(c, hint, false);
    e.setInputType(
        android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
    e.setTransformationMethod(null);
    e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    e.setImeOptions(android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
    e.setSelectAllOnFocus(true);
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
