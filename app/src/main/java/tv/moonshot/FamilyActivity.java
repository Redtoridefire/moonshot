package tv.moonshot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.WindowManager;

/** Internal activities re-check the selected profile after backgrounding. */
public abstract class FamilyActivity extends Activity {
  private boolean waiting;
  private Runnable adminAction;

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
  }

  boolean adminOnly() {
    return false;
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (!isFinishing()
        && !waiting
        && (Family.needsUnlock(this) || (adminOnly() && !Family.admin(this)))) {
      getWindow().getDecorView().setVisibility(android.view.View.INVISIBLE);
      waiting = true;
      startActivityForResult(
          new Intent(this, GateActivity.class).putExtra("admin", adminOnly()), 701);
    } else if (!Family.needsUnlock(this) && (!adminOnly() || Family.admin(this)))
      getWindow().getDecorView().setVisibility(android.view.View.VISIBLE);
  }

  void requireAdmin(Runnable action) {
    if (Family.admin(this)) {
      action.run();
      return;
    }
    adminAction = action;
    startActivityForResult(new Intent(this, GateActivity.class).putExtra("admin", true), 702);
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 701) {
      waiting = false;
      if (result != RESULT_OK) finishAffinity();
    }
    if (request == 702) {
      Runnable action = adminAction;
      adminAction = null;
      if (result == RESULT_OK && Family.admin(this) && action != null) action.run();
    }
  }
}
