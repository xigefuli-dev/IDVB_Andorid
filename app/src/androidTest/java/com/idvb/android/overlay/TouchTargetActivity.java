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
        target.setText("Touches: 0");
        target.setOnClickListener(view -> target.setText("Touches: " + (++touches)));
        setContentView(target);
    }
}
