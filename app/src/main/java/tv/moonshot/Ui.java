package tv.moonshot;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.*;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.view.*;
import android.view.animation.*;
import android.widget.*;

/** Moonshot's design system: tokens, accent palettes, focus motion and original vector icons. */
final class Ui {
  static final int BG = Color.rgb(9, 13, 21),
      BG_RAISED = Color.rgb(14, 20, 31),
      PANEL = Color.rgb(20, 28, 41),
      PANEL_HI = Color.rgb(28, 38, 55),
      BORDER = Color.rgb(40, 52, 71),
      TEXT = Color.rgb(240, 245, 250),
      MUTED = Color.rgb(146, 160, 181),
      FAINT = Color.rgb(92, 106, 128),
      LIVE = Color.rgb(255, 84, 104),
      WARN = Color.rgb(255, 196, 92),
      GOOD = Color.rgb(108, 222, 160);

  static final String[] ACCENT_NAMES = {"Mint", "Aurora", "Sunset", "Ocean", "Blossom"};
  static final int[] ACCENTS = {
    Color.rgb(120, 227, 197),
    Color.rgb(182, 156, 255),
    Color.rgb(255, 184, 107),
    Color.rgb(108, 184, 255),
    Color.rgb(255, 143, 177)
  };
  static final int[] THEMES = {
    R.style.AppTheme,
    R.style.AppTheme_Aurora,
    R.style.AppTheme_Sunset,
    R.style.AppTheme_Ocean,
    R.style.AppTheme_Blossom
  };

  /** Current accent. Set once per process (and on change) from preferences. */
  static int ACCENT = ACCENTS[0];

  static int accentIndex = 0;

  static void configure(android.content.Context c) {
    int index = c.getSharedPreferences("preferences", 0).getInt("accent", 0);
    accentIndex = index < 0 || index >= ACCENTS.length ? 0 : index;
    ACCENT = ACCENTS[accentIndex];
  }

  static int theme() {
    return THEMES[accentIndex];
  }

  /** Mixes {@code amount} (0..1) of {@code over} onto {@code base}. */
  static int mix(int base, int over, float amount) {
    return (Integer) new ArgbEvaluator().evaluate(amount, base, over);
  }

  static int alpha(int color, int alpha) {
    return (color & 0x00ffffff) | (alpha << 24);
  }

  /** Dark text that stays legible on top of the accent color. */
  static int onAccent() {
    return Color.rgb(8, 14, 22);
  }

  static int dp(android.content.Context c, int n) {
    return (int) (n * c.getResources().getDisplayMetrics().density + .5f);
  }

