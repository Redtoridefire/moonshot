package tv.moonshot;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;

/** A bounded time window keeps the guide usable with thousands of channels. */
public final class GuideActivity extends FamilyActivity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  Library library;
  RecyclerView rows;
  TextView heading;
  long start;
  int ticket;
  String query = "";
  static final long WINDOW = 2 * 60 * 60 * 1000L;

  static class Program {
    long start, end;
    String title, description;
  }

  static class Row {
    Channel channel;
    List<Program> programs = new ArrayList<>();
  }

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    start = System.currentTimeMillis() / 1800000 * 1800000;
    if (state != null) {
      start = state.getLong("start", start);
      query = state.getString("query", "");
    }
    try {
      library = ((MoonshotApplication) getApplication()).openLibrary();
    } catch (Exception e) {
      finish();
      return;
    }
    LinearLayout root = Ui.column(this);
    root.setBackgroundColor(Ui.BG);
    int p = Ui.dp(this, 24);
    root.setPadding(p, p, p, p);
    setContentView(root);
    LinearLayout bar = Ui.row(this);
    bar.addView(Ui.button(this, "Back", this::finish));
    heading = Ui.text(this, "Program guide", 20, Ui.TEXT);
    bar.addView(heading, new LinearLayout.LayoutParams(0, -2, 1));
    bar.addView(
        Ui.button(
            this,
            "Earlier",
            () -> {
              start -= WINDOW;
              load();
            }));
    bar.addView(
        Ui.button(
            this,
            "Now",
            () -> {
              start = System.currentTimeMillis() / 1800000 * 1800000;
              load();
            }));
    bar.addView(
        Ui.button(
            this,
            "Later",
            () -> {
              start += WINDOW;
              load();
            }));
    bar.addView(Ui.button(this, "Time zone", this::zone));
    root.addView(bar);
    EditText search = Ui.input(this, "Search guide channels", false);
    search.setText(query);
    root.addView(search);
    search.addTextChangedListener(
        new android.text.TextWatcher() {
          Runnable pending;

          public void beforeTextChanged(CharSequence s, int st, int c, int a) {}

          public void afterTextChanged(android.text.Editable e) {}

          public void onTextChanged(CharSequence s, int st, int before, int c) {
            query = s.toString();
            if (pending != null) main.removeCallbacks(pending);
            pending = () -> load();
            main.postDelayed(pending, 300);
          }
        });
    TextView help =
        Ui.text(
            this,
            "Select a program for details. Times use the selected display zone. Refresh XMLTV in"
                + " Settings.",
            12,
            Ui.MUTED);
    root.addView(help);
    rows = new RecyclerView(this);
    rows.setLayoutManager(new LinearLayoutManager(this));
    rows.setItemAnimator(null);
    root.addView(rows, new LinearLayout.LayoutParams(-1, 0, 1));
    load();
  }

  java.text.DateFormat formatter() {
    java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("EEE HH:mm", Locale.getDefault());
    String zone =
        getSharedPreferences("preferences", 0)
            .getString("guideZone", TimeZone.getDefault().getID());
    f.setTimeZone(TimeZone.getTimeZone(zone));
    return f;
  }

  void zone() {
    List<String> zones = new ArrayList<>(Arrays.asList(TimeZone.getAvailableIDs()));
    zones.add(0, TimeZone.getDefault().getID());
    new AlertDialog.Builder(this)
        .setTitle("Guide display time zone")
        .setItems(
            zones.toArray(new String[0]),
            (d, w) -> {
              getSharedPreferences("preferences", 0)
                  .edit()
                  .putString("guideZone", zones.get(w))
                  .apply();
              load();
            })
        .setNegativeButton("Close", null)
        .show();
  }

  void load() {
    int request = ++ticket;
    long from = start, to = start + WINDOW;
    String search = query;
    heading.setText(
        "  " + formatter().format(new Date(from)) + " – " + formatter().format(new Date(to)));
    worker.execute(
        () -> {
          try {
            Map<String, List<Program>> guide = new HashMap<>();
            try (Cursor c =
                library
                    .getReadableDatabase()
                    .rawQuery(
                        "SELECT channel,start,stop,title,description FROM programs WHERE start<?"
                            + " AND stop>? ORDER BY start",
                        new String[] {Long.toString(to), Long.toString(from)})) {
              while (c.moveToNext()) {
                Program program = new Program();
                program.start = c.getLong(1);
                program.end = c.getLong(2);
                program.title = c.getString(3);
                program.description = c.getString(4);
                guide.computeIfAbsent(c.getString(0), k -> new ArrayList<>()).add(program);
              }
            }
            List<Row> data = new ArrayList<>();
            for (Channel channel : library.channels("live", null, search, false)) {
              Row row = new Row();
              row.channel = channel;
              row.programs = guide.getOrDefault(channel.tvgId, Collections.emptyList());
              data.add(row);
            }
            main.post(
                () -> {
                  if (!isDestroyed() && ticket == request)
                    rows.setAdapter(new GuideRows(data, from, to));
                });
          } catch (Exception e) {
            main.post(
                () -> {
                  if (!isDestroyed())
                    Toast.makeText(this, "Could not load guide.", Toast.LENGTH_LONG).show();
                });
          }
        });
  }

  final class GuideRows extends RecyclerView.Adapter<Holder> {
    final List<Row> data;
    final long from, to;

    GuideRows(List<Row> d, long f, long t) {
      data = d;
      from = f;
      to = t;
    }

    @Override
    public Holder onCreateViewHolder(ViewGroup parent, int type) {
      LinearLayout row = Ui.row(GuideActivity.this);
      row.setPadding(0, Ui.dp(GuideActivity.this, 5), 0, Ui.dp(GuideActivity.this, 5));
      row.setLayoutParams(new RecyclerView.LayoutParams(-1, Ui.dp(GuideActivity.this, 86)));
      return new Holder(row);
    }

    @Override
    public void onBindViewHolder(Holder holder, int position) {
      LinearLayout view = (LinearLayout) holder.itemView;
      view.removeAllViews();
      Row row = data.get(position);
      view.addView(
          Ui.button(GuideActivity.this, row.channel.name, () -> watch(row.channel)),
          new LinearLayout.LayoutParams(Ui.dp(GuideActivity.this, 160), -1));
      LinearLayout cells = Ui.row(GuideActivity.this);
      view.addView(cells, new LinearLayout.LayoutParams(0, -1, 1));
      long cursor = from;
      for (Program program : row.programs) {
        long left = Math.max(cursor, Math.max(from, program.start)),
            right = Math.min(to, program.end);
        if (right <= left) continue;
        if (left > cursor) gap(cells, left - cursor);
        Button cell =
            Ui.button(
                GuideActivity.this,
                formatter().format(new Date(program.start)) + "\n" + program.title,
                () -> details(row.channel, program));
        cell.setMaxLines(3);
        cell.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cells.addView(cell, new LinearLayout.LayoutParams(0, -1, (float) (right - left) / WINDOW));
        cursor = right;
      }
      if (cursor < to) {
        if (row.programs.isEmpty())
          cells.addView(
              Ui.button(GuideActivity.this, "No guide data · Watch live", () -> watch(row.channel)),
              new LinearLayout.LayoutParams(0, -1, 1));
        else gap(cells, to - cursor);
      }
    }

    void gap(LinearLayout parent, long duration) {
      View gap = new View(GuideActivity.this);
      parent.addView(gap, new LinearLayout.LayoutParams(0, -1, (float) duration / WINDOW));
    }

    @Override
    public int getItemCount() {
      return data.size();
    }
  }

  static class Holder extends RecyclerView.ViewHolder {
    Holder(View v) {
      super(v);
    }
  }

  void watch(Channel channel) {
    startActivity(new Intent(this, PlaybackActivity.class).putExtra("channel", channel.id));
  }

  void details(Channel channel, Program program) {
    worker.execute(
        () -> {
          boolean available = false;
          try {
            available =
                Catchup.address(
                        library.channel(channel.id),
                        program.start,
                        program.end,
                        System.currentTimeMillis())
                    != null;
          } catch (Exception ignored) {
          }
          boolean catchup = available;
          main.post(
              () -> {
                if (isDestroyed()) return;
                AlertDialog.Builder dialog =
                    new AlertDialog.Builder(this)
                        .setTitle(program.title)
                        .setMessage(
                            formatter().format(new Date(program.start))
                                + " – "
                                + formatter().format(new Date(program.end))
                                + "\n\n"
                                + program.description)
                        .setPositiveButton("Watch live", (d, w) -> watch(channel))
                        .setNegativeButton("Close", null);
                if (catchup)
                  dialog.setNeutralButton(
                      "Watch catch-up",
                      (d, w) ->
                          startActivity(
                              new Intent(this, PlaybackActivity.class)
                                  .putExtra("channel", channel.id)
                                  .putExtra("archiveStart", program.start)
                                  .putExtra("archiveEnd", program.end)));
                if (program.end > System.currentTimeMillis())
                  dialog.setNeutralButton(
                      "Record program",
                      (d, w) ->
                          Recorder.schedule(
                              this,
                              channel,
                              Math.max(program.start, System.currentTimeMillis()),
                              program.end));
                dialog.show();
              });
        });
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putLong("start", start);
    out.putString("query", query);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    main.removeCallbacksAndMessages(null);
    worker.shutdownNow();
    new Thread(
            () -> {
              try {
                worker.awaitTermination(35, TimeUnit.SECONDS);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              if (library != null) library.close();
            })
        .start();
  }
}
