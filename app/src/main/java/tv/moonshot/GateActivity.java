package tv.moonshot;

import android.app.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.Arrays;
import java.util.concurrent.*;

public final class GateActivity extends Activity {
  final ExecutorService worker = Executors.newSingleThreadExecutor();

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    Ui.immersive(this);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    if (!Family.hasPin(this)) {
      setResult(RESULT_OK);
      finish();
      return;
    }
    LinearLayout root = Ui.dialogBody(this);
    root.setGravity(Gravity.CENTER);
    root.setBackgroundColor(Ui.BG);
    setContentView(root);
    root.addView(Ui.text(this, "Enter parental PIN", 26, Ui.TEXT));
    TextView message =
        Ui.text(
            this,
            "Six to twelve digits. Five incorrect attempts cause a 30-second lockout.",
            14,
            Ui.MUTED);
    root.addView(message);
    EditText pin = Ui.input(this, "PIN", true);
    pin.setInputType(
        android.text.InputType.TYPE_CLASS_NUMBER
            | android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    pin.setFilters(new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(12)});
    root.addView(pin, new LinearLayout.LayoutParams(Ui.dp(this, 320), -2));
    Button unlock = Ui.button(this, "Unlock", () -> {});
    root.addView(unlock);
    root.addView(Ui.button(this, "Cancel", this::finish));
    unlock.setOnClickListener(
        v -> {
          char[] candidate = pin.getText().toString().toCharArray();
          pin.getText().clear();
          unlock.setEnabled(false);
          worker.execute(
              () -> {
                boolean valid = false;
                try {
                  valid = Family.verify(this, candidate);
                } catch (Exception ignored) {
                } finally {
                  Arrays.fill(candidate, '\0');
                }
                boolean accepted = valid;
                runOnUiThread(
                    () -> {
                      if (isDestroyed()) return;
                      unlock.setEnabled(true);
                      if (accepted) {
                        Family.grant(this, getIntent().getBooleanExtra("admin", false));
                        setResult(RESULT_OK);
                        finish();
                      } else
                        message.setText(
                            "Incorrect PIN or temporarily locked. Try again after 30 seconds.");
                    });
              });
        });
    pin.requestFocus();
  }

  @Override
  protected void onDestroy() {
    super.onDestroy();
    worker.shutdown();
  }
}