  static GradientDrawable shape(int color, int radius) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(radius);
    return d;
  }

  static GradientDrawable outline(int fill, int stroke, int width, int radius) {
    GradientDrawable d = shape(fill, radius);
    d.setStroke(width, stroke);
    return d;
  }

  static GradientDrawable gradient(int first, int last, int radius) {
    GradientDrawable d =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[] {first, last});
    d.setCornerRadius(radius);
    return d;
  }

  static GradientDrawable vertical(int top, int bottom, int radius) {
    GradientDrawable d =
        new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[] {top, bottom});
    d.setCornerRadius(radius);
    return d;
  }

  static StateListDrawable focus(android.content.Context c) {
    return surface(c, PANEL, false);
  }

  /** Card/button background: quiet at rest, accent-lit when focused, tinted when selected. */
  static StateListDrawable surface(android.content.Context c, int tint, boolean navigation) {
    int radius = dp(c, navigation ? 14 : 14);
    StateListDrawable d = new StateListDrawable();
    GradientDrawable focused =
        gradient(mix(PANEL_HI, ACCENT, .30f), mix(PANEL, ACCENT, .10f), radius);
    focused.setStroke(dp(c, 2), ACCENT);
    d.addState(new int[] {android.R.attr.state_focused}, focused);
    d.addState(new int[] {android.R.attr.state_pressed}, focused);
    GradientDrawable selected =
        gradient(mix(PANEL, ACCENT, .22f), mix(BG_RAISED, ACCENT, .08f), radius);
    selected.setStroke(dp(c, 1), alpha(ACCENT, 110));
    d.addState(new int[] {android.R.attr.state_selected}, selected);
    GradientDrawable rest =
        navigation ? shape(Color.TRANSPARENT, radius) : gradient(tint, mix(tint, BG, .55f), radius);
    if (!navigation) rest.setStroke(dp(c, 1), alpha(Color.WHITE, 16));
    d.addState(new int[] {}, rest);
    return d;
  }

  /** Pill chip: filled with the accent when selected, outlined when focused. */
  static StateListDrawable chipBackground(android.content.Context c) {
    int r = dp(c, 999);
    StateListDrawable d = new StateListDrawable();
    d.addState(
        new int[] {android.R.attr.state_focused, android.R.attr.state_selected},
        outline(ACCENT, TEXT, dp(c, 2), r));
    d.addState(
        new int[] {android.R.attr.state_focused}, outline(mix(PANEL, ACCENT, .25f), ACCENT, dp(c, 2), r));
    d.addState(new int[] {android.R.attr.state_selected}, shape(ACCENT, r));
    d.addState(new int[] {}, outline(alpha(PANEL, 200), alpha(Color.WHITE, 22), dp(c, 1), r));
    return d;
  }

  static android.content.res.ColorStateList chipText() {
    return new android.content.res.ColorStateList(
        new int[][] {
          {android.R.attr.state_selected}, {android.R.attr.state_focused}, {}
        },
        new int[] {onAccent(), TEXT, MUTED});
  }

  /** Lifts and enlarges the focused element; restores it smoothly on blur. */
  static void animateFocus(View v, boolean focused) {
    animateFocus(v, focused, 1.05f);
  }

  static void animateFocus(View v, boolean focused, float scale) {
    v.animate().cancel();
    v.animate()
        .scaleX(focused ? scale : 1f)
        .scaleY(focused ? scale : 1f)
        .translationZ(dp(v.getContext(), focused ? 10 : 0))
        .setInterpolator(new DecelerateInterpolator(2f))
        .setDuration(focused ? 170 : 140)
        .start();
  }

  static Button navButton(android.content.Context c, String label, String icon, Runnable action) {
    Button b = button(c, label, action);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setTextSize(14);
    b.setSingleLine(true);
    b.setBackground(surface(c, BG, true));
    b.setPadding(dp(c, 16), 0, dp(c, 12), 0);
    b.setCompoundDrawablePadding(dp(c, 14));
    android.graphics.drawable.Drawable glyph = new TvIcon(icon);
    glyph.setBounds(0, 0, dp(c, 20), dp(c, 20));
    b.setCompoundDrawables(glyph, null, null, null);
    b.setOnFocusChangeListener((v, focused) -> animateFocus(v, focused, 1.02f));
    return b;
  }

  static Drawable icon(android.content.Context c, String kind, int sizeDp, int color) {
    TvIcon icon = new TvIcon(kind, color);
    icon.setBounds(0, 0, dp(c, sizeDp), dp(c, sizeDp));
    return icon;
  }

  /** Small original vector icons: no font-glyph or remote artwork dependency. */
  static final class TvIcon extends android.graphics.drawable.Drawable {
    final String kind;
    final Integer fixed;
    final android.graphics.Paint paint = new android.graphics.Paint(3);

    TvIcon(String value) {
      this(value, null);
    }

    TvIcon(String value, Integer color) {
      kind = value;
      fixed = color;
    }

    @Override
    public void draw(android.graphics.Canvas canvas) {
      canvas.save();
      canvas.translate(getBounds().left, getBounds().top);
      canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f);
      paint.setColor(fixed != null ? fixed : ACCENT);
      paint.setStyle(android.graphics.Paint.Style.STROKE);
      paint.setStrokeWidth(1.8f);
      paint.setStrokeCap(android.graphics.Paint.Cap.ROUND);
      paint.setStrokeJoin(android.graphics.Paint.Join.ROUND);
      android.graphics.Path path = new android.graphics.Path();
      switch (kind) {
        case "favorites":
          path.moveTo(12, 21);
          path.cubicTo(8, 17, 2, 13, 2, 8);
          path.cubicTo(2, 2, 9, 1, 12, 6);
          path.cubicTo(15, 1, 22, 2, 22, 8);
          path.cubicTo(22, 13, 16, 17, 12, 21);
          canvas.drawPath(path, paint);
          break;
        case "heart-filled":
          paint.setStyle(android.graphics.Paint.Style.FILL);
          path.moveTo(12, 21);
          path.cubicTo(8, 17, 2, 13, 2, 8);
          path.cubicTo(2, 2, 9, 1, 12, 6);
          path.cubicTo(15, 1, 22, 2, 22, 8);
          path.cubicTo(22, 13, 16, 17, 12, 21);
          canvas.drawPath(path, paint);
          break;
        case "profiles":
          canvas.drawCircle(12, 7, 4, paint);
          canvas.drawRoundRect(4, 14, 20, 22, 5, 5, paint);
          break;
        case "settings":
          for (int y = 5; y <= 19; y += 7) {
            canvas.drawLine(3, y, 21, y, paint);
            canvas.drawCircle(y == 12 ? 8 : 16, y, 2.5f, paint);
          }
          break;
        case "guide":
          canvas.drawRoundRect(2, 3, 22, 21, 3, 3, paint);
          canvas.drawLine(2, 9, 22, 9, paint);
          canvas.drawLine(8, 9, 8, 21, paint);
          canvas.drawLine(8, 15, 22, 15, paint);
          break;
        case "movie":
          canvas.drawRoundRect(2, 3, 22, 21, 2, 2, paint);
          canvas.drawLine(7, 3, 7, 21, paint);
          canvas.drawLine(17, 3, 17, 21, paint);
          for (int y = 8; y <= 16; y += 8) {
            canvas.drawLine(2, y, 7, y, paint);
            canvas.drawLine(17, y, 22, y, paint);
          }
          break;
        case "series":
          canvas.drawRoundRect(3, 7, 21, 22, 3, 3, paint);
          canvas.drawLine(6, 3, 18, 3, paint);
          canvas.drawLine(8, 11, 8, 18, paint);
          canvas.drawLine(8, 11, 16, 15, paint);
          canvas.drawLine(8, 18, 16, 15, paint);
          break;
        case "recent":
          canvas.drawCircle(12, 12, 9, paint);
          canvas.drawLine(12, 7, 12, 12, paint);
          canvas.drawLine(12, 12, 16, 14, paint);
          break;
        case "search":
          canvas.drawCircle(10.5f, 10.5f, 6.5f, paint);
          canvas.drawLine(15.5f, 15.5f, 21, 21, paint);
          break;
        case "add":
          canvas.drawRoundRect(3, 3, 21, 21, 5, 5, paint);
          canvas.drawLine(12, 8, 12, 16, paint);
          canvas.drawLine(8, 12, 16, 12, paint);
          break;
        case "link":
          canvas.drawRoundRect(2, 8, 13, 16, 4, 4, paint);
          canvas.drawRoundRect(11, 8, 22, 16, 4, 4, paint);
          break;
        case "key":
          canvas.drawCircle(7.5f, 12, 4.5f, paint);
          canvas.drawLine(12, 12, 22, 12, paint);
          canvas.drawLine(18, 12, 18, 16, paint);
          canvas.drawLine(21, 12, 21, 15, paint);
          break;
        case "pulse":
          path.moveTo(2, 12);
          path.lineTo(7, 12);
          path.lineTo(9.5f, 5);
          path.lineTo(14, 19);
          path.lineTo(16.5f, 12);
          path.lineTo(22, 12);
          canvas.drawPath(path, paint);
          break;
        case "refresh":
          canvas.drawArc(4, 4, 20, 20, -60, 290, false, paint);
          canvas.drawLine(18.5f, 3.5f, 19, 8.5f, paint);
          canvas.drawLine(19, 8.5f, 14, 8, paint);
          break;
        case "shield":
          path.moveTo(12, 2);
          path.lineTo(20, 5);
          path.lineTo(20, 11);
          path.cubicTo(20, 16, 16.5f, 20, 12, 22);
          path.cubicTo(7.5f, 20, 4, 16, 4, 11);
          path.lineTo(4, 5);
          path.close();
          canvas.drawPath(path, paint);
          break;
        case "lock":
          canvas.drawRoundRect(4, 10, 20, 21, 3, 3, paint);
          canvas.drawArc(7, 3, 17, 15, 180, 180, false, paint);
          canvas.drawLine(7, 9, 7, 10, paint);
          canvas.drawLine(17, 9, 17, 10, paint);
          break;
        case "globe":
          canvas.drawCircle(12, 12, 9, paint);
          canvas.drawOval(8, 3, 16, 21, paint);
          canvas.drawLine(3, 12, 21, 12, paint);
          break;
        case "palette":
          canvas.drawCircle(12, 12, 9, paint);
          paint.setStyle(android.graphics.Paint.Style.FILL);
          canvas.drawCircle(8, 9, 1.4f, paint);
          canvas.drawCircle(12, 7, 1.4f, paint);
          canvas.drawCircle(16, 9, 1.4f, paint);
          canvas.drawCircle(16.5f, 14, 1.4f, paint);
          break;
        case "eye":
          path.moveTo(2, 12);
          path.cubicTo(6, 5, 18, 5, 22, 12);
          path.cubicTo(18, 19, 6, 19, 2, 12);
          canvas.drawPath(path, paint);
          canvas.drawCircle(12, 12, 3, paint);
          break;
        case "folder":
          path.moveTo(2, 6);
          path.lineTo(9, 6);
          path.lineTo(11, 8);
          path.lineTo(22, 8);
          path.lineTo(22, 20);
          path.lineTo(2, 20);
          path.close();
          canvas.drawPath(path, paint);
          break;
        case "sort":
          canvas.drawLine(7, 4, 7, 20, paint);
          canvas.drawLine(3, 16, 7, 20, paint);
          canvas.drawLine(11, 16, 7, 20, paint);
          canvas.drawLine(17, 20, 17, 4, paint);
          canvas.drawLine(13, 8, 17, 4, paint);
          canvas.drawLine(21, 8, 17, 4, paint);
          break;
        case "record":
          canvas.drawCircle(12, 12, 9, paint);
          paint.setStyle(android.graphics.Paint.Style.FILL);
          canvas.drawCircle(12, 12, 4, paint);
          break;
        case "upload":
          canvas.drawLine(12, 4, 12, 15, paint);
          canvas.drawLine(7, 9, 12, 4, paint);
          canvas.drawLine(17, 9, 12, 4, paint);
          canvas.drawLine(4, 20, 20, 20, paint);
          break;
        case "download":
          canvas.drawLine(12, 4, 12, 15, paint);
          canvas.drawLine(7, 10, 12, 15, paint);
          canvas.drawLine(17, 10, 12, 15, paint);
          canvas.drawLine(4, 20, 20, 20, paint);
          break;
        case "grid":
          canvas.drawRoundRect(3, 3, 11, 11, 2, 2, paint);
          canvas.drawRoundRect(13, 3, 21, 11, 2, 2, paint);
          canvas.drawRoundRect(3, 13, 11, 21, 2, 2, paint);
          canvas.drawRoundRect(13, 13, 21, 21, 2, 2, paint);
          break;
        case "list":
          for (int y = 6; y <= 18; y += 6) {
            canvas.drawCircle(4.5f, y, 1, paint);
            canvas.drawLine(9, y, 21, y, paint);
          }
          break;
        case "info":
          canvas.drawCircle(12, 12, 9, paint);
          canvas.drawLine(12, 11, 12, 17, paint);
          canvas.drawPoint(12, 7.5f, paint);
          break;
        case "people":
          canvas.drawCircle(9, 8, 3.5f, paint);
          canvas.drawRoundRect(3, 14, 15, 21, 4, 4, paint);
          canvas.drawCircle(17, 9, 2.5f, paint);
          canvas.drawLine(17, 14, 21, 14, paint);
          canvas.drawLine(21, 14, 21, 20, paint);
          break;
        case "image":
          canvas.drawRoundRect(2, 4, 22, 20, 3, 3, paint);
          canvas.drawCircle(8, 9, 2, paint);
          path.moveTo(3, 18);
          path.lineTo(10, 12);
          path.lineTo(14, 15);
          path.lineTo(17, 12);
          path.lineTo(21, 16);
          canvas.drawPath(path, paint);
          break;
        case "moon":
          paint.setStyle(android.graphics.Paint.Style.FILL);
          path.addCircle(12, 12, 9, android.graphics.Path.Direction.CW);
          android.graphics.Path bite = new android.graphics.Path();
          bite.addCircle(16, 9, 7.5f, android.graphics.Path.Direction.CW);
          path.op(bite, android.graphics.Path.Op.DIFFERENCE);
          canvas.drawPath(path, paint);
          break;
        case "play":
          paint.setStyle(android.graphics.Paint.Style.FILL);
          path.moveTo(7, 4);
          path.lineTo(20, 12);
          path.lineTo(7, 20);
          path.close();
          canvas.drawPath(path, paint);
          break;
        default:
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

  static TextView heading(android.content.Context c, String value, int sp) {
    TextView t = text(c, value, sp, TEXT);
    t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
    t.setLetterSpacing(-0.01f);
    return t;
  }

  /** Small all-caps label used above titles. */
  static TextView eyebrow(android.content.Context c, String value, int color) {
    TextView t = text(c, value.toUpperCase(java.util.Locale.ROOT), 10, color);
    t.setLetterSpacing(.16f);
    t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    return t;
  }

  /** Non-focusable informational pill, e.g. "● LIVE" or "OK  Watch". */
  static TextView pill(android.content.Context c, String value, int fg, int bg) {
    TextView t = text(c, value, 10, fg);
    t.setLetterSpacing(.08f);
    t.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
    t.setPadding(dp(c, 9), dp(c, 4), dp(c, 9), dp(c, 4));
    t.setBackground(shape(bg, dp(c, 999)));
    t.setGravity(Gravity.CENTER);
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
    b.setPadding(dp(c, 16), dp(c, 10), dp(c, 16), dp(c, 10));
    b.setBackground(focus(c));
    b.setStateListAnimator(null);
    b.setOnFocusChangeListener((v, focused) -> animateFocus(v, focused, 1.04f));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  /** Accent-filled primary call to action. */
  static Button primary(android.content.Context c, String label, Runnable action) {
    Button b = button(c, label, action);
    StateListDrawable d = new StateListDrawable();
    d.addState(
        new int[] {android.R.attr.state_focused}, outline(ACCENT, TEXT, dp(c, 2), dp(c, 14)));
    d.addState(new int[] {}, shape(ACCENT, dp(c, 14)));
    b.setBackground(d);
    b.setTextColor(onAccent());
    b.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.BOLD));
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

  /** Smoothly shifts a gradient's colors, used for the ambient backdrop. */
  static void shiftGradient(GradientDrawable d, int[] from, int[] to, long ms) {
    ValueAnimator a = ValueAnimator.ofFloat(0, 1);
    a.setDuration(ms);
    a.setInterpolator(new DecelerateInterpolator());
    ArgbEvaluator e = new ArgbEvaluator();
    a.addUpdateListener(
        v -> {
          float f = (float) v.getAnimatedValue();
          int[] now = new int[to.length];
          for (int i = 0; i < to.length; i++) now[i] = (Integer) e.evaluate(f, from[i], to[i]);
          d.setColors(now);
        });
    a.start();
  }

  /**
   * An in-app notice that slides up from the bottom of the screen and fades away: easier to read
   * on a TV than a system toast, and it respects the app's colors.
   */
  static void notice(Activity a, String message) {
    if (a.isFinishing() || a.isDestroyed()) return;
    View content = a.findViewById(android.R.id.content);
    if (!(content instanceof FrameLayout)) {
      Toast.makeText(a, message, Toast.LENGTH_LONG).show();
      return;
    }
    FrameLayout host = (FrameLayout) content;
    View old = host.findViewWithTag("moonshot-notice");
    if (old != null) host.removeView(old);
    LinearLayout bar = row(a);
    bar.setTag("moonshot-notice");
    bar.setPadding(dp(a, 18), dp(a, 14), dp(a, 22), dp(a, 14));
    GradientDrawable bg = gradient(mix(PANEL_HI, ACCENT, .14f), PANEL, dp(a, 16));
    bg.setStroke(dp(a, 1), alpha(ACCENT, 120));
    bar.setBackground(bg);
    bar.setElevation(dp(a, 16));
    ImageView glyph = new ImageView(a);
    glyph.setImageDrawable(icon(a, "info", 20, ACCENT));
    bar.addView(glyph, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
    TextView t = text(a, message, 14, TEXT);
    t.setLineSpacing(0, 1.15f);
    t.setPadding(dp(a, 14), 0, 0, 0);
    t.setMaxWidth(dp(a, 620));
    bar.addView(t);
    bar.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
    bar.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    FrameLayout.LayoutParams lp =
        new FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
    lp.bottomMargin = dp(a, 32);
    host.addView(bar, lp);
    bar.setAlpha(0f);
    bar.setTranslationY(dp(a, 40));
    bar.animate()
        .alpha(1f)
        .translationY(0)
        .setDuration(220)
        .setInterpolator(new DecelerateInterpolator(2f))
        .start();
    long visible = Math.min(9000, 3500 + message.length() * 30L);
    bar.postDelayed(
        () ->
            bar.animate()
                .alpha(0f)
                .translationY(dp(a, 24))
                .setDuration(260)
                .withEndAction(() -> host.removeView(bar))
                .start(),
        visible);
  }

  /** Fades and lifts children into place; used when a screen's content changes. */
  static LayoutAnimationController entrance() {
    AnimationSet set = new AnimationSet(true);
    AlphaAnimation fade = new AlphaAnimation(0f, 1f);
    TranslateAnimation lift =
        new TranslateAnimation(
            Animation.RELATIVE_TO_SELF, 0, Animation.RELATIVE_TO_SELF, 0,
            Animation.RELATIVE_TO_SELF, .18f, Animation.RELATIVE_TO_SELF, 0);
    set.addAnimation(fade);
    set.addAnimation(lift);
    set.setDuration(260);
    set.setInterpolator(new DecelerateInterpolator(2f));
    LayoutAnimationController controller = new LayoutAnimationController(set, .06f);
    controller.setOrder(LayoutAnimationController.ORDER_NORMAL);
    return controller;
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
