package tv.moonshot;

import android.os.Bundle;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import java.io.*;

@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class RecordingPlaybackActivity extends FamilyActivity {
  ExoPlayer player;
  PlayerView view;
  long position;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    view = new PlayerView(this);
    setContentView(view);
    if (state != null) position = state.getLong("position");
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (Family.needsUnlock(this) || player != null) return;
    try (RecordingStore store = new RecordingStore(this)) {
      RecordingStore.Item item = store.get(getIntent().getStringExtra("id"));
      if (item == null
          || !item.profile.equals(Family.active(this))
          || !item.file.matches("capture\\.(ts|m3u8)")) throw new IOException();
      File file = new File(Recorder.directory(this, item.id), item.file);
      if (!file.isFile()) throw new IOException();
      player = new ExoPlayer.Builder(this).build();
      view.setPlayer(player);
      player.setAudioAttributes(
          new AudioAttributes.Builder()
              .setUsage(C.USAGE_MEDIA)
              .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
              .build(),
          true);
      player.setMediaItem(MediaItem.fromUri(android.net.Uri.fromFile(file)));
      player.seekTo(position);
      player.addListener(
          new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException e) {
              new android.app.AlertDialog.Builder(RecordingPlaybackActivity.this)
                  .setMessage(
                      "This recording is incomplete or its format is unsupported on this device.")
                  .setPositiveButton("Close", (d, w) -> finish())
                  .show();
            }
          });
      player.prepare();
      player.play();
      view.requestFocus();
      getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    } catch (Exception e) {
      new android.app.AlertDialog.Builder(this)
          .setMessage("Recording is missing, belongs to another profile, or is incomplete.")
          .setPositiveButton("Close", (d, w) -> finish())
          .show();
    }
  }

  @Override
  protected void onStop() {
    if (player != null) {
      position = player.getCurrentPosition();
      player.release();
      player = null;
    }
    super.onStop();
  }

  @Override
  protected void onSaveInstanceState(Bundle out) {
    out.putLong("position", player == null ? position : player.getCurrentPosition());
    super.onSaveInstanceState(out);
  }
}
