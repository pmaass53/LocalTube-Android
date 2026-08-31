package xyz.sunkastudios.localtube.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class ConfigManager {
    private static final String TAG = "ConfigManager";
    private static volatile SharedPreferences prefs;

    public enum SettingType {
        STRING, INT, BOOLEAN, COLOR
    }

    public static class Setting {
        public final String id;
        public final SettingType type;
        public final Object defaultValue;

        public Setting(String id, SettingType type, Object defaultValue) {
            this.id = id;
            this.type = type;
            this.defaultValue = defaultValue;
        }
    }

    private static final Map<String, Setting> settingsRegistry = new HashMap<>();

    static {
        registerSetting("audio_delay", SettingType.INT, 0);
        registerSetting("max_playlist", SettingType.INT, 10);
        registerSetting("navbar_background_color", SettingType.COLOR, "#777777");
        registerSetting("switch_notifications", SettingType.BOOLEAN, false);
        registerSetting("default_language", SettingType.STRING, "en");
        registerSetting("anime_mode", SettingType.STRING, "sub");
        registerSetting("accent_color", SettingType.COLOR, "#FF4081");
        registerSetting("ui_inset_top", SettingType.INT, 0);
        registerSetting("ui_inset_bottom", SettingType.INT, 0);
        registerSetting("buffer_policy", SettingType.STRING, "Standard");
        registerSetting("preferred_quality", SettingType.INT, 1080);
        registerSetting("homepage_cache_expiry", SettingType.INT, -1);
        registerSetting("auto_add_history", SettingType.BOOLEAN, true);
    }

    public static void registerSetting(String id, SettingType type, Object defaultValue) {
        settingsRegistry.put(id, new Setting(id, type, defaultValue));
    }

    public static synchronized void init(Context context) {
        Log.i(TAG, "ConfigManager.init() called");
        if (prefs != null) {
            Log.i(TAG, "ConfigManager already initialized, skipping.");
            return;
        }
        prefs = context.getApplicationContext().getSharedPreferences("app_settings", Context.MODE_PRIVATE);
        
        // If config file doesn't exist, this is a first launch or fresh start.
        // Save the defaults immediately.
        if (!FileLoader.exists(context.getApplicationContext(), "config.txt")) {
            Log.i(TAG, "Config file not found, saving defaults...");
            saveConfig(context);
        } else {
            loadConfig(context);
        }
        Log.i(TAG, "ConfigManager.init() finished");
    }

    private static Setting getRegisteredSetting(String id) {
        Setting s = settingsRegistry.get(id);
        if (s == null) {
            Log.w(TAG, "Accessing unregistered setting: " + id);
        }
        return s;
    }

    private static boolean ensureInitialized() {
        if (prefs == null) {
            Log.e(TAG, "ConfigManager accessed before init!");
            return false;
        }
        return true;
    }

    public static String getString(String id) {
        Setting s = getRegisteredSetting(id);
        String def = (s != null && s.defaultValue instanceof String) ? (String) s.defaultValue : "";
        return ensureInitialized() ? prefs.getString(id, def) : def;
    }

    public static void setString(String id, String value) {
        if (ensureInitialized()) prefs.edit().putString(id, value).apply();
    }

    public static int getInt(String id) {
        Setting s = getRegisteredSetting(id);
        int def = (s != null && s.defaultValue instanceof Integer) ? (Integer) s.defaultValue : 0;
        return ensureInitialized() ? prefs.getInt(id, def) : def;
    }

    public static void setInt(String id, int value) {
        if (ensureInitialized()) prefs.edit().putInt(id, value).apply();
    }

    public static boolean getBoolean(String id) {
        Setting s = getRegisteredSetting(id);
        boolean def = (s != null && s.defaultValue instanceof Boolean) ? (Boolean) s.defaultValue : false;
        return ensureInitialized() ? prefs.getBoolean(id, def) : def;
    }

    public static void setBoolean(String id, boolean value) {
        if (ensureInitialized()) prefs.edit().putBoolean(id, value).apply();
    }

    public static int getColor(String id) {
        Setting s = getRegisteredSetting(id);
        String defaultHex = (s != null && s.defaultValue instanceof String) ? (String) s.defaultValue : "#777777";
        return getColor(id, defaultHex);
    }

    public static int getColor(String id, String defaultHexColor) {
        String val = getString(id);
        String hexColor = (val == null || val.isEmpty()) ? defaultHexColor : val;
        try {
            return Color.parseColor(hexColor);
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse color: " + hexColor);
            try {
                return Color.parseColor(defaultHexColor);
            } catch (Exception e2) {
                return Color.BLACK;
            }
        }
    }

    public static void setColor(String id, String hexValue) {
        setString(id, hexValue);
    }

    public static void saveConfig(Context context) {
        if (!ensureInitialized()) return;
        Map<String, ?> allEntries = prefs.getAll();
        try {
            Gson gson = new Gson();
            String json = gson.toJson(allEntries);
            FileLoader.writeFile(context.getApplicationContext(), "config.txt", json.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            Log.e(TAG, "Failed to save config", e);
        }
    }

    public static void loadConfig(Context context) {
        if (!ensureInitialized()) return;
        try {
            byte[] bytes = FileLoader.readFile(context.getApplicationContext(), "config.txt");
            if (bytes == null || bytes.length == 0) return;
            
            String json = new String(bytes, StandardCharsets.UTF_8);
            Gson gson = new Gson();
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> entries = gson.fromJson(json, type);

            if (entries == null) return;

            SharedPreferences.Editor editor = prefs.edit();
            for (Map.Entry<String, Object> entry : entries.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                
                Setting reg = settingsRegistry.get(key);
                if (reg != null) {
                    applyRegistryValue(editor, reg, value);
                } else {
                    applyUnknownValue(editor, key, value);
                }
            }
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load config", e);
        }
    }

    private static void applyRegistryValue(SharedPreferences.Editor editor, Setting reg, Object value) {
        try {
            switch (reg.type) {
                case INT:
                    if (value instanceof Number) editor.putInt(reg.id, ((Number) value).intValue());
                    break;
                case BOOLEAN:
                    if (value instanceof Boolean) editor.putBoolean(reg.id, (Boolean) value);
                    break;
                case STRING:
                case COLOR:
                    if (value != null) editor.putString(reg.id, value.toString());
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error applying registry value for " + reg.id, e);
        }
    }

    private static void applyUnknownValue(SharedPreferences.Editor editor, String key, Object value) {
        if (value instanceof Boolean) {
            editor.putBoolean(key, (Boolean) value);
        } else if (value instanceof String) {
            editor.putString(key, (String) value);
        } else if (value instanceof Number) {
            Number n = (Number) value;
            if (value instanceof Double || value instanceof Float) {
                double d = n.doubleValue();
                if (d == (int) d) editor.putInt(key, (int) d);
                else editor.putLong(key, (long) d);
            } else {
                editor.putLong(key, n.longValue());
            }
        }
    }
}
