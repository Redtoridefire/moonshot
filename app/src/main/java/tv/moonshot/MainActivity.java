package tv.moonshot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends FamilyActivity {
  private Library library;
  private char[] backupPassword;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private RecyclerView grid;
  private TextView title, subtitle, count;
  private LinearLayout content, empty;
  private Button groups;
  private String customGroup = null;
  private String displayedQuery;
  private String section = "live", group = null, query = "";
  private int generation = 0;
  private boolean busy = false;
  private final List<Button> nav = new ArrayList<>();
  private List<Channel> visible = new ArrayList<>();
  private android.content.SharedPreferences prefs;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    prefs = getSharedPreferences("preferences", MODE_PRIVATE);
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

  private void build() {
    LinearLayout root = Ui.row(this);
    root.setGravity(Gravity.TOP);
    root.setBackgroundColor(Ui.BG);
    int pad = Ui.dp(this, 24);
    root.setPadding(pad, pad, pad, pad);
    setContentView(root);
    LinearLayout side = Ui.column(this);
    side.setPadding(0, 0, Ui.dp(this, 20), 0);
    root.addView(side, new LinearLayout.LayoutParams(Ui.dp(this, 177), -1));
    TextView brand = Ui.text(this, "◕  moonshot", 23, Ui.MINT);
    brand.setTypeface(null, android.graphics.Typeface.BOLD);
    side.addView(brand);
    TextView tagline = Ui.text(this, "YOUR TV. YOUR ORBIT.", 9, Ui.MUTED);
    tagline.setLetterSpacing(.13f);
    LinearLayout.LayoutParams tagp = new LinearLayout.LayoutParams(-1, -2);
    tagp.setMargins(0, Ui.dp(this, 5), 0, Ui.dp(this, 30));
    side.addView(tagline, tagp);
    side.addView(
        Ui.button(
            this, "Program guide", () -> startActivity(new Intent(this, GuideActivity.class))),
        new LinearLayout.LayoutParams(-1, Ui.dp(this, 42)));
    addNav(side, "Live TV", "live");
    addNav(side, "Favorites", "favorites");
    addNav(side, "Movies", "movie");
    addNav(side, "Series", "series");
    side.addView(
        Ui.button(this, "Profiles", this::profiles),
        new LinearLayout.LayoutParams(-1, Ui.dp(this, 38)));
    View spacer = new View(this);
    side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    Button settings = Ui.button(this, "Settings", this::settings);
    side.addView(settings, new LinearLayout.LayoutParams(-1, Ui.dp(this, 44)));
    TextView version = Ui.text(this, "MOONSHOT  /  0.3", 10, Ui.MUTED);
    version.setPadding(Ui.dp(this, 12), Ui.dp(this, 15), 0, 0);
    side.addView(version);
    content = Ui.column(this);
    root.addView(content, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout top = Ui.row(this);
    title = Ui.text(this, "Live TV", 28, Ui.TEXT);
    title.setTypeface(null, android.graphics.Typeface.BOLD);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    android.widget.TextClock clock = new android.widget.TextClock(this);
    clock.setFormat12Hour("h:mm a");
    clock.setFormat24Hour("HH:mm");
    clock.setTextColor(Ui.MUTED);
    clock.setTextSize(15);
    top.addView(clock);
    content.addView(top);
    subtitle = Ui.text(this, "A better place to watch.", 13, Ui.MUTED);
    subtitle.setPadding(0, Ui.dp(this, 6), 0, Ui.dp(this, 18));
    content.addView(subtitle);
    LinearLayout tools = Ui.row(this);
    groups = Ui.button(this, "All groups ▾", this::chooseGroup);
    tools.addView(groups, new LinearLayout.LayoutParams(Ui.dp(this, 190), Ui.dp(this, 44)));
    EditText search = Ui.input(this, "Search your library", false);
    search.setText(query);
    search.setPadding(Ui.dp(this, 16), 0, Ui.dp(this, 12), 0);
    search.setBackground(Ui.focus(this));
    LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, Ui.dp(this, 44), 1);
    sp.setMargins(Ui.dp(this, 10), 0, 0, 0);
    tools.addView(search, sp);
    content.addView(tools);
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
    count = Ui.text(this, "Loading library…", 11, Ui.MUTED);
    count.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 10));
    content.addView(count);
    FrameLayout area = new FrameLayout(this);
    content.addView(area, new LinearLayout.LayoutParams(-1, 0, 1));
    grid = new RecyclerView(this);
    grid.setLayoutManager(new GridLayoutManager(this, 3));
    grid.setClipToPadding(false);
    grid.setPadding(0, 0, Ui.dp(this, 4), Ui.dp(this, 8));
    grid.setItemAnimator(null);
    area.addView(grid, new FrameLayout.LayoutParams(-1, -1));
    empty = Ui.column(this);
    empty.setGravity(Gravity.CENTER);
    empty.setPadding(Ui.dp(this, 30), Ui.dp(this, 20), Ui.dp(this, 30), Ui.dp(this, 20));
    area.addView(empty, new FrameLayout.LayoutParams(-1, -1));
    for (Button b : nav) if (section.equals(b.getTag())) b.requestFocus();
  }

  private void addNav(LinearLayout side, String label, String key) {
    Button b =
        Ui.button(
            this,
            label,
            () -> {
              section = key;
              group = null;
              customGroup = null;
              load();
            });
    b.setTag(key);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Ui.dp(this, 46));
    p.setMargins(0, 0, 0, Ui.dp(this, 8));
    side.addView(b, p);
    nav.add(b);
  }

  private String label() {
    switch (section) {
      case "favorites":
        return "Favorites";
      case "movie":
        return "Movies";
      case "series":
        return "Series";
      default:
        return "Live TV";
    }
  }

  private void load() {
    if (isFinishing() || library == null) return;
    int ticket = ++generation;
    String key = section, selectedGroup = group, search = query, custom = customGroup;
    String queryKey = key + "\n" + selectedGroup + "\n" + search + "\n" + custom;
    android.os.Parcelable scrollState =
        queryKey.equals(displayedQuery) ? grid.getLayoutManager().onSaveInstanceState() : null;
    title.setText(label());
    subtitle.setText(
        section.equals("live")
            ? "Tune in. Settle in. Make yourself at home."
            : section.equals("favorites")
                ? "The channels you always come back to."
                : "Your provider’s on-demand stream library.");
    groups.setText(
        customGroup != null
            ? "Custom: " + customGroup + " ▾"
            : group == null ? "All groups ▾" : group + " ▾");
    for (Button b : nav) b.setSelected(section.equals(b.getTag()));
    worker.execute(
        () -> {
          try {
            List<Channel> data =
                library.channels(
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
                  displayedQuery = queryKey;
                  count.setText(
                      data.size()
                          + " streams  •  Hold OK for favorites & guide"
                          + (data.size() == 5000 ? "  •  First 5,000; narrow your search" : ""));
                  showEmpty(data.isEmpty(), hasSources);
                });
          } catch (Exception e) {
            main.post(() -> toast("Could not read the local library."));
          }
        });
  }

  private void showEmpty(boolean show, boolean hasSources) {
    empty.setVisibility(show ? View.VISIBLE : View.GONE);
    grid.setVisibility(show ? View.GONE : View.VISIBLE);
    empty.removeAllViews();
    if (!show) return;
    TextView h =
        Ui.text(
            this, hasSources ? "Nothing here yet" : "Your next favorite starts here.", 24, Ui.TEXT);
    h.setGravity(Gravity.CENTER);
    empty.addView(h);
    TextView t =
        Ui.text(
            this,
            hasSources
                ? "Try another group or search. Hold OK on a stream to add a favorite."
                : "Add an M3U playlist from your provider.\n"
                    + "Your access details stay encrypted on this device.",
            14,
            Ui.MUTED);
    t.setGravity(Gravity.CENTER);
    t.setPadding(0, Ui.dp(this, 14), 0, Ui.dp(this, 22));
    empty.addView(t);
    if (!hasSources) empty.addView(Ui.button(this, "+  Add playlist", () -> importDialog(false)));
  }

  private void chooseGroup() {
    String kind = section;
    worker.execute(
        () -> {
          List<String> provider = library.groups(kind),
              custom = library.customGroups(),
              labels = new ArrayList<>();
          labels.add("All groups");
          labels.addAll(provider);
          for (String name : custom) labels.add("Custom: " + name);
          main.post(
              () -> {
                if (isDestroyed() || isFinishing()) return;
                new AlertDialog.Builder(this)
                    .setTitle("Channel groups")
                    .setItems(
                        labels.toArray(new String[0]),
                        (d, w) -> {
                          if (w > provider.size()) {
                            customGroup = custom.get(w - provider.size() - 1);
                            group = null;
                          } else {
                            customGroup = null;
                            group = w == 0 ? null : provider.get(w - 1);
                          }
                          load();
                        })
                    .setNegativeButton("Close", null)
                    .show();
              });
        });
  }

  private final class Cards extends RecyclerView.Adapter<Card> {
    final List<Channel> data;

    Cards(List<Channel> d) {
      data = d;
    }

    @Override
    public Card onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout box = Ui.column(MainActivity.this);
      box.setFocusable(true);
      box.setClickable(true);
      box.setBackground(Ui.focus(MainActivity.this));
      int p = Ui.dp(MainActivity.this, 14);
      box.setPadding(p, p, p, p);
      RecyclerView.LayoutParams lp =
          new RecyclerView.LayoutParams(-1, Ui.dp(MainActivity.this, 122));
      lp.setMargins(0, 0, Ui.dp(MainActivity.this, 10), Ui.dp(MainActivity.this, 10));
      box.setLayoutParams(lp);
      TextView badge = Ui.text(MainActivity.this, "", 10, Ui.MINT);
      badge.setLetterSpacing(.10f);
      box.addView(badge);
      TextView name = Ui.text(MainActivity.this, "", 16, Ui.TEXT);
      name.setTypeface(null, android.graphics.Typeface.BOLD);
      name.setMaxLines(2);
      name.setEllipsize(TextUtils.TruncateAt.END);
      LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(-1, 0, 1);
      np.setMargins(0, Ui.dp(MainActivity.this, 8), 0, Ui.dp(MainActivity.this, 5));
      box.addView(name, np);
      TextView detail = Ui.text(MainActivity.this, "", 11, Ui.MUTED);
      detail.setSingleLine(true);
      detail.setEllipsize(TextUtils.TruncateAt.END);
      box.addView(detail);
      return new Card(box, badge, name, detail);
    }

    @Override
    public void onBindViewHolder(Card h, int i) {
      Channel ch = data.get(i);
      h.badge.setText(
          (ch.favorite ? "★  " : "")
              + (ch.kind.equals("live") ? "LIVE" : ch.kind.equals("movie") ? "MOVIE" : "SERIES"));
      h.name.setText(ch.name);
      h.detail.setText(ch.now.isEmpty() ? ch.group : ch.now);
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
    TextView badge, name, detail;

    Card(View v, TextView b, TextView n, TextView d) {
      super(v);
      badge = b;
      name = n;
      detail = d;
    }
  }

  private void play(Channel ch) {
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
                    if (address.startsWith("http:") && !prefs.getBoolean("allowHttp", false)) {
                      server.setError("Enable HTTP in Settings only if your provider requires it");
                      return;
                    }
                    dialog.dismiss();
                    runImport(
                        "Importing Xtream playlist…",
                        () ->
                            library.importSource(
                                name, address, prefs.getBoolean("allowHttp", false)),
                        " streams imported");
                  });
        });
    dialog.show();
  }

  private void settings() {
    requireAdmin(this::showSettings);
  }

  private void showSettings() {
    boolean http = prefs.getBoolean("allowHttp", false);
    new AlertDialog.Builder(this)
        .setTitle("Settings")
        .setItems(
            new String[] {
              "Add M3U playlist",
              "Manage & refresh playlists",
              "Import XMLTV guide",
              "Unencrypted HTTP: " + (http ? "allowed" : "blocked"),
              "Privacy & about",
              "Restore hidden channels",
              "Browse custom groups",
              "Reset channel order",
              "Automatic refresh: " + (prefs.getBoolean("automaticRefresh", false) ? "on" : "off"),
              "Add Xtream-compatible login",
              "Parental PIN",
              "Create profile",
              "Export encrypted library",
              "Restore encrypted library",
              "Recordings",
              "Provider connection limit",
              "Xtream catalog",
              "Provider artwork: " + (prefs.getBoolean("artwork", false) ? "on" : "off")
            },
            (d, w) -> {
              if (!Family.admin(this)) {
                toast("Authorization expired. Unlock Settings and try again.");
                return;
              }
              if (w == 17)
                new AlertDialog.Builder(this)
                    .setTitle("Provider artwork")
                    .setMessage(
                        "Artwork may contact image hosts supplied by your provider and use extra"
                            + " bandwidth. It is off by default.")
                    .setPositiveButton(
                        prefs.getBoolean("artwork", false) ? "Disable" : "Enable",
                        (a, b) -> {
                          if (Family.admin(this))
                            prefs
                                .edit()
                                .putBoolean("artwork", !prefs.getBoolean("artwork", false))
                                .apply();
                        })
                    .setNegativeButton("Cancel", null)
                    .show();
              if (w == 16) startActivity(new Intent(this, CatalogActivity.class));
              if (w == 15)
                new AlertDialog.Builder(this)
                    .setTitle("Your subscription’s connection limit")
                    .setMessage(
                        "Set the limit actually permitted by your provider. Playback, recording and"
                            + " multiview share this budget; other apps/devices cannot be counted."
                            + " Default: one.")
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
              if (w == 14) startActivity(new Intent(this, RecordingsActivity.class));
              if (w == 12) backupDialog(false);
              if (w == 13) backupDialog(true);
              if (w == 10) configurePin();
              if (w == 11) createProfile();
              if (w == 9) xtreamDialog();
              if (w == 8) {
                boolean enabled = !prefs.getBoolean("automaticRefresh", false);
                prefs.edit().putBoolean("automaticRefresh", enabled).apply();
                RefreshService.schedule(this, enabled);
                toast(
                    enabled
                        ? "Refresh scheduled about every six hours when the device permits."
                        : "Automatic refresh disabled.");
              }
              if (w == 6) browseCustom();
              if (w == 7)
                worker.execute(
                    () -> {
                      library.resetOrder();
                      main.post(this::load);
                    });
              if (w == 5) restoreHidden();
              if (w == 0) importDialog(false);
              if (w == 1) manage();
              if (w == 2) importDialog(true);
              if (w == 3)
                new AlertDialog.Builder(this)
                    .setTitle("Allow unencrypted HTTP?")
                    .setMessage(
                        "Some providers require HTTP streams. HTTP can expose access credentials"
                            + " and viewing activity to your network. HTTPS stays verified. Enable"
                            + " only if your provider requires it.")
                    .setPositiveButton(
                        http ? "Block HTTP" : "Allow HTTP",
                        (a, b) -> {
                          if (!Family.admin(this)) {
                            toast("Authorization expired. Unlock Settings and retry.");
                            return;
                          }
                          prefs.edit().putBoolean("allowHttp", !http).apply();
                          toast(http ? "HTTP blocked" : "HTTP allowed");
                        })
                    .setNegativeButton("Cancel", null)
                    .show();
              if (w == 4)
                new AlertDialog.Builder(this)
                    .setTitle("Moonshot · 0.3.0")
                    .setMessage(
                        "A private, independent IPTV player. No content is bundled. Use playlists"
                            + " you are authorized to access.\n\n"
                            + "No analytics, ads or cloud account. Provider artwork is off by"
                            + " default; enabling it contacts image hosts supplied by the provider."
                            + " Playlist, stream and archive addresses are encrypted with Android"
                            + " Keystore. Automatic system backups are disabled; explicit library"
                            + " exports use your passphrase. Channel metadata and recorded video"
                            + " are local.\n\n"
                            + "Profiles have separate libraries. A parental PIN protects settings"
                            + " and protected profiles; it is not protection against a compromised"
                            + " device. Recording, catch-up and two-channel multiview depend on"
                            + " provider, format and device capabilities. DRM is not"
                            + " implemented.\n\n"
                            + "Clear app storage in device settings to remove the entire local"
                            + " library.")
                    .setPositiveButton("Close", null)
                    .show();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  private void importDialog(boolean epg) {
    requireAdmin(() -> showImportDialog(epg));
  }

  private void showImportDialog(boolean epg) {
    if (busy) {
      toast("An import is already running.");
      return;
    }
    LinearLayout body = Ui.dialogBody(this);
    EditText name = Ui.input(this, "Playlist name", false),
        url = Ui.input(this, "https://provider.example/playlist.m3u", true);
    if (!epg) body.addView(name);
    body.addView(url);
    TextView note =
        Ui.text(
            this,
            epg
                ? "XMLTV channel IDs must match your playlist’s tvg-id values. Plain XML or HTTP"
                    + " gzip encoding is supported."
                : "Paste your provider’s complete M3U address. It is never included in the APK or"
                    + " logs.",
            12,
            Ui.MUTED);
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
                    String address = url.getText().toString().trim(),
                        label = name.getText().toString().trim();
                    if (!M3uParser.isWebUrl(address)) {
                      url.setError("Enter a complete HTTP or HTTPS URL");
                      return;
                    }
                    if (!prefs.getBoolean("allowHttp", false) && address.startsWith("http:")) {
                      url.setError("Enable HTTP in Settings if your provider requires it");
                      return;
                    }
                    if (!epg && label.isEmpty()) {
                      name.setError("Name this playlist");
                      return;
                    }
                    dialog.dismiss();
                    runImport(
                        epg ? "Importing guide…" : "Importing playlist…",
                        () ->
                            epg
                                ? XmlTv.importGuide(
                                    library, address, prefs.getBoolean("allowHttp", false))
                                : library.importSource(
                                    label, address, prefs.getBoolean("allowHttp", false)),
                        epg ? " guide programs imported" : " streams imported");
                  });
        });
    dialog.show();
  }

  interface ImportJob {
    int run() throws Exception;
  }

  private void runImport(String message, ImportJob job, String success) {
    if (busy) return;
    busy = true;
    ProgressDialog progress = new ProgressDialog(this);
    progress.setMessage(message);
    progress.setIndeterminate(true);
    progress.setCancelable(false);
    progress.show();
    worker.execute(
        () -> {
          String result;
          try {
            result = job.run() + success;
          } catch (Exception e) {
            result = Network.safeError(e);
          }
          String finalResult = result;
          main.post(
              () -> {
                busy = false;
                if (isDestroyed()) return;
                progress.dismiss();
                toast(finalResult);
                load();
              });
        });
  }

  private void editSource(Library.Source source) {
    LinearLayout body = Ui.dialogBody(this);
    EditText name = Ui.input(this, "Name", false), url = Ui.input(this, "Playlist address", true);
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
                                      "Refresh playlist", "Remove playlist", "Edit name or address"
                                    },
                                    (a, b) -> {
                                      if (b == 2) {
                                        editSource(s);
                                        return;
                                      }
                                      if (b == 0)
                                        runImport(
                                            "Refreshing playlist…",
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
    if (!isDestroyed()) Toast.makeText(this, text, Toast.LENGTH_LONG).show();
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
