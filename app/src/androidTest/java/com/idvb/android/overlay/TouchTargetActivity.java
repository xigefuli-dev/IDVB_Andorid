package com.idvb.android.overlay;

import android.app.Activity;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.TextView;

/** Separate APK/UID, using only framework classes (no target APK dependencies). */
public class TouchTargetActivity extends Activity {
    private int touches;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        TextView target = new TextView(this);
        target.setGravity(Gravity.CENTER);
        target.setText("Touches: 0 Status: IDLE");
        target.setOnClickListener(view -> target.setText("Touches: " + (++touches) + " Status: CLICKED"));
        target.setOnTouchListener((view, event) -> {
            int action = event.getActionMasked();
            if (action == android.view.MotionEvent.ACTION_DOWN) {
                target.setText("Touches: " + touches + " Status: DOWN");
            } else if (action == android.view.MotionEvent.ACTION_CANCEL) {
                target.setText("Touches: " + touches + " Status: CANCEL");
            } else if (action == android.view.MotionEvent.ACTION_UP) {
                target.setText("Touches: " + touches + " Status: UP");
            }
            return false;
        });
        setContentView(target);
    }
}
