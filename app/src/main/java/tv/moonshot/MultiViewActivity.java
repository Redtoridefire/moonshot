package tv.moonshot;

import android.app.*;
import android.media.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class MultiViewActivity extends FamilyActivity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();
  final List<ExoPlayer> players = new ArrayList<>();
  LinearLayout root, tiles;
  ConnectionBudget.Lease lease;
  boolean active, failed;
  int selected;
  int generation;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    root = Ui.dialogBody(this);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    root.addView(Ui.text(this, "Multiview · two channels · Menu changes audio", 22, Ui.TEXT));
    root.addView(Ui.button(this, "Back", this::finish));
    tiles = Ui.row(this);
    root.addView(tiles, new LinearLayout.LayoutParams(-1, 0, 1));
  }

  static boolean decoderAdmission() {
    try {
      for (MediaCodecInfo codec : new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos())
        if (!codec.isEncoder())
          for (String type : codec.getSupportedTypes())
            if (type.equalsIgnoreCase("video/avc")
                && codec.getCapabilitiesForType(type).getMaxSupportedInstances() >= 2) return true;
    } catch (RuntimeException ignored) {
    }
    return false;
  }

  @Override
  protected void onResume() {
    super.onResume();
    active = true;
    if (failed) return;
    if (Family.needsUnlock(this) || !players.isEmpty() || lease != null) return;
    int request = ++generation;
    if (!decoderAdmission()) {
      error(
          "This device does not advertise two AVC decoder instances. Use single-channel playback.");
      return;
    }
    lease =
        ConnectionBudget.acquire(
            getSharedPreferences("preferences", 0).getInt("connections", 1), 2);
    if (lease == null) {
      error(
          "Two provider connections must be available. Set your actual subscription limit in"
              + " Settings, and stop a recording if needed.");
      return;
    }
    worker.execute(
        () -> {
          try (Library library = ((MoonshotApplication) getApplication()).openLibrary()) {
            Channel first = library.channel(getIntent().getStringExtra("first")),
                second = library.channel(getIntent().getStringExtra("second"));
            if (!first.kind.equals("live")
                || !second.kind.equals("live")
                || first.id.equals(second.id)) throw new IllegalArgumentException();
            runOnUiThread(
                () -> {
                  if (active && !isDestroyed() && request == generation) start(first, second);
                });
          } catch (Exception e) {
            runOnUiThread(
                () -> {
                  if (request == generation)
                    error("Both live channels must still exist in this profile.");
                });
          }
        });
  }

  void start(Channel first, Channel second) {
    tiles.removeAllViews();
    try {
      int index = 0;
      for (Channel channel : new Channel[] {first, second}) {
        final int tile = index++;
        LinearLayout column = Ui.column(this);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, 1);
        params.setMargins(Ui.dp(this, 5), 0, Ui.dp(this, 5), 0);
        tiles.addView(column, params);
        column.addView(Ui.button(this, channel.name + " · Listen", () -> audio(tile)));
        PlayerView view = new PlayerView(this);
        column.addView(view, new LinearLayout.LayoutParams(-1, 0, 1));
        androidx.media3.datasource.okhttp.OkHttpDataSource.Factory http =
            new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(Network.playbackClient())
                .setUserAgent("Moonshot/0.3 AndroidTV");
        boolean allow = getSharedPreferences("preferences", 0).getBoolean("allowHttp", false);
        DataSource.Factory guarded =
            () -> new PlaybackActivity.GuardedSource(http.createDataSource(), allow);
        DefaultLoadControl buffer =
            new DefaultLoadControl.Builder()
                .setBufferDurationsMs(10000, 20000, 1500, 2500)
                .setTargetBufferBytes(12 * 1024 * 1024)
                .setPrioritizeTimeOverSizeThresholds(false)
                .build();
        androidx.media3.exoplayer.trackselection.DefaultTrackSelector selector =
            new androidx.media3.exoplayer.trackselection.DefaultTrackSelector(this);
        selector.setParameters(
            selector
                .buildUponParameters()
                .setMaxVideoSize(1280, 720)
                .setExceedVideoConstraintsIfNecessary(false));
        ExoPlayer player =
            new ExoPlayer.Builder(this)
                .setTrackSelector(selector)
                .setMediaSourceFactory(new DefaultMediaSourceFactory(guarded))
                .setLoadControl(buffer)
                .build();
        players.add(player);
        view.setPlayer(player);
        view.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
        player.setAudioAttributes(
            new androidx.media3.common.AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build(),
            tile == 0);
        player.setVolume(tile == 0 ? 1 : 0);
        player.setMediaItem(MediaItem.fromUri(channel.url));
        player.addListener(
            new Player.Listener() {
              @Override
              public void onTracksChanged(Tracks tracks) {
                if (!tracks.isEmpty() && !tracks.isTypeSelected(C.TRACK_TYPE_VIDEO))
                  error(
                      "This channel has no supported video rendition at or below 720p. Use"
                          + " single-channel playback.");
              }

              @Override
              public void onVideoSizeChanged(VideoSize size) {
                if (size.width > 1280 || size.height > 720)
                  error(
                      "This rendition exceeds the multiview resolution limit. Use single-channel"
                          + " playback.");
              }

              @Override
              public void onPlayerError(PlaybackException e) {
                error(
                    "Multiview cannot play this combination. Check provider limits and stream"
                        + " formats. Tiles require a rendition at or below 720p; fixed"
                        + " higher-resolution streams may fail.");
              }
            });
        player.prepare();
        player.play();
      }
    } catch (RuntimeException e) {
      error("The device could not allocate both players. Use single-channel playback.");
    }
  }

  void audio(int index) {
    if (index >= players.size()) return;
    selected = index;
    for (int i = 0; i < players.size(); i++) {
      ExoPlayer player = players.get(i);
      player.setVolume(i == index ? 1 : 0);
      player.setAudioAttributes(
          new androidx.media3.common.AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
              .build(),
          i == index);
      if (i == index) player.play();
    }
  }

  void release() {
    for (ExoPlayer player : players) player.release();
    players.clear();
    if (lease != null) {
      lease.close();
      lease = null;
    }
  }

  void error(String text) {
    if (!active || isDestroyed()) {
      release();
      return;
    }
    if (failed) return;
    failed = true;
    release();
    if (isDestroyed() || !active) return;
    new AlertDialog.Builder(this)
        .setTitle("Multiview unavailable")
        .setMessage(text)
        .setPositiveButton("Back", (d, w) -> finish())
        .setOnCancelListener(d -> finish())
        .show();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (event.getAction() == KeyEvent.ACTION_DOWN && event.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      audio(1 - selected);
      return true;
    }
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onStop() {
    active = false;
    generation++;
    release();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    worker.shutdownNow();
    super.onDestroy();
  }
}
