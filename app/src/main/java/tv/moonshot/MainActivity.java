package tv.moonshot;

import android.animation.ValueAnimator;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.*;
import android.text.*;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends FamilyActivity {
  private static final int RAIL_OPEN = 212, RAIL_CLOSED = 76;
  private static final int[] TONES = {
    0xff1f4a5c, 0xff3a3563, 0xff1f5248, 0xff57374f, 0xff4d4430, 0xff27426b, 0xff4a2f3a
  };

  private Library library;
  private char[] backupPassword;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private RecyclerView grid, chips;
  private TextView title,
      subtitle,
      count,
      spotlightLabel,
      spotlightName,
      spotlightDetail,
      spotlightMark,
      spotlightGroup,
      brandName,
      version;
  private LinearLayout content, empty, rail, spotlightPanel;
  private GradientDrawable ambient, heroTint, markTint;
  private int[] ambientColors, heroColors, markColors;
  private Button layoutToggle;
  private String customGroup = null;
  private String displayedQuery, chipSection;
  private String section = "live", group = null, query = "";
  private int generation = 0;
  private boolean busy = false, compactLibrary, railOpen = true;
  private ValueAnimator railAnimator;
  private final List<Button> nav = new ArrayList<>();
  private final Map<Button, String> navLabels = new HashMap<>();
  private List<Channel> visible = new ArrayList<>();
  private android.content.SharedPreferences prefs;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    prefs = getSharedPreferences("preferences", MODE_PRIVATE);
    compactLibrary = prefs.getBoolean("compactLibrary", false);
    try {
      library = ((MoonshotApplication) getApplication()).openLibrary();
    } catch (Exception e) {
      new AlertDialog.Builder(this)
          .setTitle("Secure storage unavailable")
          .setMessage(
              "Moonshot could not open its device encryption key. Clear app data only if you are"
                  + " willing to remove your local library.")
          .setPositiveButton("Close", (d, w) -> finish())
          .setCancelable(false)
          .show();
      return;
    }
    if (state != null) {
      section = state.getString("section", "live");
      group = state.getString("group");
      query = state.getString("query", "");
      customGroup = state.getString("custom");
    }
    build();
    load();
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    state.putString("section", section);
    state.putString("group", group);
    state.putString("query", query);
    state.putString("custom", customGroup);
  }

  @Override
  protected void onResume() {
    super.onResume();
    Ui.immersive(this);
    if (library != null && grid != null) load();
  }

  // ---------------------------------------------------------------------------------------------
  // Layout

  private void build() {
    FrameLayout stage = new FrameLayout(this);
    stage.setBackground(Ui.vertical(Ui.BG_RAISED, Ui.BG, 0));
    setContentView(stage);
    View glow = new View(this);
    ambientColors = new int[] {Ui.alpha(Ui.ACCENT, 70), Ui.alpha(Ui.ACCENT, 0)};
    ambient = new GradientDrawable();
    ambient.setGradientType(GradientDrawable.RADIAL_GRADIENT);
    ambient.setGradientCenter(.78f, .05f);
    ambient.setGradientRadius(Ui.dp(this, 620));
    ambient.setColors(ambientColors);
    glow.setBackground(ambient);
    stage.addView(glow, new FrameLayout.LayoutParams(-1, -1));

    LinearLayout root = Ui.row(this);
    root.setGravity(Gravity.TOP);
    root.setClipChildren(false);
    root.setClipToPadding(false);
    int pad = Ui.dp(this, 22);
    root.setPadding(Ui.dp(this, 14), pad, pad, 0);
    stage.addView(root, new FrameLayout.LayoutParams(-1, -1));

    buildRail(root);

    content = Ui.column(this);
    content.setClipChildren(false);
    content.setClipToPadding(false);
    content.setPadding(Ui.dp(this, 18), 0, 0, 0);
    root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
    buildTopBar();
    buildSpotlight();

    chips = new RecyclerView(this);
    chips.setLayoutManager(new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
    chips.setClipToPadding(false);
    chips.setClipChildren(false);
    chips.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), Ui.dp(this, 20), Ui.dp(this, 6));
    chips.setItemAnimator(null);
    chips.setOverScrollMode(View.OVER_SCROLL_NEVER);
    LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 50));
    cp.topMargin = Ui.dp(this, 10);
    content.addView(chips, cp);

    count = Ui.text(this, "Loading library…", 12, Ui.MUTED);
    count.setPadding(Ui.dp(this, 2), Ui.dp(this, 6), 0, Ui.dp(this, 10));
    content.addView(count);

    FrameLayout area = new FrameLayout(this);
    area.setClipChildren(false);
    area.setClipToPadding(false);
    content.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new RecyclerView(this);
    grid.setLayoutManager(new GridLayoutManager(this, compactLibrary ? 1 : 4));
    grid.setClipToPadding(false);
    grid.setClipChildren(false);
    int edge = Ui.dp(this, 10);
    grid.setPadding(edge, edge, edge, Ui.dp(this, 28));
    grid.setItemAnimator(null);
    grid.setOverScrollMode(View.OVER_SCROLL_NEVER);
    grid.setLayoutAnimation(Ui.entrance());
    area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    empty = Ui.column(this);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(Ui.dp(this, 30), 0, Ui.dp(this, 30), Ui.dp(this, 20));
    area.addView(empty, new FrameLayout.LayoutParams(-1, -1));

    stage
        .getViewTreeObserver()
        .addOnGlobalFocusChangeListener(
            (old, now) -> {
              if (now == null) return;
              setRail(inside(rail, now));
            });
    for (Button b : nav) if (section.equals(b.getTag())) b.requestFocus();
  }

  private static boolean inside(View parent, View child) {
    for (View v = child; v != null; ) {
      if (v == parent) return true;
      ViewParent p = v.getParent();
      v = p instanceof View ? (View) p : null;
    }
    return false;
  }

  private void buildRail(LinearLayout root) {
    rail = Ui.column(this);
    rail.setClipChildren(false);
    rail.setClipToPadding(false);
    rail.setPadding(Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 18));
    root.addView(rail, new LinearLayout.LayoutParams(Ui.dp(this, RAIL_OPEN), -1));
    LinearLayout brand = Ui.row(this);
    brand.setPadding(Ui.dp(this, 14), 0, 0, 0);
    ImageView moon = new ImageView(this);
    moon.setImageDrawable(Ui.icon(this, "moon", 26, Ui.ACCENT));
    brand.addView(moon, new LinearLayout.LayoutParams(Ui.dp(this, 26), Ui.dp(this, 26)));
    brandName = Ui.heading(this, "moonshot", 22);
    brandName.setPadding(Ui.dp(this, 12), 0, 0, 0);
    brandName.setSingleLine(true);
    brand.addView(brandName);
    LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(-1, Ui.dp(this, 40));
    bp.bottomMargin = Ui.dp(this, 22);
    rail.addView(brand, bp);
    Button guide =
        Ui.navButton(
            this,
            "Program guide",
            "guide",
            () -> startActivity(new Intent(this, GuideActivity.class)));
    addRailButton(guide, "Program guide");
    addNav("Live TV", "live");
    addNav("Favorites", "favorites");
    addNav("Movies", "movie");
    addNav("Series", "series");
    addNav("Recent", "recent");
    Button profiles = Ui.navButton(this, "Profiles", "profiles", this::profiles);
    addRailButton(profiles, "Profiles");
    View spacer = new View(this);
    rail.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    Button settings = Ui.navButton(this, "Settings", "settings", this::settings);
    addRailButton(settings, "Settings");
    version = Ui.text(this, "MOONSHOT  ·  0.5", 10, Ui.FAINT);
    version.setLetterSpacing(.12f);
    version.setSingleLine(true);
    version.setPadding(Ui.dp(this, 16), Ui.dp(this, 12), 0, 0);
    rail.addView(version);
  }

  private void addRailButton(Button b, String label) {
    navLabels.put(b, label);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
    p.setMargins(0, 0, 0, Ui.dp(this, 6));
    rail.addView(b, p);
  }

  private void addNav(String label, String key) {
    Button b =
        Ui.navButton(
            this,
            label,
            key,
            () -> {
              section = key;
              group = null;
              customGroup = null;
              load();
            });
    b.setTag(key);
    addRailButton(b, label);
    nav.add(b);
  }

  /** Collapses the rail to icons while browsing, and expands it when the remote returns to it. */
  private void setRail(boolean open) {
    if (open == railOpen) return;
    railOpen = open;
    if (railAnimator != null) railAnimator.cancel();
    ViewGroup.LayoutParams lp = rail.getLayoutParams();
    int from = lp.width, to = Ui.dp(this, open ? RAIL_OPEN : RAIL_CLOSED);
    for (Map.Entry<Button, String> e : navLabels.entrySet()) {
      e.getKey().setText(open ? e.getValue() : "");
      e.getKey().setContentDescription(e.getValue());
      e.getKey()
          .setPadding(
              Ui.dp(this, open ? 16 : 21), 0, Ui.dp(this, open ? 12 : 0), 0);
    }
    brandName.setVisibility(open ? View.VISIBLE : View.GONE);
    version.setVisibility(open ? View.VISIBLE : View.INVISIBLE);
    railAnimator = ValueAnimator.ofInt(from, to);
    railAnimator.setDuration(200);
    railAnimator.setInterpolator(new DecelerateInterpolator(2f));
    railAnimator.addUpdateListener(
        a -> {
          lp.width = (int) a.getAnimatedValue();
          rail.requestLayout();
        });
    railAnimator.start();
  }

  private void buildTopBar() {
    LinearLayout top = Ui.row(this);
    top.setClipChildren(false);
    LinearLayout heading = Ui.column(this);
    title = Ui.heading(this, "Live TV", 30);
    heading.addView(title);
    subtitle = Ui.text(this, "Tune in. Settle in. Make yourself at home.", 13, Ui.MUTED);
    subtitle.setPadding(0, Ui.dp(this, 6), 0, 0);
    subtitle.setSingleLine(true);
    subtitle.setEllipsize(TextUtils.TruncateAt.END);
    heading.addView(subtitle);
    top.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));

    EditText search = Ui.input(this, "Search your library", false);
    search.setText(query);
    search.setTextSize(15);
    search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 14), 0);
    search.setCompoundDrawablePadding(Ui.dp(this, 10));
    search.setCompoundDrawables(Ui.icon(this, "search", 18, Ui.MUTED), null, null, null);
    StateListDrawableHolder.pill(this, search);
    search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(Ui.dp(this, 300), Ui.dp(this, 44));
    sp.setMargins(Ui.dp(this, 12), 0, 0, 0);
    top.addView(search, sp);
    search.addTextChangedListener(
        new TextWatcher() {
          Runnable next;

          public void beforeTextChanged(CharSequence s, int a, int c, int f) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            query = s.toString();
            if (next != null) main.removeCallbacks(next);
            next = () -> load();
            main.postDelayed(next, 250);
          }

          public void afterTextChanged(Editable e) {}
        });

    layoutToggle = Ui.button(this, "", () -> {});
    layoutToggle.setGravity(Gravity.CENTER);
    layoutToggle.setPadding(Ui.dp(this, 12), 0, 0, 0);
    updateLayoutToggle();
    layoutToggle.setOnClickListener(v -> toggleLayout());
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 44), Ui.dp(this, 44));
    lp.setMargins(Ui.dp(this, 10), 0, 0, 0);
    top.addView(layoutToggle, lp);

    LinearLayout time = Ui.column(this);
    time.setGravity(Gravity.END);
    time.setPadding(Ui.dp(this, 18), 0, Ui.dp(this, 4), 0);
    TextClock clock = new TextClock(this);
    clock.setFormat12Hour("h:mm a");
    clock.setFormat24Hour("HH:mm");
    clock.setTextColor(Ui.TEXT);
    clock.setTextSize(18);
    clock.setTypeface(
        android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
    time.addView(clock);
    TextClock date = new TextClock(this);
    date.setFormat12Hour("EEE, MMM d");
    date.setFormat24Hour("EEE, d MMM");
    date.setTextColor(Ui.MUTED);
    date.setTextSize(11);
    time.addView(date);
    top.addView(time);

    String who = Family.name(this, Family.active(this));
    TextView avatar = Ui.text(this, initials(who), 14, Ui.onAccent());
    avatar.setGravity(Gravity.CENTER);
    avatar.setTypeface(null, android.graphics.Typeface.BOLD);
    avatar.setFocusable(true);
    avatar.setClickable(true);
    android.graphics.drawable.StateListDrawable ring =
        new android.graphics.drawable.StateListDrawable();
    ring.addState(
        new int[] {android.R.attr.state_focused},
        Ui.outline(Ui.ACCENT, Ui.TEXT, Ui.dp(this, 2), Ui.dp(this, 999)));
    ring.addState(new int[] {}, Ui.shape(Ui.ACCENT, Ui.dp(this, 999)));
    avatar.setBackground(ring);
    avatar.setContentDescription("Profile: " + who);
    avatar.setOnClickListener(v -> profiles());
    avatar.setOnFocusChangeListener((v, f) -> Ui.animateFocus(v, f, 1.1f));
    LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(Ui.dp(this, 40), Ui.dp(this, 40));
    ap.setMargins(Ui.dp(this, 8), 0, 0, 0);
    top.addView(avatar, ap);
    content.addView(top);
  }

  /** Small holder so the search field gets the same pill focus treatment as chips. */
  private static final class StateListDrawableHolder {
    static void pill(Activity a, View v) {
      android.graphics.drawable.StateListDrawable d =
          new android.graphics.drawable.StateListDrawable();
      d.addState(
          new int[] {android.R.attr.state_focused},
          Ui.outline(Ui.mix(Ui.PANEL, Ui.ACCENT, .12f), Ui.ACCENT, Ui.dp(a, 2), Ui.dp(a, 999)));
      d.addState(
          new int[] {},
          Ui.outline(Ui.alpha(Ui.PANEL, 220), Ui.alpha(Color.WHITE, 20), Ui.dp(a, 1), Ui.dp(a, 999)));
      v.setBackground(d);
    }
  }

  private void updateLayoutToggle() {
    layoutToggle.setCompoundDrawables(
        Ui.icon(this, compactLibrary ? "grid" : "list", 20, Ui.TEXT), null, null, null);
    layoutToggle.setContentDescription(compactLibrary ? "Grid view" : "List view");
  }

  private void toggleLayout() {
    compactLibrary = !compactLibrary;
    prefs.edit().putBoolean("compactLibrary", compactLibrary).apply();
    updateLayoutToggle();
    grid.setLayoutManager(new GridLayoutManager(this, compactLibrary ? 1 : 4));
    displayedQuery = null;
    load();
  }

  private void buildSpotlight() {
    spotlightPanel = Ui.row(this);
    spotlightPanel.setPadding(Ui.dp(this, 26), Ui.dp(this, 18), Ui.dp(this, 22), Ui.dp(this, 18));
    heroColors = new int[] {Ui.mix(Ui.PANEL, Ui.ACCENT, .22f), Ui.alpha(Ui.PANEL, 200)};
    heroTint =
        new GradientDrawable(GradientDrawable.Orientation.TL_BR, heroColors.clone());
    heroTint.setCornerRadius(Ui.dp(this, 22));
    heroTint.setStroke(Ui.dp(this, 1), Ui.alpha(Color.WHITE, 22));
    spotlightPanel.setBackground(heroTint);
    LinearLayout info = Ui.column(this);
    spotlightPanel.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    LinearLayout tags = Ui.row(this);
    spotlightLabel = Ui.pill(this, "YOUR LIBRARY", Ui.onAccent(), Ui.ACCENT);
    tags.addView(spotlightLabel);
    spotlightGroup = Ui.text(this, "", 12, Ui.MUTED);
    spotlightGroup.setPadding(Ui.dp(this, 12), 0, 0, 0);
    spotlightGroup.setSingleLine(true);
    spotlightGroup.setEllipsize(TextUtils.TruncateAt.END);
    tags.addView(spotlightGroup, new LinearLayout.LayoutParams(0, -2, 1));
    info.addView(tags);
    spotlightName = Ui.heading(this, "Make room for better TV.", 30);
    spotlightName.setSingleLine(true);
    spotlightName.setEllipsize(TextUtils.TruncateAt.END);
    spotlightName.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 8));
    info.addView(spotlightName);
    spotlightDetail =
        Ui.text(this, "Live channels, movies and series. All in your orbit.", 14, Ui.MUTED);
    spotlightDetail.setSingleLine(true);
    spotlightDetail.setEllipsize(TextUtils.TruncateAt.END);
    info.addView(spotlightDetail);
    LinearLayout hints = Ui.row(this);
    hints.setPadding(0, Ui.dp(this, 14), 0, 0);
    hints.addView(Ui.pill(this, "OK", Ui.TEXT, Ui.alpha(Color.WHITE, 30)));
    TextView watch = Ui.text(this, "Watch", 12, Ui.MUTED);
    watch.setPadding(Ui.dp(this, 8), 0, Ui.dp(this, 18), 0);
    hints.addView(watch);
    hints.addView(Ui.pill(this, "HOLD OK", Ui.TEXT, Ui.alpha(Color.WHITE, 30)));
    TextView more = Ui.text(this, "Favorite, record, multiview", 12, Ui.MUTED);
    more.setPadding(Ui.dp(this, 8), 0, 0, 0);
    hints.addView(more);
    info.addView(hints);
    markColors = new int[] {Ui.mix(Ui.PANEL_HI, Ui.ACCENT, .35f), Ui.PANEL};
    markTint = new GradientDrawable(GradientDrawable.Orientation.TL_BR, markColors.clone());
    markTint.setCornerRadius(Ui.dp(this, 22));
    spotlightMark = Ui.heading(this, "M", 40);
    spotlightMark.setGravity(Gravity.CENTER);
    spotlightMark.setTextColor(Ui.TEXT);
    spotlightMark.setBackground(markTint);
    spotlightMark.setElevation(Ui.dp(this, 6));
    LinearLayout.LayoutParams mark =
        new LinearLayout.LayoutParams(Ui.dp(this, 112), Ui.dp(this, 112));
    mark.setMargins(Ui.dp(this, 20), 0, 0, 0);
    spotlightPanel.addView(spotlightMark, mark);
    LinearLayout.LayoutParams size = new LinearLayout.LayoutParams(-1, Ui.dp(this, 158));
    size.setMargins(0, Ui.dp(this, 18), 0, 0);
    content.addView(spotlightPanel, size);
  }

  static String initials(String name) {
    String[] words = name.trim().split("\\s+");
    StringBuilder result = new StringBuilder();
    for (String word : words) {
      if (!word.isEmpty()) result.appendCodePoint(word.codePointAt(0));
      if (result.codePointCount(0, result.length()) == 2) break;
    }
    return result.length() == 0 ? "TV" : result.toString().toUpperCase(Locale.ROOT);
  }

  private static int tone(Channel ch) {
    return TONES[(ch.name.hashCode() & Integer.MAX_VALUE) % TONES.length];
  }

  private static String kindLabel(Channel ch) {
    return ch.kind.equals("live") ? "● LIVE" : ch.kind.equals("movie") ? "MOVIE" : "SERIES";
  }

  private void spotlight(Channel channel) {
    int tone = channel == null ? Ui.ACCENT : tone(channel);
    int[] hero = {Ui.mix(Ui.PANEL, tone, .55f), Ui.alpha(Ui.PANEL, 200)};
    int[] glow = {Ui.alpha(Ui.mix(tone, Ui.ACCENT, .35f), 80), Ui.alpha(tone, 0)};
    int[] mark = {Ui.mix(tone, Color.WHITE, .12f), Ui.mix(tone, Ui.BG, .45f)};
    Ui.shiftGradient(heroTint, heroColors, hero, 320);
    Ui.shiftGradient(ambient, ambientColors, glow, 420);
    Ui.shiftGradient(markTint, markColors, mark, 320);
    heroColors = hero;
    ambientColors = glow;
    markColors = mark;
    if (channel == null) {
      spotlightLabel.setText("YOUR LIBRARY");
      spotlightLabel.setBackground(Ui.shape(Ui.ACCENT, Ui.dp(this, 999)));
      spotlightGroup.setText("");
      spotlightName.setText("Make room for better TV.");
      spotlightDetail.setText("Live channels, movies and series. All in your orbit.");
      spotlightMark.setText("M");
      return;
    }
    spotlightLabel.setText(kindLabel(channel) + (channel.favorite ? "  ♥" : ""));
    spotlightLabel.setTextColor(channel.kind.equals("live") ? Color.WHITE : Ui.onAccent());
    spotlightLabel.setBackground(
        Ui.shape(channel.kind.equals("live") ? Ui.LIVE : Ui.ACCENT, Ui.dp(this, 999)));
    spotlightGroup.setText(channel.group);
    spotlightName.setText(channel.name);
    spotlightDetail.setText(
        !channel.now.isEmpty()
            ? "Now playing  ·  " + channel.now
            : channel.position > 0
                ? "Resume where you left off"
                : channel.kind.equals("live") ? "Press OK to tune in" : "Press OK to play");
    spotlightMark.setText(initials(channel.name));
    spotlightName.setAlpha(.35f);
    spotlightName.animate().alpha(1f).setDuration(180).start();
    spotlightMark.setScaleX(.94f);
    spotlightMark.setScaleY(.94f);
    spotlightMark.animate().scaleX(1f).scaleY(1f).setDuration(220).start();
  }

  private String label() {
    switch (section) {
      case "favorites":
        return "Favorites";
      case "movie":
        return "Movies";
      case "series":
        return "Series";
      case "recent":
        return "Recently watched";
      default:
        return "Live TV";
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Data

  private String recentKey() {
    return "recent." + Family.active(this);
  }

  private void remember(Channel ch) {
    List<String> ids =
        new ArrayList<>(Arrays.asList(prefs.getString(recentKey(), "").split(",")));
    ids.removeIf(id -> id.isEmpty() || id.equals(ch.id));
    ids.add(0, ch.id);
    while (ids.size() > 30) ids.remove(ids.size() - 1);
    prefs.edit().putString(recentKey(), TextUtils.join(",", ids)).apply();
  }

  private List<Channel> recents(String search) {
    List<Channel> out = new ArrayList<>();
    for (String id : prefs.getString(recentKey(), "").split(",")) {
      if (id.isEmpty()) continue;
      try {
        Channel ch = library.channel(id);
        ch.url = "";
        ch.catchupSource = "";
        if (search.isEmpty()
            || ch.name.toLowerCase(Locale.ROOT).contains(search.toLowerCase(Locale.ROOT)))
          out.add(ch);
      } catch (Exception ignored) {
        // Removed since it was watched.
      }
    }
    return out;
  }

  private void load() {
    if (isFinishing() || library == null) return;
    int ticket = ++generation;
    String key = section, selectedGroup = group, search = query, custom = customGroup;
    String queryKey = key + "\n" + selectedGroup + "\n" + search + "\n" + custom;
    boolean fresh = !queryKey.equals(displayedQuery);
    android.os.Parcelable scrollState =
        !fresh ? grid.getLayoutManager().onSaveInstanceState() : null;
    title.setText(label());
    subtitle.setText(
        section.equals("live")
            ? "Tune in. Settle in. Make yourself at home."
            : section.equals("favorites")
                ? "The channels you always come back to."
                : section.equals("recent")
                    ? "Pick up right where you left off."
                    : "Your provider’s on-demand stream library.");
    for (Button b : nav) b.setSelected(section.equals(b.getTag()));
    boolean showChips = !section.equals("favorites") && !section.equals("recent");
    chips.setVisibility(showChips ? View.VISIBLE : View.GONE);
    if (showChips && !section.equals(chipSection)) loadChips();
    else if (chips.getAdapter() != null) chips.getAdapter().notifyItemRangeChanged(0, chips.getAdapter().getItemCount());
    worker.execute(
        () -> {
          try {
            List<Channel> data =
                key.equals("recent")
                    ? recents(search)
                    : library.channels(
                        key.equals("favorites") ? null : key,
                        selectedGroup,
                        search,
                        key.equals("favorites"),
                        custom);
            for (Channel ch : data)
              if (ch.kind.equals("live") && !ch.tvgId.isEmpty()) ch.now = library.now(ch.tvgId);
            boolean hasSources = !library.sources().isEmpty();
            main.post(
                () -> {
                  if (ticket != generation || isFinishing() || isDestroyed()) return;
                  visible = data;
                  grid.setAdapter(new Cards(data));
                  if (scrollState != null)
                    grid.getLayoutManager().onRestoreInstanceState(scrollState);
                  else if (fresh) grid.scheduleLayoutAnimation();
                  displayedQuery = queryKey;
                  String noun =
                      key.equals("live") ? "channels" : key.equals("movie") ? "movies" : "streams";
                  count.setText(
                      String.format(Locale.getDefault(), "%,d %s", data.size(), noun)
                          + (selectedGroup != null ? "  ·  " + selectedGroup : "")
                          + (custom != null ? "  ·  " + custom : "")
                          + (data.size() == 5000 ? "  ·  First 5,000; narrow your search" : ""));
                  showEmpty(data.isEmpty(), hasSources);
                  spotlight(data.isEmpty() ? null : data.get(0));
                });
          } catch (Exception e) {
            main.post(() -> toast("Could not read the local library."));
          }
        });
  }

  private static final class Chip {
    final String label, group, custom;

    Chip(String label, String group, String custom) {
      this.label = label;
      this.group = group;
      this.custom = custom;
    }
  }

  private void loadChips() {
    String kind = section;
    chipSection = kind;
    worker.execute(
        () -> {
          List<Chip> list = new ArrayList<>();
          list.add(new Chip("All", null, null));
          for (String name : library.groups(kind)) list.add(new Chip(name, name, null));
          for (String name : library.customGroups()) list.add(new Chip("★ " + name, null, name));
          main.post(
              () -> {
                if (isDestroyed() || !kind.equals(chipSection)) return;
                chips.setAdapter(new Chips(list));
              });
        });
  }

  private boolean chipSelected(Chip c) {
    return Objects.equals(c.group, group) && Objects.equals(c.custom, customGroup);
  }

  private final class Chips extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    final List<Chip> data;

    Chips(List<Chip> d) {
      data = d;
    }

    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int type) {
      TextView t = Ui.text(MainActivity.this, "", 13, Ui.MUTED);
      t.setTypeface(
          android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
      t.setTextColor(Ui.chipText());
      t.setBackground(Ui.chipBackground(MainActivity.this));
      t.setGravity(Gravity.CENTER);
      t.setSingleLine(true);
      t.setMaxWidth(Ui.dp(MainActivity.this, 260));
      t.setEllipsize(TextUtils.TruncateAt.END);
      t.setFocusable(true);
      t.setClickable(true);
      t.setPadding(
          Ui.dp(MainActivity.this, 18), 0, Ui.dp(MainActivity.this, 18), 0);
      RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-2, -1);
      lp.setMargins(0, 0, Ui.dp(MainActivity.this, 8), 0);
      t.setLayoutParams(lp);
      t.setOnFocusChangeListener((v, f) -> Ui.animateFocus(v, f, 1.06f));
      return new RecyclerView.ViewHolder(t) {};
    }

    @Override
    public void onBindViewHolder(RecyclerView.ViewHolder h, int i) {
      Chip c = data.get(i);
      TextView t = (TextView) h.itemView;
      t.setText(c.label);
      t.setSelected(chipSelected(c));
      t.setOnClickListener(
          v -> {
            group = c.group;
            customGroup = c.custom;
            load();
          });
    }

    @Override
    public int getItemCount() {
      return data.size();
    }
  }

  private void showEmpty(boolean show, boolean hasSources) {
    empty.setVisibility(show ? View.VISIBLE : View.GONE);
    grid.setVisibility(show ? View.GONE : View.VISIBLE);
    spotlightPanel.setVisibility(show && !hasSources ? View.GONE : View.VISIBLE);
    empty.removeAllViews();
    if (!show) return;
    FrameLayout badge = new FrameLayout(this);
    GradientDrawable halo = new GradientDrawable();
    halo.setGradientType(GradientDrawable.RADIAL_GRADIENT);
    halo.setGradientRadius(Ui.dp(this, 52));
    halo.setColors(new int[] {Ui.alpha(Ui.ACCENT, 90), Ui.alpha(Ui.ACCENT, 0)});
    badge.setBackground(halo);
    ImageView moon = new ImageView(this);
    moon.setImageDrawable(Ui.icon(this, hasSources ? "search" : "moon", 46, Ui.ACCENT));
    badge.addView(moon, new FrameLayout.LayoutParams(Ui.dp(this, 46), Ui.dp(this, 46), Gravity.CENTER));
    empty.addView(badge, new LinearLayout.LayoutParams(Ui.dp(this, 104), Ui.dp(this, 104)));
    TextView h =
        Ui.heading(
            this,
            hasSources
                ? section.equals("recent") ? "Nothing watched yet" : "Nothing here yet"
                : "Your next favorite starts here.",
            26);
    h.setGravity(Gravity.CENTER);
    h.setPadding(0, Ui.dp(this, 6), 0, 0);
    empty.addView(h);
    TextView t =
        Ui.text(
            this,
            hasSources
                ? section.equals("recent")
                    ? "Channels and movies you play will appear here."
                    : query.isEmpty()
                        ? "Try another group, or hold OK on a stream to add a favorite."
                        : "No matches for “" + query + "”. Try a shorter search."
                : "Add your provider's M3U link or Xtream login to get started.\n"
                    + "Your access details stay encrypted on this device.",
            14,
            Ui.MUTED);
    t.setGravity(Gravity.CENTER);
    t.setLineSpacing(0, 1.25f);
    t.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 24));
    empty.addView(t);
    if (hasSources) return;
    LinearLayout actions = Ui.row(this);
    actions.setGravity(Gravity.CENTER);
    actions.setClipChildren(false);
    actions.addView(action("link", "M3U playlist", "Paste a link", () -> importDialog(false)));
    actions.addView(action("key", "Xtream login", "Server + account", this::addXtream));
    actions.addView(action("pulse", "Test a link", "Find what's wrong", () -> testLink(null)));
    empty.addView(actions);
  }

  private View action(String icon, String label, String detail, Runnable run) {
    LinearLayout tile = Ui.column(this);
    tile.setGravity(Gravity.CENTER);
    tile.setFocusable(true);
    tile.setClickable(true);
    tile.setBackground(Ui.surface(this, Ui.PANEL, false));
    tile.setPadding(Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 16), Ui.dp(this, 14));
    ImageView glyph = new ImageView(this);
    glyph.setImageDrawable(Ui.icon(this, icon, 28, Ui.ACCENT));
    tile.addView(glyph, new LinearLayout.LayoutParams(Ui.dp(this, 28), Ui.dp(this, 28)));
    TextView name = Ui.heading(this, label, 15);
    name.setPadding(0, Ui.dp(this, 12), 0, Ui.dp(this, 4));
    tile.addView(name);
    tile.addView(Ui.text(this, detail, 12, Ui.MUTED));
    tile.setContentDescription(label);
    tile.setOnClickListener(v -> run.run());
    tile.setOnFocusChangeListener((v, f) -> Ui.animateFocus(v, f, 1.07f));
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(Ui.dp(this, 168), Ui.dp(this, 128));
    lp.setMargins(Ui.dp(this, 8), 0, Ui.dp(this, 8), 0);
    tile.setLayoutParams(lp);
    return tile;
  }

  // ---------------------------------------------------------------------------------------------
  // Cards

  private final class Cards extends RecyclerView.Adapter<Card> {
    final List<Channel> data;

    Cards(List<Channel> d) {
      data = d;
    }

    @Override
    public Card onCreateViewHolder(ViewGroup parent, int type) {
      MainActivity a = MainActivity.this;
      if (compactLibrary) {
        LinearLayout line = Ui.row(a);
        line.setFocusable(true);
        line.setClickable(true);
        line.setPadding(Ui.dp(a, 14), 0, Ui.dp(a, 18), 0);
        RecyclerView.LayoutParams rowSize = new RecyclerView.LayoutParams(-1, Ui.dp(a, 64));
        rowSize.setMargins(0, 0, 0, Ui.dp(a, 6));
        line.setLayoutParams(rowSize);
        TextView number = Ui.text(a, "", 12, Ui.FAINT);
        number.setGravity(Gravity.CENTER);
        number.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(number, new LinearLayout.LayoutParams(Ui.dp(a, 38), -2));
        TextView monogram = Ui.heading(a, "", 14);
        monogram.setGravity(Gravity.CENTER);
        monogram.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        line.addView(monogram, new LinearLayout.LayoutParams(Ui.dp(a, 42), Ui.dp(a, 42)));
        LinearLayout words = Ui.column(a);
        words.setGravity(Gravity.CENTER_VERTICAL);
        words.setPadding(Ui.dp(a, 16), 0, Ui.dp(a, 12), 0);
        TextView name = Ui.heading(a, "", 15);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        TextView detail = Ui.text(a, "", 12, Ui.MUTED);
        detail.setPadding(0, Ui.dp(a, 5), 0, 0);
        detail.setSingleLine(true);
        detail.setEllipsize(TextUtils.TruncateAt.END);
        words.addView(name);
        words.addView(detail);
        line.addView(words, new LinearLayout.LayoutParams(0, -1, 1));
        ImageView heart = new ImageView(a);
        heart.setImageDrawable(Ui.icon(a, "heart-filled", 14, Ui.LIVE));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(Ui.dp(a, 14), Ui.dp(a, 14));
        hp.setMargins(0, 0, Ui.dp(a, 12), 0);
        line.addView(heart, hp);
        TextView badge = Ui.pill(a, "", Ui.TEXT, Ui.alpha(Color.WHITE, 26));
        line.addView(badge);
        return new Card(line, badge, name, detail, monogram, heart, number, null);
      }
      FrameLayout box = new FrameLayout(a);
      box.setFocusable(true);
      box.setClickable(true);
      RecyclerView.LayoutParams lp = new RecyclerView.LayoutParams(-1, Ui.dp(a, 148));
      lp.setMargins(Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 5), Ui.dp(a, 9));
      box.setLayoutParams(lp);
      TextView watermark = Ui.heading(a, "", 64);
      watermark.setTextColor(Ui.alpha(Color.WHITE, 34));
      watermark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      watermark.setPadding(0, Ui.dp(a, 8), Ui.dp(a, 12), 0);
      box.addView(watermark, new FrameLayout.LayoutParams(-2, -2, Gravity.END | Gravity.TOP));
      LinearLayout column = Ui.column(a);
      int p = Ui.dp(a, 14);
      column.setPadding(p, p, p, Ui.dp(a, 13));
      box.addView(column, new FrameLayout.LayoutParams(-1, -1));
      LinearLayout top = Ui.row(a);
      TextView badge = Ui.pill(a, "", Color.WHITE, Ui.LIVE);
      top.addView(badge);
      View gap = new View(a);
      top.addView(gap, new LinearLayout.LayoutParams(0, 1, 1));
      ImageView heart = new ImageView(a);
      heart.setImageDrawable(Ui.icon(a, "heart-filled", 15, Ui.LIVE));
      top.addView(heart, new LinearLayout.LayoutParams(Ui.dp(a, 15), Ui.dp(a, 15)));
      column.addView(top);
      View spacer = new View(a);
      column.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
      TextView name = Ui.heading(a, "", 16);
      name.setMaxLines(1);
      name.setEllipsize(TextUtils.TruncateAt.END);
      column.addView(name);
      TextView detail = Ui.text(a, "", 12, Ui.MUTED);
      detail.setSingleLine(true);
      detail.setEllipsize(TextUtils.TruncateAt.END);
      detail.setPadding(0, Ui.dp(a, 5), 0, 0);
      column.addView(detail);
      View progress = new View(a);
      progress.setBackground(Ui.shape(Ui.ACCENT, Ui.dp(a, 2)));
      LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(Ui.dp(a, 60), Ui.dp(a, 3));
      pp.topMargin = Ui.dp(a, 8);
      column.addView(progress, pp);
      return new Card(box, badge, name, detail, watermark, heart, null, progress);
    }

    @Override
    public void onBindViewHolder(Card h, int i) {
      Channel ch = data.get(i);
      int tint = tone(ch);
      h.itemView.setBackground(Ui.surface(MainActivity.this, tint, false));
      h.monogram.setText(initials(ch.name));
      if (compactLibrary)
        h.monogram.setBackground(
            Ui.gradient(Ui.mix(tint, Color.WHITE, .1f), tint, Ui.dp(MainActivity.this, 12)));
      if (h.number != null) h.number.setText(String.valueOf(i + 1));
      h.itemView.setOnFocusChangeListener(
          (v, focused) -> {
            Ui.animateFocus(v, focused, compactLibrary ? 1.015f : 1.07f);
            h.name.setTextColor(focused ? Color.WHITE : Ui.TEXT);
            if (focused) spotlight(ch);
          });
      boolean live = ch.kind.equals("live");
      h.badge.setText(kindLabel(ch));
      if (!compactLibrary) {
        h.badge.setTextColor(live ? Color.WHITE : Ui.onAccent());
        h.badge.setBackground(Ui.shape(live ? Ui.LIVE : Ui.ACCENT, Ui.dp(MainActivity.this, 999)));
      }
      h.heart.setVisibility(ch.favorite ? View.VISIBLE : View.GONE);
      h.name.setText(ch.name);
      h.detail.setText(ch.now.isEmpty() ? ch.group : "Now · " + ch.now);
      if (h.progress != null) {
        h.progress.setVisibility(ch.position > 0 && !live ? View.VISIBLE : View.GONE);
      }
      h.itemView.setContentDescription(
          ch.name + ", " + ch.group + (ch.favorite ? ", favorite" : ""));
      h.itemView.setOnClickListener(v -> play(ch));
      h.itemView.setOnLongClickListener(
          v -> {
            options(ch);
            return true;
          });
    }

    @Override
    public int getItemCount() {
      return data.size();
    }
  }

  static final class Card extends RecyclerView.ViewHolder {
    TextView badge, name, detail, monogram, number;
    ImageView heart;
    View progress;

    Card(
        View v,
        TextView b,
        TextView n,
        TextView d,
        TextView m,
        ImageView heart,
        TextView number,
        View progress) {
      super(v);
      badge = b;
      name = n;
      detail = d;
      monogram = m;
      this.heart = heart;
      this.number = number;
      this.progress = progress;
    }
  }

  private void play(Channel ch) {
    remember(ch);
    Intent i = new Intent(this, PlaybackActivity.class);
    i.putExtra("channel", ch.id);
    startActivity(i);
  }

  private void options(Channel ch) {
    new AlertDialog.Builder(this)
        .setTitle(ch.name)
        .setItems(
            new String[] {
              "Watch",
              ch.favorite ? "Remove favorite" : "Add favorite",
              "Program guide",
              "Play from beginning",
              "Hide channel",
              "Move earlier",
              "Move later",
              "Custom group",
              "Record live channel",
              "Multiview with…"
            },
            (d, w) -> {
              if (w == 5 || w == 6) {
                List<Channel> scope = new ArrayList<>(visible);
                worker.execute(
                    () -> {
                      library.reorder(scope, ch.id, w == 5 ? -1 : 1);
                      main.post(this::load);
                    });
              }
              if (w == 9) chooseSecond(ch);
              if (w == 8) {
                if (ch.kind.equals("live")) Recorder.choose(this, ch);
                else toast("Recording is available for live channels.");
              }
              if (w == 7) assignGroup(ch);
              if (w == 0) play(ch);
              if (w == 1)
                worker.execute(
                    () -> {
                      library.favorite(ch);
                      main.post(this::load);
                    });
              if (w == 2)
                worker.execute(
                    () -> {
                      List<String> programs = library.guide(ch.tvgId);
                      main.post(
                          () ->
                              new AlertDialog.Builder(this)
                                  .setTitle(ch.name + " · Guide")
                                  .setMessage(
                                      programs.isEmpty()
                                          ? "No matching guide data. Import XMLTV in Settings;"
                                              + " tvg-id values must match the guide’s channel"
                                              + " IDs."
                                          : TextUtils.join("\n\n", programs))
                                  .setPositiveButton("Close", null)
                                  .show());
                    });
              if (w == 4)
                worker.execute(
                    () -> {
                      library.hidden(ch.id, true);
                      main.post(this::load);
                    });
              if (w == 3)
                worker.execute(
                    () -> {
                      library.position(ch.id, 0);
                      main.post(() -> play(ch));
                    });
            })
        .show();
  }

  private void chooseSecond(Channel first) {
    if (!first.kind.equals("live")) {
      toast("Choose two live channels.");
      return;
    }
    worker.execute(
        () -> {
          List<Channel> channels = library.channels("live", null, "", false);
          channels.removeIf(c -> c.id.equals(first.id));
          String[] names = new String[channels.size()];
          for (int i = 0; i < names.length; i++)
            names[i] = channels.get(i).name + " · " + channels.get(i).group;
          main.post(
              () -> {
                if (isDestroyed()) return;
                if (channels.isEmpty()) {
                  toast("Add another live channel first.");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Second channel · uses two connections")
                    .setItems(
                        names,
                        (d, w) ->
                            startActivity(
                                new Intent(this, MultiViewActivity.class)
                                    .putExtra("first", first.id)
                                    .putExtra("second", channels.get(w).id)))
                    .setNegativeButton("Cancel", null)
                    .show();
              });
        });
  }

  private void restoreHidden() {
    worker.execute(
        () -> {
          List<Channel> hidden = library.hiddenChannels();
          main.post(
              () -> {
                if (isFinishing() || isDestroyed()) return;
                if (hidden.isEmpty()) {
                  toast("No hidden channels.");
                  return;
                }
                String[] labels = new String[hidden.size()];
                for (int i = 0; i < labels.length; i++)
                  labels[i] = hidden.get(i).name + " · " + hidden.get(i).group;
                new AlertDialog.Builder(this)
                    .setTitle("Restore a hidden channel")
                    .setItems(
                        labels,
                        (d, w) ->
                            worker.execute(
                                () -> {
                                  library.hidden(hidden.get(w).id, false);
                                  main.post(this::load);
                                }))
                    .setNegativeButton("Close", null)
                    .show();
              });
        });
  }

  private void assignGroup(Channel ch) {
    worker.execute(
        () -> {
          List<String> gs = library.customGroups();
          gs.add(0, "Create group…");
          gs.add("Remove from custom group");
          main.post(
              () -> {
                if (isDestroyed()) return;
                new AlertDialog.Builder(this)
                    .setTitle("Custom group")
                    .setItems(
                        gs.toArray(new String[0]),
                        (d, w) -> {
                          if (w == 0) {
                            EditText input =
                                Ui.input(this, "Group name (up to 80 characters)", false);
                            input.setFilters(
                                new android.text.InputFilter[] {
                                  new android.text.InputFilter.LengthFilter(80)
                                });
                            new AlertDialog.Builder(this)
                                .setTitle("Create group")
                                .setView(input)
                                .setPositiveButton(
                                    "Save",
                                    (a, b) -> {
                                      String name = input.getText().toString().trim();
                                      if (name.isEmpty()) {
                                        toast("Enter a group name.");
                                        return;
                                      }
                                      worker.execute(
                                          () -> {
                                            library.customGroup(ch.id, name);
                                            main.post(this::load);
                                          });
                                    })
                                .setNegativeButton("Cancel", null)
                                .show();
                          } else
                            worker.execute(
                                () -> {
                                  library.customGroup(ch.id, w == gs.size() - 1 ? null : gs.get(w));
                                  main.post(this::load);
                                });
                        })
                    .setNegativeButton("Cancel", null)
                    .show();
              });
        });
  }

  private void browseCustom() {
    worker.execute(
        () -> {
          List<String> gs = library.customGroups();
          main.post(
              () -> {
                if (isDestroyed()) return;
                if (gs.isEmpty()) {
                  toast("Hold OK on a channel to create a custom group.");
                  return;
                }
                new AlertDialog.Builder(this)
                    .setTitle("Custom groups")
                    .setItems(
                        gs.toArray(new String[0]),
                        (d, w) -> {
                          String selected = gs.get(w);
                          new AlertDialog.Builder(this)
                              .setTitle(selected)
                              .setItems(
                                  new String[] {"Browse", "Delete group"},
                                  (a, b) -> {
                                    if (b == 0) {
                                      customGroup = selected;
                                      group = null;
                                      load();
                                    } else
                                      worker.execute(
                                          () -> {
                                            library.deleteCustomGroup(selected);
                                            if (selected.equals(customGroup)) customGroup = null;
                                            main.post(this::load);
                                          });
                                  })
                              .show();
                        })
                    .setNegativeButton("Close", null)
                    .show();
              });
        });
  }

  private void backupDialog(boolean restore) {
    LinearLayout body = Ui.dialogBody(this);
    EditText password = Ui.input(this, "Passphrase: 12 to 256 characters", true),
        confirm = Ui.input(this, "Repeat passphrase", true);
    body.addView(password);
    if (!restore) body.addView(confirm);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(restore ? "Restore this profile’s library" : "Export this profile’s library")
            .setMessage(
                restore
                    ? "Restoring replaces this profile’s channels and playlists after successful"
                        + " verification. Guide caches are cleared; other profiles and the"
                        + " parental PIN are unchanged."
                    : "Keep this passphrase somewhere safe. No recovery is available. Guide caches,"
                        + " recordings and the parental PIN are excluded. Maximum backup size: 32"
                        + " MiB. Your device needs a document picker.")
            .setView(body)
            .setPositiveButton(restore ? "Choose backup" : "Choose location", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      String value = password.getText().toString();
                      if (value.length() < 12
                          || value.length() > 256
                          || (!restore && !value.equals(confirm.getText().toString()))) {
                        password.setError("Use a matching passphrase of 12 to 256 characters");
                        return;
                      }
                      if (backupPassword != null) Arrays.fill(backupPassword, '\0');
                      backupPassword = value.toCharArray();
                      password.getText().clear();
                      confirm.getText().clear();
                      dialog.dismiss();
                      Intent intent =
                          new Intent(
                                  restore
                                      ? Intent.ACTION_OPEN_DOCUMENT
                                      : Intent.ACTION_CREATE_DOCUMENT)
                              .addCategory(Intent.CATEGORY_OPENABLE)
                              .setType("application/octet-stream");
                      if (!restore) intent.putExtra(Intent.EXTRA_TITLE, "moonshot-library.mns");
                      try {
                        startActivityForResult(intent, restore ? 802 : 801);
                      } catch (android.content.ActivityNotFoundException e) {
                        Arrays.fill(backupPassword, '\0');
                        backupPassword = null;
                        toast("No document picker is available on this device.");
                      }
                    }));
    dialog.show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 801 && request != 802) return;
    char[] password = backupPassword;
    backupPassword = null;
    if (password == null) return;
    if (result != RESULT_OK
        || data == null
        || data.getData() == null
        || !"content".equals(data.getData().getScheme())) {
      Arrays.fill(password, '\0');
      return;
    }
    android.net.Uri uri = data.getData();
    if (!Family.admin(this)) {
      Arrays.fill(password, '\0');
      toast("Authorization expired. Unlock Settings and retry.");
      return;
    }
    worker.execute(
        () -> {
          String message;
          try {
            if (request == 801) {
              try (java.io.OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new java.io.IOException();
                LibraryBackup.exportLibrary(library, out, password);
              }
              message = "Encrypted library exported.";
            } else {
              try (java.io.InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new java.io.IOException();
                LibraryBackup.importLibrary(library, in, password);
              }
              message = "Library restored. Import the XMLTV guide again.";
            }
          } catch (Exception e) {
            message =
                request == 802
                    ? "Restore failed. Check the passphrase and backup. Existing library retained."
                    : "Export failed. Remove the incomplete file and try again.";
          } finally {
            Arrays.fill(password, '\0');
          }
          String status = message;
          main.post(
              () -> {
                if (!isDestroyed()) {
                  toast(status);
                  load();
                }
              });
        });
  }

  private void profiles() {
    List<String> ids = Family.profiles(this);
    String[] names = new String[ids.size()];
    for (int i = 0; i < names.length; i++)
      names[i] =
          Family.name(this, ids.get(i))
              + (Family.restricted(this, ids.get(i))
                  ? " · family"
                  : " · protected when PIN enabled");
    new AlertDialog.Builder(this)
        .setTitle("Choose profile")
        .setItems(
            names,
            (d, w) -> {
              Family.select(this, ids.get(w));
              startActivity(
                  new Intent(this, EntryActivity.class)
                      .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void createProfile() {
    LinearLayout body = Ui.dialogBody(this);
    EditText name = Ui.input(this, "Profile name", false);
    name.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(40)});
    body.addView(name);
    CheckBox child = new CheckBox(this);
    child.setText("Family profile: open without PIN; settings require parental PIN");
    child.setTextColor(Ui.TEXT);
    child.setChecked(true);
    body.addView(child);
    new AlertDialog.Builder(this)
        .setTitle("Create separate library")
        .setView(body)
        .setPositiveButton(
            "Create",
            (d, w) -> {
              if (child.isChecked() && !Family.hasPin(this)) {
                toast("Set a parental PIN before creating a family profile.");
                return;
              }
              try {
                Family.create(this, name.getText().toString(), child.isChecked());
                toast(
                    "Profile created. Switch with Profiles, then add its permitted playlists using"
                        + " the parental PIN.");
              } catch (RuntimeException e) {
                toast("Use a name up to 40 characters; at most eight profiles.");
              }
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void configurePin() {
    LinearLayout body = Ui.dialogBody(this);
    EditText first = Ui.input(this, "New PIN: 6 to 12 digits", true),
        again = Ui.input(this, "Repeat PIN", true);
    for (EditText e : new EditText[] {first, again}) {
      e.setInputType(
          android.text.InputType.TYPE_CLASS_NUMBER
              | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
      e.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(12)});
      body.addView(e);
    }
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Set parental PIN")
            .setMessage(
                "Remember this PIN. Recovery requires clearing app data, which removes all"
                    + " libraries. This protects app access, not a compromised device.")
            .setView(body)
            .setPositiveButton("Save", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      if (!Family.admin(this)) {
                        dialog.dismiss();
                        toast("Authorization expired. Unlock Settings and retry.");
                        return;
                      }
                      String value = first.getText().toString();
                      if (!value.matches("[0-9]{6,12}")
                          || !value.equals(again.getText().toString())) {
                        first.setError("Use matching PINs of 6 to 12 digits");
                        return;
                      }
                      char[] pin = value.toCharArray();
                      first.getText().clear();
                      again.getText().clear();
                      dialog.dismiss();
                      worker.execute(
                          () -> {
                            try {
                              Family.setPin(this, pin);
                              main.post(() -> toast("Parental PIN saved."));
                            } catch (Exception e) {
                              main.post(() -> toast("Could not save PIN."));
                            } finally {
                              Arrays.fill(pin, '\0');
                            }
                          });
                    }));
    dialog.show();
  }

  private void xtreamDialog() {
    if (busy) {
      toast("An import is already running.");
      return;
    }
    LinearLayout body = Ui.dialogBody(this);
    EditText label = Ui.input(this, "Playlist name", false),
        server = Ui.input(this, "https://provider.example:443", false),
        user = Ui.input(this, "Username", true),
        password = Ui.input(this, "Password", true);
    body.addView(label);
    body.addView(server);
    body.addView(user);
    body.addView(password);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Xtream-compatible login")
            .setView(body)
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    if (!Family.admin(this)) {
                      dialog.dismiss();
                      toast("Authorization expired. Unlock Settings and retry.");
                      return;
                    }
                    String name = label.getText().toString().trim();
                    if (name.isEmpty()) {
                      label.setError("Enter a playlist name");
                      return;
                    }
                    String address;
                    try {
                      address =
                          Xtream.playlist(
                              server.getText().toString().trim(),
                              user.getText().toString(),
                              password.getText().toString());
                    } catch (IllegalArgumentException e) {
                      server.setError("Check server address and login fields");
                      return;
                    }
                    dialog.dismiss();
                    Runnable start =
                        () ->
                            runImport(
                                "Importing Xtream playlist…",
                                address,
                                () -> library.importSource(name, address, http()),
                                " streams imported");
                    if (address.startsWith("http:") && !http()) chooseHttp(start);
                    else start.run();
                  });
        });
    dialog.show();
  }

  // ---------------------------------------------------------------------------------------------
  // Settings

  private void settings() {
    requireAdmin(this::showSettings);
  }

  private void addXtream() {
    requireAdmin(this::xtreamDialog);
  }

  /** Runs a settings action only while parental authorization is still valid. */
  private Runnable admin(Sheet sheet, Runnable action) {
    return () -> {
      if (!Family.admin(this)) {
        sheet.dismiss();
        toast("Authorization expired. Unlock Settings and try again.");
        return;
      }
      action.run();
    };
  }

  /** Closes the sheet, then opens the next screen or dialog. */
  private Runnable go(Sheet sheet, Runnable action) {
    return admin(
        sheet,
        () -> {
          sheet.dismiss();
          action.run();
        });
  }

  private boolean http() {
    return prefs.getBoolean("allowHttp", false);
  }

  private void showSettings() {
    Sheet sheet =
        new Sheet(
            this,
            "Settings",
            "Moonshot",
            "Profile: " + Family.name(this, Family.active(this)) + "  ·  Version 0.5");
    sheet.section("Playlists");
    sheet.row(
        "link",
        "Add M3U playlist",
        () -> "Paste the playlist link from your provider",
        go(sheet, () -> importDialog(false)));
    sheet.row(
        "key",
        "Add Xtream login",
        () -> "Server address, username and password",
        go(sheet, this::xtreamDialog));
    sheet.row(
        "pulse",
        "Test a playlist link",
        () -> "Step-by-step check of a link that won't load",
        go(sheet, () -> testLink(null)));
    sheet.row(
        "refresh",
        "Manage & refresh playlists",
        () -> "Refresh, rename, test or remove",
        go(sheet, this::manage));
    sheet.row(
        "guide",
        "Import XMLTV guide",
        () -> "Program guide for your channels",
        go(sheet, () -> importDialog(true)));
    sheet.row(
        "folder",
        "Xtream catalog",
        () -> "Browse categories, movie details and seasons",
        go(sheet, () -> startActivity(new Intent(this, CatalogActivity.class))));

    sheet.section("Connection");
    sheet.row(
        "lock",
        "Unencrypted HTTP",
        () -> http() ? "Allowed for providers that need it" : "Blocked · HTTPS only",
        admin(sheet, () -> chooseHttp(null)));
    sheet.row(
        "globe",
        "Secure DNS",
        () ->
            new String[] {
                  "Automatic · used when your network blocks a provider",
                  "Always · encrypted lookups for every provider",
                  "Off · use only the network's DNS"
                }[
                Network.dnsMode()],
        admin(sheet, this::chooseDns));
    sheet.row(
        "shield",
        "Player identity",
        this::agentLabel,
        admin(sheet, this::chooseAgent));
    sheet.row(
        "people",
        "Provider connection limit",
        () -> {
          int n = prefs.getInt("connections", 1);
          return n + (n == 1 ? " connection" : " connections") + " at a time";
        },
        admin(sheet, this::chooseConnections));

    sheet.section("Library");
    sheet.row(
        "refresh",
        "Automatic refresh",
        () ->
            prefs.getBoolean("automaticRefresh", false)
                ? "On · about every six hours"
                : "Off",
        admin(
            sheet,
            () -> {
              boolean enabled = !prefs.getBoolean("automaticRefresh", false);
              prefs.edit().putBoolean("automaticRefresh", enabled).apply();
              RefreshService.schedule(this, enabled);
            }));
    sheet.row(
        "eye", "Restore hidden channels", null, go(sheet, this::restoreHidden));
    sheet.row("folder", "Browse custom groups", null, go(sheet, this::browseCustom));
    sheet.row(
        "sort",
        "Reset channel order",
        () -> "Return to the provider's order",
        admin(
            sheet,
            () ->
                worker.execute(
                    () -> {
                      library.resetOrder();
                      main.post(
                          () -> {
                            load();
                            toast("Channel order reset.");
                          });
                    })));
    sheet.row(
        "image",
        "Provider artwork",
        () -> prefs.getBoolean("artwork", false) ? "On" : "Off · no extra image downloads",
        admin(sheet, this::chooseArtwork));
    sheet.row(
        "record",
        "Recordings",
        () -> "Scheduled, completed and partial recordings",
        go(sheet, () -> startActivity(new Intent(this, RecordingsActivity.class))));

    sheet.section("Appearance");
    sheet.row(
        "palette",
        "Accent color",
        () -> Ui.ACCENT_NAMES[Ui.accentIndex],
        admin(sheet, () -> chooseAccent(sheet)));
    sheet.row(
        compactLibrary ? "list" : "grid",
        "Library layout",
        () -> compactLibrary ? "List" : "Grid",
        this::toggleLayout);

    sheet.section("Profiles & security");
    sheet.row(
        "lock",
        "Parental PIN",
        () -> Family.hasPin(this) ? "Set · change it here" : "Not set",
        go(sheet, this::configurePin));
    sheet.row("profiles", "Create profile", null, go(sheet, this::createProfile));
    sheet.row(
        "upload", "Export encrypted library", null, go(sheet, () -> backupDialog(false)));
    sheet.row(
        "download", "Restore encrypted library", null, go(sheet, () -> backupDialog(true)));

    sheet.section("About");
    sheet.row("info", "Privacy & about", () -> "Moonshot 0.5", this::about);
    sheet.show();
  }

  private String agentLabel() {
    String agent = Network.userAgent();
    for (int i = 0; i < Network.AGENTS.length; i++)
      if (Network.AGENTS[i].equals(agent)) return Network.AGENT_LABELS[i];
    return "Custom: " + agent;
  }

  private void saveNetwork(String agent, int dns) {
    prefs.edit().putString("userAgent", agent).putInt("secureDns", dns).apply();
    Network.configure(agent, dns);
  }

  private void chooseHttp(Runnable then) {
    boolean allowed = http();
    new AlertDialog.Builder(this)
        .setTitle(allowed ? "Block unencrypted HTTP?" : "Allow unencrypted HTTP?")
        .setMessage(
            "Many IPTV providers only serve playlists and streams over plain HTTP. HTTP can"
                + " expose your playlist link and viewing activity to your network. HTTPS"
                + " connections stay verified either way.")
        .setPositiveButton(
            allowed ? "Block HTTP" : then == null ? "Allow HTTP" : "Allow HTTP and continue",
            (a, b) -> {
              if (!Family.admin(this)) {
                toast("Authorization expired. Unlock Settings and retry.");
                return;
              }
              prefs.edit().putBoolean("allowHttp", !allowed).apply();
              toast(allowed ? "HTTP blocked. Only HTTPS is used." : "HTTP allowed.");
              if (then != null && !allowed) then.run();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void chooseDns() {
    new AlertDialog.Builder(this)
        .setTitle("Secure DNS")
        .setSingleChoiceItems(
            new String[] {
              "Automatic (recommended): use encrypted DNS when the network can't find a provider",
              "Always: encrypted DNS for every provider",
              "Off: only the network's DNS"
            },
            Network.dnsMode(),
            (d, w) -> {
              if (!Family.admin(this)) {
                toast("Authorization expired. Unlock Settings and retry.");
                return;
              }
              saveNetwork(Network.userAgent(), w);
              d.dismiss();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void chooseAgent() {
    String[] labels = Arrays.copyOf(Network.AGENT_LABELS, Network.AGENT_LABELS.length + 1);
    labels[labels.length - 1] = "Custom…";
    int current = labels.length - 1;
    for (int i = 0; i < Network.AGENTS.length; i++)
      if (Network.AGENTS[i].equals(Network.userAgent())) current = i;
    new AlertDialog.Builder(this)
        .setTitle("Player identity")
        .setSingleChoiceItems(
            labels,
            current,
            (d, w) -> {
              d.dismiss();
              if (!Family.admin(this)) {
                toast("Authorization expired. Unlock Settings and retry.");
                return;
              }
              if (w < Network.AGENTS.length) {
                saveNetwork(Network.AGENTS[w], Network.dnsMode());
                toast("Player identity: " + Network.AGENT_LABELS[w]);
                return;
              }
              EditText input = Ui.input(this, "User-Agent text", false);
              input.setText(Network.userAgent());
              input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(200)});
              LinearLayout body = Ui.dialogBody(this);
              body.addView(input);
              new AlertDialog.Builder(this)
                  .setTitle("Custom player identity")
                  .setMessage(
                      "Some providers require a specific User-Agent. Use the value your provider"
                          + " gives you.")
                  .setView(body)
                  .setPositiveButton(
                      "Save",
                      (a, b) -> {
                        String value = input.getText().toString().trim();
                        if (value.isEmpty() || !value.matches("[\\x20-\\x7E]+")) {
                          toast("Use plain letters, numbers and punctuation.");
                          return;
                        }
                        if (Family.admin(this)) saveNetwork(value, Network.dnsMode());
                      })
                  .setNegativeButton("Cancel", null)
                  .show();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void chooseConnections() {
    new AlertDialog.Builder(this)
        .setTitle("Your subscription’s connection limit")
        .setSingleChoiceItems(
            new String[] {"1", "2", "3", "4"},
            prefs.getInt("connections", 1) - 1,
            (choice, index) -> {
              if (!Family.admin(this)) {
                toast("Authorization expired. Unlock Settings and retry.");
                return;
              }
              prefs.edit().putInt("connections", index + 1).apply();
              choice.dismiss();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void chooseArtwork() {
    new AlertDialog.Builder(this)
        .setTitle("Provider artwork")
        .setMessage(
            "Artwork may contact image hosts supplied by your provider and use extra bandwidth."
                + " It is off by default.")
        .setPositiveButton(
            prefs.getBoolean("artwork", false) ? "Disable" : "Enable",
            (a, b) -> {
              if (Family.admin(this))
                prefs.edit().putBoolean("artwork", !prefs.getBoolean("artwork", false)).apply();
            })
        .setNegativeButton("Cancel", null)
        .show();
  }

  private void chooseAccent(Sheet sheet) {
    new AlertDialog.Builder(this)
        .setTitle("Accent color")
        .setSingleChoiceItems(
            Ui.ACCENT_NAMES,
            Ui.accentIndex,
            (d, w) -> {
              d.dismiss();
              if (w == Ui.accentIndex) return;
              prefs.edit().putInt("accent", w).apply();
              Ui.configure(this);
              sheet.dismiss();
              recreate();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void about() {
    new AlertDialog.Builder(this)
        .setTitle("Moonshot · 0.5")
        .setMessage(
            "A private, independent IPTV player. No content is bundled. Use playlists you are"
                + " authorized to access.\n\n"
                + "No analytics, ads or cloud account. Provider artwork is off by default;"
                + " enabling it contacts image hosts supplied by the provider. Secure DNS, when"
                + " used, sends provider host names (never addresses or credentials) to"
                + " Cloudflare or Google DNS. Playlist, stream and archive addresses are"
                + " encrypted with Android Keystore. Automatic system backups are disabled;"
                + " explicit library exports use your passphrase. Channel metadata and recorded"
                + " video are local.\n\n"
                + "Profiles have separate libraries. A parental PIN protects settings and"
                + " protected profiles; it is not protection against a compromised device."
                + " Recording, catch-up and two-channel multiview depend on provider, format and"
                + " device capabilities. DRM is not implemented.\n\n"
                + "Clear app storage in device settings to remove the entire local library.")
        .setPositiveButton("Close", null)
        .show();
  }

  // ---------------------------------------------------------------------------------------------
  // Import

  private void importDialog(boolean epg) {
    requireAdmin(() -> showImportDialog(epg, null));
  }

  private void showImportDialog(boolean epg, String prefill) {
    if (busy) {
      toast("An import is already running.");
      return;
    }
    LinearLayout body = Ui.dialogBody(this);
    EditText name = Ui.input(this, "Playlist name", false),
        url =
            Ui.address(
                this,
                epg
                    ? "https://provider.example/guide.xml"
                    : "https://provider.example/playlist.m3u");
    if (prefill != null) url.setText(prefill);
    if (!epg) body.addView(name);
    body.addView(url);
    TextView note =
        Ui.text(
            this,
            epg
                ? "XMLTV channel IDs must match your playlist’s tvg-id values. Plain XML or HTTP"
                    + " gzip encoding is supported."
                : "Paste your provider’s complete M3U link. It is stored encrypted and never"
                    + " logged. If it won't load, Moonshot will tell you why and offer a fix.",
            12,
            Ui.MUTED);
    note.setLineSpacing(0, 1.2f);
    note.setPadding(0, Ui.dp(this, 10), 0, 0);
    body.addView(note);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(epg ? "Import program guide" : "Add your playlist")
            .setView(body)
            .setPositiveButton("Import", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    if (!Family.admin(this)) {
                      dialog.dismiss();
                      toast("Authorization expired. Unlock Settings and retry.");
                      return;
                    }
                    String address = url.getText().toString().trim();
                    String label = name.getText().toString().trim();
                    if (!M3uParser.isWebUrl(address)) {
                      url.setError("Enter a complete link starting with https:// or http://");
                      return;
                    }
                    if (!epg && label.isEmpty()) label = suggestName(address);
                    String finalLabel = label;
                    dialog.dismiss();
                    Runnable start =
                        () ->
                            runImport(
                                epg ? "Importing guide…" : "Importing playlist…",
                                address,
                                () ->
                                    epg
                                        ? XmlTv.importGuide(library, address, http())
                                        : library.importSource(finalLabel, address, http()),
                                epg ? " guide programs imported" : " streams imported");
                    if (address.regionMatches(true, 0, "http:", 0, 5) && !http())
                      chooseHttp(start);
                    else start.run();
                  });
        });
    dialog.show();
  }

  /** A readable default name, e.g. "con.me", when the person leaves the name blank. */
  static String suggestName(String address) {
    try {
      String host = java.net.URI.create(address).getHost();
      if (host == null) return "My playlist";
      return host.startsWith("www.") ? host.substring(4) : host;
    } catch (IllegalArgumentException e) {
      return "My playlist";
    }
  }

  interface ImportJob {
    int run() throws Exception;
  }

  private void runImport(String message, String address, ImportJob job, String success) {
    if (busy) return;
    busy = true;
    ProgressDialog progress = new ProgressDialog(this);
    progress.setMessage(message);
    progress.setIndeterminate(true);
    progress.setCancelable(false);
    progress.show();
    worker.execute(
        () -> {
          Exception failure = null;
          int result = 0;
          try {
            result = job.run();
          } catch (Exception e) {
            failure = e;
          }
          Exception finalFailure = failure;
          int finalResult = result;
          int insecure = library.lastInsecure;
          main.post(
              () -> {
                busy = false;
                if (isDestroyed()) return;
                progress.dismiss();
                load();
                if (finalFailure == null) {
                  toast(String.format(Locale.getDefault(), "%,d", finalResult) + success);
                  if (insecure > 0 && !http() && success.contains("stream"))
                    new AlertDialog.Builder(this)
                        .setTitle("Allow HTTP so these streams can play?")
                        .setMessage(
                            String.format(
                                Locale.getDefault(),
                                "%,d of the imported streams use unencrypted HTTP. They will not"
                                    + " play while HTTP is blocked.",
                                insecure))
                        .setPositiveButton(
                            "Allow HTTP",
                            (a, b) -> {
                              if (Family.admin(this)) {
                                prefs.edit().putBoolean("allowHttp", true).apply();
                                toast("HTTP allowed.");
                              }
                            })
                        .setNegativeButton("Not now", null)
                        .show();
                } else importFailed(finalFailure, message, address, job, success);
              });
        });
  }

  /** Explains a failed import and offers the most likely one-step fix. */
  private void importFailed(
      Exception e, String message, String address, ImportJob job, String success) {
    String reason = Network.safeError(e);
    ProviderException provider = e instanceof ProviderException ? (ProviderException) e : null;
    AlertDialog.Builder b =
        new AlertDialog.Builder(this).setTitle("Couldn't import that").setMessage(reason);
    Runnable retry = () -> runImport(message, address, job, success);
    if (provider != null && provider.reason == ProviderException.Reason.NEEDS_HTTP)
      b.setPositiveButton(
          "Allow HTTP and retry",
          (d, w) -> {
            if (!Family.admin(this)) {
              toast("Authorization expired. Unlock Settings and retry.");
              return;
            }
            prefs.edit().putBoolean("allowHttp", true).apply();
            retry.run();
          });
    else if (e instanceof java.net.UnknownHostException
        && Network.dnsMode() != Network.DNS_ALWAYS)
      b.setPositiveButton(
          "Use Secure DNS and retry",
          (d, w) -> {
            if (!Family.admin(this)) {
              toast("Authorization expired. Unlock Settings and retry.");
              return;
            }
            saveNetwork(Network.userAgent(), Network.DNS_ALWAYS);
            retry.run();
          });
    else b.setPositiveButton("Try again", (d, w) -> retry.run());
    if (address != null)
      b.setNeutralButton("Test this link", (d, w) -> requireAdmin(() -> runProbe(address)));
    b.setNegativeButton("Close", null).show();
  }

  // ---------------------------------------------------------------------------------------------
  // Link tester

  private void testLink(String prefill) {
    requireAdmin(
        () -> {
          LinearLayout body = Ui.dialogBody(this);
          EditText url = Ui.address(this, "https://provider.example/playlist.m3u");
          if (prefill != null) url.setText(prefill);
          body.addView(url);
          TextView note =
              Ui.text(
                  this,
                  "Moonshot checks the server lookup, each redirect, the response format, the"
                      + " playlist and one sample stream. Only host names are shown; the link"
                      + " itself is never displayed or logged.",
                  12,
                  Ui.MUTED);
          note.setLineSpacing(0, 1.2f);
          note.setPadding(0, Ui.dp(this, 10), 0, 0);
          body.addView(note);
          AlertDialog dialog =
              new AlertDialog.Builder(this)
                  .setTitle("Test a playlist link")
                  .setView(body)
                  .setPositiveButton("Run test", null)
                  .setNegativeButton("Cancel", null)
                  .create();
          dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
          dialog.setOnShowListener(
              d ->
                  dialog
                      .getButton(AlertDialog.BUTTON_POSITIVE)
                      .setOnClickListener(
                          v -> {
                            String address = url.getText().toString().trim();
                            if (address.isEmpty()) {
                              url.setError("Paste the playlist link");
                              return;
                            }
                            dialog.dismiss();
                            runProbe(address);
                          }));
          dialog.show();
        });
  }

  private void runProbe(String address) {
    Sheet sheet =
        new Sheet(this, "Link test", "Checking your playlist…", PlaylistProbe.hostOf(address));
    ProgressBar spinner = new ProgressBar(this);
    spinner.setIndeterminate(true);
    sheet.list.addView(spinner, new LinearLayout.LayoutParams(-2, Ui.dp(this, 64)));
    sheet.note(
        "This can take up to a minute for large playlists. Moonshot uses one provider connection"
            + " briefly to check a sample stream.");
    sheet.show();
    boolean allowHttp = http();
    Thread probe =
        new Thread(
            () -> {
              PlaylistProbe.Result result = PlaylistProbe.run(address, allowHttp);
              main.post(
                  () -> {
                    if (isDestroyed() || !sheet.dialog.isShowing()) return;
                    showProbe(sheet, address, result);
                  });
            },
            "moonshot-link-test");
    probe.start();
    sheet.dialog.setOnDismissListener(d -> probe.interrupt());
  }

  private void showProbe(Sheet sheet, String address, PlaylistProbe.Result r) {
    sheet.clear();
    TextView verdict = Ui.heading(this, r.verdict, 17);
    verdict.setTextColor(r.ok && r.fix == PlaylistProbe.Fix.NONE ? Ui.GOOD : r.ok ? Ui.WARN : Ui.TEXT);
    verdict.setLineSpacing(0, 1.2f);
    verdict.setPadding(Ui.dp(this, 6), 0, 0, Ui.dp(this, 10));
    sheet.list.addView(verdict);
    for (PlaylistProbe.Step s : r.steps) sheet.line(s.state, s.title, s.detail);
    switch (r.fix) {
      case ALLOW_HTTP:
        sheet.button(
            "Allow HTTP" + (r.ok ? " and add playlist" : " and test again"),
            true,
            () -> {
              if (!Family.admin(this)) return;
              prefs.edit().putBoolean("allowHttp", true).apply();
              sheet.dismiss();
              if (r.ok) showImportDialog(false, address);
              else runProbe(address);
            });
        break;
      case SECURE_DNS:
        sheet.button(
            "Turn on Secure DNS and test again",
            true,
            () -> {
              if (!Family.admin(this)) return;
              saveNetwork(Network.userAgent(), Network.DNS_ALWAYS);
              sheet.dismiss();
              runProbe(address);
            });
        break;
      case AGENT:
        sheet.button(
            "Use “" + Network.AGENT_LABELS[r.agent] + "” and add playlist",
            true,
            () -> {
              if (!Family.admin(this)) return;
              saveNetwork(Network.AGENTS[r.agent], Network.dnsMode());
              sheet.dismiss();
              showImportDialog(false, address);
            });
        break;
      case XTREAM:
        sheet.button(
            "Add Xtream login",
            true,
            () -> {
              sheet.dismiss();
              xtreamDialog();
            });
        break;
      default:
        if (r.ok)
          sheet.button(
              "Add this playlist",
              true,
              () -> {
                sheet.dismiss();
                showImportDialog(false, address);
              });
    }
    sheet.button("Test again", false, () -> {
      sheet.dismiss();
      runProbe(address);
    });
    sheet.button("Close", false, sheet::dismiss);
    if (!r.ok)
      sheet.note(
          "Apollo-style services may only serve playlists to their own apps. If every check"
              + " passes except the provider's answer, ask your provider for an M3U or Xtream"
              + " login that is meant for third-party players.");
    sheet.focusFirst();
  }

  private void editSource(Library.Source source) {
    LinearLayout body = Ui.dialogBody(this);
    EditText name = Ui.input(this, "Name", false), url = Ui.address(this, "Playlist address");
    name.setText(source.name);
    url.setText(source.url);
    body.addView(name);
    body.addView(url);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle("Edit playlist")
            .setView(body)
            .setPositiveButton("Save and refresh", null)
            .setNegativeButton("Cancel", null)
            .create();
    dialog.getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    dialog.setOnShowListener(
        d ->
            dialog
                .getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(
                    v -> {
                      if (!Family.admin(this)) {
                        dialog.dismiss();
                        toast("Authorization expired. Unlock Settings and retry.");
                        return;
                      }
                      String label = name.getText().toString().trim(),
                          address = url.getText().toString().trim();
                      if (label.isEmpty() || label.length() > 512) {
                        name.setError("Enter a name up to 512 characters");
                        return;
                      }
                      if (!M3uParser.isWebUrl(address)) {
                        url.setError("Enter a complete HTTP or HTTPS address");
                        return;
                      }
                      dialog.dismiss();
                      runImport(
                          "Updating playlist…",
                          address,
                          () ->
                              library.replaceSource(
                                  source.id, label, address, prefs.getBoolean("allowHttp", false)),
                          " streams updated");
                    }));
    dialog.show();
  }

  private void manage() {
    worker.execute(
        () -> {
          try {
            List<Library.Source> sources = library.sources();
            main.post(
                () -> {
                  if (sources.isEmpty()) {
                    toast("Add a playlist first.");
                    return;
                  }
                  String[] names = new String[sources.size()];
                  for (int j = 0; j < names.length; j++) names[j] = sources.get(j).name;
                  new AlertDialog.Builder(this)
                      .setTitle("Your playlists")
                      .setItems(
                          names,
                          (d, w) -> {
                            Library.Source s = sources.get(w);
                            new AlertDialog.Builder(this)
                                .setTitle(s.name)
                                .setItems(
                                    new String[] {
                                      "Refresh playlist",
                                      "Remove playlist",
                                      "Edit name or address",
                                      "Test connection"
                                    },
                                    (a, b) -> {
                                      if (b == 3) {
                                        requireAdmin(() -> runProbe(s.url));
                                        return;
                                      }
                                      if (b == 2) {
                                        editSource(s);
                                        return;
                                      }
                                      if (b == 0)
                                        runImport(
                                            "Refreshing playlist…",
                                            s.url,
                                            () ->
                                                library.importSource(
                                                    s.name,
                                                    s.url,
                                                    prefs.getBoolean("allowHttp", false)),
                                            " streams refreshed");
                                      else
                                        new AlertDialog.Builder(this)
                                            .setTitle("Remove " + s.name + "?")
                                            .setMessage(
                                                "This removes its channels, favorites and resume"
                                                    + " positions from this device.")
                                            .setPositiveButton(
                                                "Remove",
                                                (x, y) ->
                                                    worker.execute(
                                                        () -> {
                                                          library.removeSource(s.id);
                                                          main.post(this::load);
                                                        }))
                                            .setNegativeButton("Cancel", null)
                                            .show();
                                    })
                                .show();
                          })
                      .setNegativeButton("Close", null)
                      .show();
                });
          } catch (Exception e) {
            main.post(() -> toast("Could not open saved playlists."));
          }
        });
  }

  private void toast(String text) {
    if (!isDestroyed()) Ui.notice(this, text);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    main.removeCallbacksAndMessages(null);
    if (backupPassword != null) Arrays.fill(backupPassword, '\0');
    worker.shutdownNow();
    if (library != null) workerCleanup();
  }

  private void workerCleanup() {
    new Thread(
            () -> {
              try {
                worker.awaitTermination(35, TimeUnit.SECONDS);
              } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
              }
              library.close();
            },
            "moonshot-close")
        .start();
  }
}
