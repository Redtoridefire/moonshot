package tv.moonshot;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

public final class RecordingsActivity extends FamilyActivity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final Handler main = new Handler(Looper.getMainLooper());
  ListView list;
  TextView note;
  boolean foreground;
  String pendingExport;
  final Runnable refresh =
      new Runnable() {
        public void run() {
          if (!foreground) return;
          load();
          main.postDelayed(this, 2000);
        }
      };

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    LinearLayout root = Ui.dialogBody(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    root.addView(Ui.text(this, "Recordings", 28, Ui.TEXT));
    root.addView(Ui.button(this, "Back", this::finish));
    note =
        Ui.text(
            this, "Private storage · 2 GiB total quota · one recording at a time", 13, Ui.MUTED);
    root.addView(note);
    list = new ListView(this);
    root.addView(list, new LinearLayout.LayoutParams(-1, 0, android.widget.Toast.LENGTH_LONG));
  }

  @Override
  protected void onResume() {
    super.onResume();
    foreground = true;
    main.post(refresh);
  }

  @Override
  protected void onPause() {
    foreground = false;
    main.removeCallbacks(refresh);
    super.onPause();
  }

  void load() {
    String profile = Family.active(this);
    worker.execute(
        () -> {
          try (RecordingStore store = new RecordingStore(this)) {
            List<RecordingStore.Item> items = store.items(profile);
            for (RecordingStore.Item item : items)
              if (item.state.equals("recording") && RecordingService.activeId == null) {
                String file = item.file;
                try {
                  file = Recorder.recover(this, item.id);
                } catch (IOException ignored) {
                }
                store.state(item.id, "interrupted", file, item.bytes);
              }
            List<String> labels = new ArrayList<>();
            java.text.DateFormat date =
                java.text.DateFormat.getDateTimeInstance(
                    java.text.DateFormat.SHORT, java.text.DateFormat.SHORT);
            for (RecordingStore.Item item : items)
              labels.add(
                  item.name + "  ·  " + item.state + "  ·  " + date.format(new Date(item.start)));
            main.post(
                () -> {
                  if (isDestroyed()) return;
                  note.setText(
                      items.isEmpty()
                          ? "No recordings yet. Hold OK on a live channel to record, or schedule"
                              + " from the guide."
                          : "Private storage · 2 GiB total quota · one recording at a time");
                  android.os.Parcelable scroll = list.onSaveInstanceState();
                  list.setAdapter(
                      new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels));
                  list.onRestoreInstanceState(scroll);
                  list.setOnItemClickListener((parent, v, index, id) -> options(items.get(index)));
                });
          }
        });
  }

  void options(RecordingStore.Item item) {
    new AlertDialog.Builder(this)
        .setTitle(item.name + " · " + item.state)
        .setItems(
            new String[] {"Play", "Stop / cancel", "Export to chosen folder", "Delete"},
            (dialog, choice) -> {
              if (choice == 0) {
                if (item.file.isEmpty()) {
                  Toast.makeText(this, "No playable capture is available.", Toast.LENGTH_LONG)
                      .show();
                  return;
                }
                startActivity(
                    new Intent(this, RecordingPlaybackActivity.class).putExtra("id", item.id));
              }
              if (choice == 1)
                requireAdmin(
                    () -> {
                      ((AlarmManager) getSystemService(ALARM_SERVICE))
                          .cancel(Recorder.alarm(this, item.id));
                      worker.execute(
                          () -> {
                            try (RecordingStore store = new RecordingStore(this)) {
                              store.cancelScheduled(item.id);
                            }
                            main.post(
                                () -> {
                                  if (item.id.equals(RecordingService.activeId))
                                    startService(
                                        new Intent(this, RecordingService.class)
                                            .setAction("stop")
                                            .putExtra("id", item.id));
                                  load();
                                });
                          });
                    });
              if (choice == 2)
                requireAdmin(
                    () -> {
                      if (item.id.equals(RecordingService.activeId) || item.file.isEmpty()) {
                        Toast.makeText(
                                this,
                                "Finish or stop the recording before export.",
                                Toast.LENGTH_LONG)
                            .show();
                        return;
                      }
                      new AlertDialog.Builder(this)
                          .setTitle("Export recording files")
                          .setMessage(
                              "Choose an archive folder. These video files will be visible outside"
                                  + " Moonshot. HLS exports contain a standard playlist and its"
                                  + " media files; use a player that can read the folder. Captures"
                                  + " are initially saved in private device storage. Keep Moonshot"
                                  + " open until export finishes; interruption may leave an"
                                  + " incomplete archive folder.")
                          .setPositiveButton(
                              "Choose folder",
                              (d, w) -> {
                                pendingExport = item.id;
                                try {
                                  startActivityForResult(
                                      new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), 804);
                                } catch (ActivityNotFoundException e) {
                                  pendingExport = null;
                                  Toast.makeText(
                                          this,
                                          "No folder picker is available on this device.",
                                          Toast.LENGTH_LONG)
                                      .show();
                                }
                              })
                          .setNegativeButton("Cancel", null)
                          .show();
                    });
              if (choice == 3)
                requireAdmin(
                    () -> {
                      if (item.id.equals(RecordingService.activeId)) {
                        Toast.makeText(this, "Stop recording before deletion.", Toast.LENGTH_LONG)
                            .show();
                        return;
                      }
                      new AlertDialog.Builder(this)
                          .setTitle("Delete recording?")
                          .setPositiveButton(
                              "Delete",
                              (d, w) ->
                                  worker.execute(
                                      () -> {
                                        try (RecordingStore store = new RecordingStore(this)) {
                                          ((AlarmManager) getSystemService(ALARM_SERVICE))
                                              .cancel(Recorder.alarm(this, item.id));
                                          if (store.delete(item.id))
                                            Recorder.delete(Recorder.directory(this, item.id));
                                        } catch (Exception ignored) {
                                        }
                                        main.post(this::load);
                                      }))
                          .setNegativeButton("Cancel", null)
                          .show();
                    });
            })
        .setNegativeButton("Close", null)
        .show();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request != 804) return;
    String id = pendingExport;
    pendingExport = null;
    if (id == null || result != RESULT_OK || data == null || data.getData() == null) return;
    if (!Family.admin(this)) {
      Toast.makeText(this, "Authorization expired. Unlock and retry.", Toast.LENGTH_LONG).show();
      return;
    }
    android.net.Uri tree = data.getData();
    if (!"content".equals(tree.getScheme())) return;
    String profile = Family.active(this);
    worker.execute(
        () -> {
          android.net.Uri folder = null;
          boolean success = false;
          try (RecordingStore store = new RecordingStore(this)) {
            RecordingStore.Item item = store.get(id);
            if (item == null
                || !profile.equals(item.profile)
                || id.equals(RecordingService.activeId)) throw new IOException();
            android.content.ContentResolver resolver = getContentResolver();
            android.net.Uri parent =
                android.provider.DocumentsContract.buildDocumentUriUsingTree(
                    tree, android.provider.DocumentsContract.getTreeDocumentId(tree));
            folder =
                android.provider.DocumentsContract.createDocument(
                    resolver,
                    parent,
                    android.provider.DocumentsContract.Document.MIME_TYPE_DIR,
                    "Moonshot-" + id);
            if (folder == null) throw new IOException();
            File[] files = Recorder.directory(this, id).listFiles();
            if (files == null) throw new IOException();
            int count = 0;
            for (File file : files) {
              if (!file.getName()
                  .matches("capture\\.(ts|m3u8)|segment[0-9]+\\.(ts|m4s)|init[0-9]+\\.mp4"))
                continue;
              android.net.Uri target =
                  android.provider.DocumentsContract.createDocument(
                      resolver,
                      folder,
                      file.getName().endsWith("m3u8")
                          ? "application/vnd.apple.mpegurl"
                          : "application/octet-stream",
                      file.getName());
              if (target == null) throw new IOException();
              try (InputStream in = new FileInputStream(file);
                  OutputStream out = resolver.openOutputStream(target, "wt")) {
                if (out == null) throw new IOException();
                byte[] buffer = new byte[32768];
                int n;
                while ((n = in.read(buffer)) != -1) {
                  if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException();
                  out.write(buffer, 0, n);
                }
              }
              count++;
            }
            if (count == 0) throw new IOException();
            success = true;
          } catch (Exception e) {
            if (folder != null)
              try {
                android.provider.DocumentsContract.deleteDocument(getContentResolver(), folder);
              } catch (Exception ignored) {
              }
          }
          boolean exported = success;
          main.post(
              () -> {
                if (!isDestroyed())
                  Toast.makeText(
                          this,
                          exported
                              ? "Recording files exported."
                              : "Export failed. Check folder access and available storage.",
                          Toast.LENGTH_LONG)
                      .show();
              });
        });
  }

  @Override
  protected void onDestroy() {
    main.removeCallbacksAndMessages(null);
    worker.shutdown();
    super.onDestroy();
  }
}
