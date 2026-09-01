package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.OptIn;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.util.UnstableApi;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.materialswitch.MaterialSwitch;

import java.util.Arrays;
import java.util.List;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.util.ConfigManager;
import xyz.sunkastudios.localtube.util.UIUtil;

public class SettingsActivity extends AppCompatActivity {
    private EditText inputAudioDelay, inputMaxPlaylist, inputNavbarColor, inputAccentColor, inputDefaultLanguage, inputInsetTop, inputInsetBottom, inputCacheExpiry;
    private Spinner spinnerAnimeMode, spinnerBufferPolicy, spinnerPreferredQuality;
    private MaterialSwitch switchAutoHistory;
    private TextView headerGeneral, headerYoutube, headerAnime;
    private MaterialButton btnSave;

    @OptIn(markerClass = UnstableApi.class)
    @Override
    protected void onCreate(Bundle savedInstanceData) {
        super.onCreate(savedInstanceData);
        setContentView(R.layout.activity_settings);
        UIUtil.applyInsets(this);

        bindViews();
        loadCurrentSettings();
        setupSpinners();
        setupNavigation();
        setupColorAutoPreview();
        setupVersionInfo();

        btnSave.setOnClickListener(v -> saveSettings());
    }

    private void setupVersionInfo() {
        try {
            PackageInfo pInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String version = pInfo.versionName;
            TextView versionText = findViewById(R.id.text_version);
            if (versionText != null) {
                versionText.setText("Version " + version);
            }
        } catch (Exception ignored) {}
    }

    private void bindViews() {
        inputAudioDelay = findViewById(R.id.input_audio_delay);
        inputMaxPlaylist = findViewById(R.id.input_max_playlist);
        inputNavbarColor = findViewById(R.id.input_navbar_color);
        inputAccentColor = findViewById(R.id.input_accent_color);
        inputDefaultLanguage = findViewById(R.id.input_default_language);
        inputInsetTop = findViewById(R.id.input_ui_inset_top);
        inputInsetBottom = findViewById(R.id.input_ui_inset_bottom);
        inputCacheExpiry = findViewById(R.id.input_cache_expiry);
        
        spinnerAnimeMode = findViewById(R.id.spinner_anime_mode);
        spinnerBufferPolicy = findViewById(R.id.spinner_buffer_policy);
        spinnerPreferredQuality = findViewById(R.id.spinner_preferred_quality);

        switchAutoHistory = findViewById(R.id.switch_auto_history);
        
        btnSave = findViewById(R.id.btn_save);
        headerGeneral = findViewById(R.id.header_general);
        headerYoutube = findViewById(R.id.header_youtube);
        headerAnime = findViewById(R.id.header_anime);
    }

    private void loadCurrentSettings() {
        inputAudioDelay.setText(String.valueOf(ConfigManager.getInt("audio_delay")));
        inputMaxPlaylist.setText(String.valueOf(ConfigManager.getInt("max_playlist")));
        inputDefaultLanguage.setText(ConfigManager.getString("default_language"));
        inputInsetTop.setText(String.valueOf(ConfigManager.getInt("ui_inset_top")));
        inputInsetBottom.setText(String.valueOf(ConfigManager.getInt("ui_inset_bottom")));
        inputCacheExpiry.setText(String.valueOf(ConfigManager.getInt("homepage_cache_expiry")));

        String navColor = ConfigManager.getString("navbar_background_color");
        if (navColor.isEmpty()) navColor = "#777777";
        inputNavbarColor.setText(navColor.replace("#", ""));

        String accentColor = ConfigManager.getString("accent_color");
        inputAccentColor.setText(accentColor.replace("#", ""));
        
        switchAutoHistory.setChecked(ConfigManager.getBoolean("auto_add_history"));

        applyAccentColor(accentColor);
    }

