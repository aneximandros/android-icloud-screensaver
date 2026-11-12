package com.aneximandros.icloudscreensaver;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

public class SetupActivity extends Activity {

    private EditText urlInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_setup);

        urlInput = findViewById(R.id.albumUrlInput);
        Button saveButton = findViewById(R.id.saveButton);

        SharedPreferences prefs = getSharedPreferences(ICloudPhotosDream.PREFS, MODE_PRIVATE);
        String saved = prefs.getString(ICloudPhotosDream.PREF_URL, "");
        if (!saved.isEmpty()) urlInput.setText(saved);

        saveButton.setOnClickListener(v -> {
            String url = urlInput.getText().toString().trim();
            if (!url.startsWith("https://photos.icloud.com/shared/album/")) {
                Toast.makeText(this, R.string.setup_invalid_url, Toast.LENGTH_LONG).show();
                return;
            }
            prefs.edit().putString(ICloudPhotosDream.PREF_URL, url).apply();
            Toast.makeText(this, R.string.setup_saved, Toast.LENGTH_LONG).show();
        });
    }
}
