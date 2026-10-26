package tv.moonshot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public final class EntryActivity extends Activity {
  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    android.content.Context app = getApplicationContext();
    new Thread(() -> RecordingBoot.reconcile(app, false), "moonshot-recording-recovery").start();
    if (Family.needsUnlock(this)) startActivityForResult(new Intent(this, GateActivity.class), 701);
    else open();
  }

  void open() {
    startActivity(new Intent(this, MainActivity.class));
    finish();
  }

  @Override
  protected void onActivityResult(int r, int result, Intent data) {
    super.onActivityResult(r, result, data);
    if (r == 701) {
      if (result == RESULT_OK && !Family.needsUnlock(this)) open();
      else finish();
    }
  }
}
