package tv.moonshot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;

public final class CatalogActivity extends FamilyActivity {
  final ExecutorService artwork = Executors.newSingleThreadExecutor();
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  XtreamCatalog.Account account;
  TextView title;
  ListView list;
  EditText search;
  List<XtreamCatalog.Entry> shown = new ArrayList<>();
  String kind = "live", category = "Catalog";
  boolean categories = true, episodes;
  List<XtreamCatalog.Entry> allEpisodes = new ArrayList<>(), seriesEntries = new ArrayList<>();
  Button seasons;
  volatile int ticket;

  @Override
  boolean adminOnly() {
    return true;
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    LinearLayout root = Ui.dialogBody(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    LinearLayout top = Ui.row(this);
    top.addView(Ui.button(this, "Back", this::back));
    title = Ui.text(this, "Xtream catalog", 22, Ui.TEXT);
    top.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
    seasons = Ui.button(this, "Seasons", this::chooseSeason);
    top.addView(seasons);
    root.addView(top);
    LinearLayout types = Ui.row(this);
    for (String type : new String[] {"live", "movie", "series"})
      types.addView(
          Ui.button(
              this,
              type.equals("live") ? "Live TV" : type.equals("movie") ? "Movies" : "Series",
              () -> {
                kind = type;
                loadCategories();
              }),
          new LinearLayout.LayoutParams(0, -2, 1));
    root.addView(types);
    search = Ui.input(this, "Filter this category", false);
    root.addView(search);
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void afterTextChanged(android.text.Editable e) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            display();
          }
        });
    list = new ListView(this);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, 1));
    chooseAccount();
  }

  void chooseAccount() {
    load(
        () -> {
          List<XtreamCatalog.Account> accounts = new ArrayList<>();
          try (Library library = ((MoonshotApplication) getApplication()).openLibrary()) {
            for (Library.Source source : library.sources())
              try {
                accounts.add(XtreamCatalog.account(source));
              } catch (Exception ignored) {
              }
          }
          return accounts;
        },
        accounts -> {
          if (accounts.isEmpty()) {
            new AlertDialog.Builder(this)
                .setMessage(
                    "Add an Xtream-compatible login in Settings first. M3U sources remain available"
                        + " in the main library.")
                .setPositiveButton("Close", (d, w) -> finish())
                .show();
            return;
          }
          String[] names = new String[accounts.size()];
          for (int i = 0; i < names.length; i++) names[i] = accounts.get(i).name;
          new AlertDialog.Builder(this)
              .setTitle("Choose provider account")
              .setItems(
                  names,
                  (d, w) -> {
                    account = accounts.get(w);
                    loadCategories();
                  })
              .setOnCancelListener(d -> finish())
              .show();
        });
  }

  boolean http() {
    return getSharedPreferences("preferences", 0).getBoolean("allowHttp", false);
  }

  String categoryAction() {
    return kind.equals("live")
        ? "get_live_categories"
        : kind.equals("movie") ? "get_vod_categories" : "get_series_categories";
  }

  String streamAction() {
    return kind.equals("live")
        ? "get_live_streams"
        : kind.equals("movie") ? "get_vod_streams" : "get_series";
  }

  void loadCategories() {
    if (account == null) return;
    categories = true;
    episodes = false;
    String action = categoryAction();
    load(
        () -> XtreamCatalog.fetchList(account, action, null, null, http()),
        data -> {
          shown = data;
          title.setText(account.name + " · " + kind + " categories");
          search.getText().clear();
          display();
        });
  }

  void display() {
    if (list == null) return;
    String query = search.getText().toString().toLowerCase(Locale.ROOT);
    List<XtreamCatalog.Entry> filtered = new ArrayList<>();
    List<String> names = new ArrayList<>();
    for (XtreamCatalog.Entry entry : shown)
      if (entry.name.toLowerCase(Locale.ROOT).contains(query)) {
        filtered.add(entry);
        names.add((episodes ? "S" + entry.season + " E" + entry.episode + " · " : "") + entry.name);
      }
    seasons.setEnabled(episodes);
    list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, names));
    list.setOnItemClickListener((parent, v, index, id) -> select(filtered.get(index)));
  }

  void select(XtreamCatalog.Entry entry) {
    if (categories) {
      String action = streamAction();
      category = entry.name;
      categories = false;
      episodes = false;
      load(
          () -> XtreamCatalog.fetchList(account, action, "category_id", entry.id, http()),
          data -> {
            shown = data;
            title.setText(category);
            search.getText().clear();
            display();
          });
    } else if (kind.equals("series") && !episodes) {
      seriesEntries = new ArrayList<>(shown);
      load(
          () -> XtreamCatalog.episodes(account, entry.id, http()),
          data -> {
            episodes = true;
            allEpisodes = data;
            shown = data;
            title.setText(entry.name + " · episodes");
            search.getText().clear();
            display();
          });
    } else if (kind.equals("movie"))
      load(
          () -> {
            try {
              return XtreamCatalog.movieInfo(account, entry, http());
            } catch (Exception e) {
              return entry;
            }
          },
          this::details);
    else details(entry);
  }

  void details(XtreamCatalog.Entry entry) {
    String selectedKind = kind, selectedCategory = category;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(entry.name)
            .setMessage(
                (entry.year.isEmpty() ? "" : entry.year + "\n")
                    + (entry.genre.isEmpty() ? "" : entry.genre + "\n\n")
                    + (entry.plot.isEmpty()
                        ? "Provider stream. Artwork is not downloaded."
                        : entry.plot))
            .setPositiveButton(
                "Watch",
                (d, w) ->
                    load(
                        () -> {
                          try (Library library =
                              ((MoonshotApplication) getApplication()).openLibrary()) {
                            return library.catalogStream(
                                account, entry, selectedKind, selectedCategory);
                          }
                        },
                        id ->
                            startActivity(
                                new Intent(this, PlaybackActivity.class).putExtra("channel", id))))
            .setNegativeButton("Close", null)
            .create();
    if (getSharedPreferences("preferences", 0).getBoolean("artwork", false)
        && !entry.image.isEmpty()) {
      ImageView image = new ImageView(this);
      image.setAdjustViewBounds(true);
      image.setMaxHeight(Ui.dp(this, 180));
      dialog.setView(image);
      Future<?> request =
          artwork.submit(
              () -> {
                try {
                  android.graphics.Bitmap bitmap = ProviderArtwork.load(entry.image, http());
                  main.post(
                      () -> {
                        if (!isDestroyed() && dialog.isShowing()) image.setImageBitmap(bitmap);
                      });
                } catch (Exception ignored) {
                }
              });
      dialog.setOnDismissListener(
          d -> {
            request.cancel(true);
            image.setImageDrawable(null);
          });
    }
    dialog.show();
  }

  interface Work<T> {
    T run() throws Exception;
  }

  <T> void load(Work<T> work, java.util.function.Consumer<T> result) {
    int request = ++ticket;
    if (list != null) list.setEnabled(false);
    title.setText("Loading provider catalog…");
    worker.execute(
        () -> {
          try {
            T data = work.run();
            main.post(
                () -> {
                  if (!isDestroyed() && request == ticket) {
                    list.setEnabled(true);
                    result.accept(data);
                  }
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (isDestroyed() || request != ticket) return;
                  shown = Collections.emptyList();
                  display();
                  list.setEnabled(true);
                  title.setText("Provider catalog unavailable");
                  new AlertDialog.Builder(this)
                      .setMessage(
                          "Check provider access, the account, and HTTP settings. Some providers do"
                              + " not expose these APIs. Oversized categories and unsupported"
                              + " metadata are rejected.")
                      .setPositiveButton("Close", null)
                      .show();
                });
          }
        });
  }

  void chooseSeason() {
    if (!episodes) return;
    TreeSet<Integer> available = new TreeSet<>();
    for (XtreamCatalog.Entry episode : allEpisodes) available.add(episode.season);
    List<Integer> values = new ArrayList<>(available);
    String[] names = new String[values.size() + 1];
    names[0] = "All seasons";
    for (int i = 0; i < values.size(); i++) names[i + 1] = "Season " + values.get(i);
    new AlertDialog.Builder(this)
        .setTitle("Choose season")
        .setItems(
            names,
            (d, w) -> {
              shown = new ArrayList<>();
              for (XtreamCatalog.Entry episode : allEpisodes)
                if (w == 0 || episode.season == values.get(w - 1)) shown.add(episode);
              search.getText().clear();
              display();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  void back() {
    if (episodes) {
      ticket++;
      episodes = false;
      shown = seriesEntries;
      title.setText(category);
      list.setEnabled(true);
      search.getText().clear();
      display();
    } else if (account != null && !categories) loadCategories();
    else finish();
  }

  @Override
  public void onBackPressed() {
    back();
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    artwork.shutdownNow();
    super.onDestroy();
  }
}