    private void setupSpinners() {
        // Anime Mode
        String[] animeModes = {"sub", "dub"};
        setupSpinner(spinnerAnimeMode, Arrays.asList(animeModes), ConfigManager.getString("anime_mode"));

        // Buffer Policy
        String[] bufferPolicies = {"Standard", "Fast Start", "High Stability"};
        setupSpinner(spinnerBufferPolicy, Arrays.asList(bufferPolicies), ConfigManager.getString("buffer_policy"));

        // Preferred Quality
        List<String> qualities = Arrays.asList("2160p (4K)", "1440p (2K)", "1080p", "720p", "480p", "360p");
        String currentQuality = ConfigManager.getInt("preferred_quality") + "p";
        int currentIdx = 2; // Default 1080p
        for(int i=0; i<qualities.size(); i++) if(qualities.get(i).contains(currentQuality)) currentIdx = i;
        
        ArrayAdapter<String> qualityAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, qualities);
        qualityAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinnerPreferredQuality.setAdapter(qualityAdapter);
        spinnerPreferredQuality.setSelection(currentIdx);
    }

    private void setupSpinner(Spinner spinner, List<String> options, String currentVal) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, options);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        int idx = options.indexOf(currentVal);
        if (idx != -1) spinner.setSelection(idx);
    }

    private void setupColorAutoPreview() {
        inputAccentColor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(Editable s) {
                if (s.length() == 6) applyAccentColor("#" + s);
            }
        });
    }

    private void applyAccentColor(String hex) {
        try {
            int color = Color.parseColor(hex);
            headerGeneral.setTextColor(color);
            headerYoutube.setTextColor(color);
            headerAnime.setTextColor(color);
            btnSave.setBackgroundColor(color);
        } catch (Exception ignored) {}
    }

    private void setupNavigation() {
        findViewById(R.id.bottomMenuBar).setBackground(new ColorDrawable(ConfigManager.getColor("navbar_background_color")));
        findViewById(R.id.btnYoutubeHome).setOnClickListener(v -> startActivity(new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)));
        findViewById(R.id.btnYoutubeShorts).setOnClickListener(new View.OnClickListener() {
            @OptIn(markerClass = UnstableApi.class)
            @Override public void onClick(View v) { startActivity(new Intent(SettingsActivity.this, ShortsActivity.class)); }
        });
        findViewById(R.id.btnAniHome).setOnClickListener(v -> startActivity(new Intent(this, AniHomeActivity.class)));
        findViewById(R.id.btnDownloads).setOnClickListener(v -> startActivity(new Intent(this, DownloadsActivity.class)));
    }

    private void saveSettings() {
        boolean isValid = true;

        String navHex = inputNavbarColor.getText().toString().trim();
        String accentHex = inputAccentColor.getText().toString().trim();
        
        if (navHex.length() == 6) ConfigManager.setString("navbar_background_color", "#" + navHex);
        else { inputNavbarColor.setError("6 chars"); isValid = false; }

        if (accentHex.length() == 6) ConfigManager.setString("accent_color", "#" + accentHex);
        else { inputAccentColor.setError("6 chars"); isValid = false; }

        try { ConfigManager.setInt("audio_delay", Integer.parseInt(inputAudioDelay.getText().toString())); }
        catch (Exception e) { inputAudioDelay.setError("Invalid"); isValid = false; }

        try { ConfigManager.setInt("max_playlist", Integer.parseInt(inputMaxPlaylist.getText().toString())); }
        catch (Exception e) { inputMaxPlaylist.setError("Invalid"); isValid = false; }

        try { ConfigManager.setInt("ui_inset_top", Integer.parseInt(inputInsetTop.getText().toString())); }
        catch (Exception e) { inputInsetTop.setError("Invalid"); isValid = false; }

        try { ConfigManager.setInt("ui_inset_bottom", Integer.parseInt(inputInsetBottom.getText().toString())); }
        catch (Exception e) { inputInsetBottom.setError("Invalid"); isValid = false; }

        try { ConfigManager.setInt("homepage_cache_expiry", Integer.parseInt(inputCacheExpiry.getText().toString())); }
        catch (Exception e) { inputCacheExpiry.setError("Invalid"); isValid = false; }

        ConfigManager.setString("default_language", inputDefaultLanguage.getText().toString().trim());
        ConfigManager.setString("anime_mode", spinnerAnimeMode.getSelectedItem().toString());
        ConfigManager.setString("buffer_policy", spinnerBufferPolicy.getSelectedItem().toString());
        ConfigManager.setBoolean("auto_add_history", switchAutoHistory.isChecked());
        
        String qualStr = spinnerPreferredQuality.getSelectedItem().toString();
        int qual = 1080;
        if (qualStr.contains("2160")) qual = 2160;
        else if (qualStr.contains("1440")) qual = 1440;
        else if (qualStr.contains("720")) qual = 720;
        else if (qualStr.contains("480")) qual = 480;
        else if (qualStr.contains("360")) qual = 360;
        ConfigManager.setInt("preferred_quality", qual);

        if (isValid) {
            ConfigManager.saveConfig(this);
            Toast.makeText(this, "Settings Applied", Toast.LENGTH_SHORT).show();
            finish();
        }
    }
}
