package tv.moonshot;

import android.app.*;
import android.net.Uri;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.datasource.*;
import androidx.media3.exoplayer.*;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class PlaybackActivity extends FamilyActivity {
  private Library library;
  private Channel channel;
  private ExoPlayer player;
  private PlayerView video;
  private LinearLayout error;
  private TextView status;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private boolean active;
  private long resume;
  private String channelId;
  private boolean zapping;
  private ConnectionBudget.Lease lease;
  private String archiveUrl;
  private boolean archive;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    getWindow()
        .addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                | WindowManager.LayoutParams.FLAG_SECURE);
    archive = getIntent().hasExtra("archiveStart");
    channelId = getIntent().getStringExtra("channel");
    if (state != null) resume = state.getLong("resume", 0);
    FrameLayout root = new FrameLayout(this);
    root.setBackgroundColor(android.graphics.Color.BLACK);
    setContentView(root);
    video = new PlayerView(this);
    video.setId(R.id.player_view);
    video.setUseController(true);
    video.setControllerAutoShow(true);
    video.setControllerShowTimeoutMs(5000);
    video.setShowSubtitleButton(true);
    video.setShowNextButton(false);
    video.setShowPreviousButton(false);
    video.setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING);
    root.addView(video, new FrameLayout.LayoutParams(-1, -1));
    LinearLayout top = Ui.row(this);
    top.setPadding(Ui.dp(this, 32), Ui.dp(this, 22), Ui.dp(this, 32), Ui.dp(this, 40));
    top.setBackground(Ui.vertical(0xE6000000, 0x00000000, 0));
    ImageView mark = new ImageView(this);
    mark.setImageDrawable(Ui.icon(this, "moon", 22, Ui.ACCENT));
    LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(Ui.dp(this, 22), Ui.dp(this, 22));
    mp.rightMargin = Ui.dp(this, 14);
    top.addView(mark, mp);
    status = Ui.heading(this, "Opening stream…", 18);
    top.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
    top.addView(Ui.button(this, "Audio / captions", this::tracks));
    FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
    root.addView(top, tp);
    video.setControllerVisibilityListener(
        (PlayerView.ControllerVisibilityListener) visibility -> top.setVisibility(visibility));
    error = Ui.column(this);
    error.setGravity(Gravity.CENTER);
    error.setPadding(Ui.dp(this, 40), Ui.dp(this, 30), Ui.dp(this, 40), Ui.dp(this, 30));
    error.setBackground(Ui.vertical(0xF2101826, 0xF2090D15, 0));
    error.setVisibility(View.GONE);
    root.addView(error, new FrameLayout.LayoutParams(-1, -1));
    try {
      library = ((MoonshotApplication) getApplication()).openLibrary();
    } catch (Exception e) {
      fail("Secure storage could not be opened.");
    }
    video.requestFocus();
  }

  @Override
  protected void onStart() {
    super.onStart();
    active = true;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (active && library != null && player == null && !Family.needsUnlock(this)) load();
  }

  private void load() {
    worker.execute(
        () -> {
          try {
            Channel c = library.channel(channelId);
            String address =
                archive
                    ? Catchup.address(
                        c,
                        getIntent().getLongExtra("archiveStart", 0),
                        getIntent().getLongExtra("archiveEnd", 0),
                        System.currentTimeMillis())
                    : null;
            main.post(
                () -> {
                  if (!active) return;
                  if (archive && address == null) {
                    fail("Catch-up is unavailable for this program or its archive has expired.");
                    return;
                  }
                  archiveUrl = address;
                  channel = c;
                  if (resume == 0) resume = c.position;
                  startPlayer();
                });
          } catch (Exception e) {
            main.post(
                () ->
                    fail(
                        "This stream is no longer in your library. Refresh the playlist and try"
                            + " again."));
          }
        });
  }

  private void startPlayer() {
    if (!active || channel == null) return;
    release();
    lease =
        ConnectionBudget.acquire(
            getSharedPreferences("preferences", 0).getInt("connections", 1), 1);
    if (lease == null) {
      fail(
          "The provider connection budget is in use. Stop a recording or multiview session, or set"
              + " your actual subscription limit in Settings.");
      return;
    }
    error.setVisibility(View.GONE);
    status.setText(channel.name + "  ·  OK controls  /  Menu audio");
    boolean allowHttp =
        getSharedPreferences("preferences", MODE_PRIVATE).getBoolean("allowHttp", false);
    androidx.media3.datasource.okhttp.OkHttpDataSource.Factory http =
        new androidx.media3.datasource.okhttp.OkHttpDataSource.Factory(
                Network.playbackClient(allowHttp))
            .setUserAgent(Network.userAgent());
    DataSource.Factory guarded = () -> new GuardedSource(http.createDataSource(), allowHttp);
    DefaultMediaSourceFactory sources = new DefaultMediaSourceFactory(guarded);
    DefaultLoadControl buffer =
        new DefaultLoadControl.Builder()
            .setBufferDurationsMs(15000, 45000, 1500, 3000)
            .setTargetBufferBytes(32 * 1024 * 1024)
            .setPrioritizeTimeOverSizeThresholds(false)
            .build();
    player =
        new ExoPlayer.Builder(this).setMediaSourceFactory(sources).setLoadControl(buffer).build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    player.setHandleAudioBecomingNoisy(true);
    video.setPlayer(player);
    MediaItem.Builder item =
        new MediaItem.Builder()
            .setUri(archive ? archiveUrl : channel.url)
            .setMediaMetadata(new MediaMetadata.Builder().setTitle(channel.name).build());
    String path = Uri.parse(archive ? archiveUrl : channel.url).getPath();
    if (path != null && path.toLowerCase(Locale.ROOT).endsWith(".m3u8"))
      item.setMimeType(MimeTypes.APPLICATION_M3U8);
    player.setMediaItem(item.build());
    if ((!channel.kind.equals("live") || archive) && resume > 0) player.seekTo(resume);
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayerError(PlaybackException e) {
            savePosition();
            fail(explain(e));
          }

          @Override
          public void onPlaybackStateChanged(int state) {
            if (state == Player.STATE_ENDED) {
              resume = 0;
              if (channel != null && !archive)
                worker.execute(() -> library.position(channel.id, 0));
            }
          }
        });
    player.prepare();
    player.play();
    video.requestFocus();
  }

  /** A specific, address-free explanation of a playback failure. */
  static String explain(PlaybackException e) {
    String cause = "";
    for (Throwable t = e; t != null; t = t.getCause())
      if (t.getMessage() != null && t.getMessage().startsWith("Stream ")) {
        cause = t.getMessage();
        break;
      }
    if (cause.contains("blocked") || cause.contains("unencrypted"))
      return "This stream uses unencrypted HTTP, which is turned off. Allow it in Settings →"
          + " Unencrypted HTTP if your provider requires it.";
    if (cause.contains("HTTP 401") || cause.contains("HTTP 403"))
      return "The provider refused this stream ("
          + cause.substring(cause.indexOf("HTTP"), cause.indexOf("HTTP") + 8)
          + "). Your connection limit may be in use on another device, the link may have"
          + " expired, or the provider may reject this player: try Settings → Player identity.";
    if (cause.contains("HTTP 404") || cause.contains("HTTP 410"))
      return "The provider no longer has this stream. Refresh the playlist and try again.";
    if (cause.contains("HTTP 5"))
      return "The provider's server had an error for this stream. Try again shortly.";
    switch (e.errorCode) {
      case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED:
        return "Could not connect to the stream server. It may be offline or blocked on this"
            + " network; Settings → Secure DNS: Always can help when it is blocked.";
      case PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT:
        return "The stream server timed out. Check the connection and try again.";
      case PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED:
      case PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED:
      case PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED:
        return "The stream's format could not be read. The provider may be sending an error page"
            + " instead of video, or a format this device does not support.";
      case PlaybackException.ERROR_CODE_DECODER_INIT_FAILED:
      case PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED:
      case PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES:
        return "This device cannot decode the stream's video or audio format.";
      case PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW:
        return "Playback fell behind the live stream. Press OK to retry.";
      default:
        return "Unable to play this stream. The provider may be offline, your connection limit"
            + " may be reached, or this device may not support its format.";
    }
  }

  private void fail(String message) {
    if (isFinishing() || isDestroyed()) return;
    error.removeAllViews();
    TextView heading = Ui.text(this, "Let’s get you back to watching.", 24, Ui.TEXT);
    error.addView(heading);
    TextView text = Ui.text(this, message, 15, Ui.MUTED);
    text.setGravity(Gravity.CENTER);
    text.setPadding(0, Ui.dp(this, 18), 0, Ui.dp(this, 20));
    error.addView(text);
    LinearLayout buttons = Ui.row(this);
    Button retry = Ui.button(this, "Try again", this::startPlayer);
    buttons.addView(retry);
    Button back = Ui.button(this, "Back to library", this::finish);
    LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-2, -2);
    p.setMargins(Ui.dp(this, 16), 0, 0, 0);
    buttons.addView(back, p);
    error.addView(buttons);
    error.setVisibility(View.VISIBLE);
    retry.requestFocus();
  }

  private void zap(int step) {
    if (channel == null || (!channel.kind.equals("live") || archive) || zapping) return;
    zapping = true;
    String current = channel.id, group = channel.group;
    worker.execute(
        () -> {
          try {
            List<Channel> peers = library.channels("live", group, "", false);
            int at = -1;
            for (int i = 0; i < peers.size(); i++)
              if (peers.get(i).id.equals(current)) {
                at = i;
                break;
              }
            if (at < 0 || peers.isEmpty()) {
              main.post(() -> zapping = false);
              return;
            }
            Channel next = library.channel(peers.get((at + step + peers.size()) % peers.size()).id);
            main.post(
                () -> {
                  zapping = false;
                  if (!active) return;
                  channel = next;
                  channelId = next.id;
                  resume = 0;
                  startPlayer();
                });
          } catch (Exception e) {
            main.post(() -> zapping = false);
          }
        });
  }

  private void tracks() {
    if (player == null) return;
    new AlertDialog.Builder(this)
        .setTitle("Playback options")
        .setItems(
            new String[] {"Audio track", "Subtitles", "Aspect ratio: fit / fill"},
            (d, w) -> {
              if (w == 0) chooseTrack(C.TRACK_TYPE_AUDIO);
              if (w == 1) chooseTrack(C.TRACK_TYPE_TEXT);
              if (w == 2)
                video.setResizeMode(
                    video.getResizeMode()
                            == androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
                        ? androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                        : androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT);
            })
        .show();
  }

  private void chooseTrack(int type) {
    if (player == null) return;
    List<String> labels = new ArrayList<>();
    List<TrackSelectionOverride> overrides = new ArrayList<>();
    labels.add(type == C.TRACK_TYPE_TEXT ? "Off" : "Automatic");
    overrides.add(null);
    for (Tracks.Group g : player.getCurrentTracks().getGroups())
      if (g.getType() == type)
        for (int i = 0; i < g.length; i++)
          if (g.isTrackSupported(i)) {
            Format f = g.getTrackFormat(i);
            String lang =
                f.language == null
                    ? "Unknown language"
                    : Locale.forLanguageTag(f.language).getDisplayLanguage();
            labels.add(
                (f.label == null ? lang : f.label)
                    + (f.channelCount > 0 ? " · " + f.channelCount + " channels" : ""));
            overrides.add(new TrackSelectionOverride(g.getMediaTrackGroup(), i));
          }
    new AlertDialog.Builder(this)
        .setTitle(type == C.TRACK_TYPE_TEXT ? "Subtitles" : "Audio")
        .setItems(
            labels.toArray(new String[0]),
            (d, w) -> {
              if (player == null) return;
              TrackSelectionParameters.Builder b =
                  player
                      .getTrackSelectionParameters()
                      .buildUpon()
                      .clearOverridesOfType(type)
                      .setTrackTypeDisabled(type, type == C.TRACK_TYPE_TEXT && w == 0);
              if (w > 0) b.addOverride(overrides.get(w));
              player.setTrackSelectionParameters(b.build());
            })
        .setNegativeButton("Close", null)
        .show();
  }

  @Override
  public boolean dispatchKeyEvent(KeyEvent e) {
    if (e.getAction() == KeyEvent.ACTION_DOWN && e.getKeyCode() == KeyEvent.KEYCODE_MENU) {
      tracks();
      return true;
    }
    if (e.getAction() == KeyEvent.ACTION_DOWN && error.getVisibility() != View.VISIBLE) {
      if (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_UP) {
        zap(1);
        return true;
      }
      if (e.getKeyCode() == KeyEvent.KEYCODE_CHANNEL_DOWN) {
        zap(-1);
        return true;
      }
      if (!archive
          && channel != null
          && channel.kind.equals("live")
          && !video.isControllerFullyVisible()) {
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP) {
          zap(-1);
          return true;
        }
        if (e.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN) {
          zap(1);
          return true;
        }
      }
    }
    return super.dispatchKeyEvent(e);
  }

  @Override
  public void onBackPressed() {
    if (player != null && video.isControllerFullyVisible()) {
      video.hideController();
    } else super.onBackPressed();
  }

  private void savePosition() {
    if (player != null && channel != null && !archive && !channel.kind.equals("live")) {
      long position =
          player.getPlaybackState() == Player.STATE_ENDED
              ? 0
              : Math.max(0, player.getCurrentPosition());
      resume = position;
      String id = channel.id;
      worker.execute(() -> library.position(id, position));
    }
  }

  private void release() {
    if (player != null) {
      video.setPlayer(null);
      player.release();
      player = null;
    }
    if (lease != null) {
      lease.close();
      lease = null;
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    if (player != null) resume = Math.max(0, player.getCurrentPosition());
    out.putLong("resume", resume);
    super.onSaveInstanceState(out);
  }

  @Override
  protected void onStop() {
    active = false;
    savePosition();
    release();
    super.onStop();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    main.removeCallbacksAndMessages(null);
    worker.shutdown();
    if (library != null)
      new Thread(
              () -> {
                try {
                  worker.awaitTermination(35, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                }
                library.close();
              },
              "moonshot-player-close")
          .start();
  }

  static final class GuardedSource implements DataSource {
    private final DataSource delegate;
    private final boolean allowHttp;

    GuardedSource(DataSource delegate, boolean allowHttp) {
      this.delegate = delegate;
      this.allowHttp = allowHttp;
    }

    @Override
    public long open(DataSpec spec) throws IOException {
      String scheme = spec.uri.getScheme();
      if (!"https".equalsIgnoreCase(scheme) && !(allowHttp && "http".equalsIgnoreCase(scheme)))
        throw new IOException("Stream protocol is blocked");
      try {
        return delegate.open(spec);
      } catch (HttpDataSource.InvalidResponseCodeException e) {
        throw new IOException("Stream connection failed: HTTP " + e.responseCode);
      } catch (IOException e) {
        for (Throwable t = e; t != null; t = t.getCause())
          if (t instanceof ProviderException)
            throw new IOException("Stream blocked: unencrypted redirect");
        throw new IOException("Stream connection failed");
      }
    }

    @Override
    public int read(byte[] b, int o, int l) throws IOException {
      try {
        return delegate.read(b, o, l);
      } catch (IOException e) {
        throw new IOException("Stream read failed");
      }
    }

    @Override
    public Uri getUri() {
      return delegate.getUri();
    }

    @Override
    public Map<String, List<String>> getResponseHeaders() {
      return delegate.getResponseHeaders();
    }

    @Override
    public void close() throws IOException {
      delegate.close();
    }

    @Override
    public void addTransferListener(TransferListener listener) {
      delegate.addTransferListener(listener);
    }
  }
}
