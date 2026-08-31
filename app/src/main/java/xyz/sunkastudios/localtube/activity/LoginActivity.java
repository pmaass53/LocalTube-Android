package xyz.sunkastudios.localtube.activity;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.appcompat.app.AppCompatActivity;

import xyz.sunkastudios.localtube.R;
import xyz.sunkastudios.localtube.util.FileLoader;
import xyz.sunkastudios.localtube.util.UIUtil;

public class LoginActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);
        UIUtil.applyInsets(this);

        WebView webView = (WebView) findViewById(R.id.webview);

        WebSettings webSettings = webView.getSettings();
        webSettings.setJavaScriptEnabled(true);
        webSettings.setDomStorageEnabled(true);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                String cookiesString = CookieManager.getInstance().getCookie(url);

                if (cookiesString != null) {
                    Log.d("Login", "Current Domain: " + url);

                    // Wait until the redirection arrives back on the main youtube domain
                    if (url.contains("youtube.com") && cookiesString.contains("SID=")) {
                        Log.d("Login", "Authentication cookies successfully located.");

                        // 1. Build the Netscape header mandatory for yt-dlp
                        StringBuilder netscapeCookieBuffer = new StringBuilder();
                        netscapeCookieBuffer.append("# Netscape HTTP Cookie File\n");
                        netscapeCookieBuffer.append("# This is a generated file! Do not edit.\n\n");

                        // 2. Parse individual cookies split by semicolons
                        String[] cookieArray = cookiesString.split(";");
                        for (String cookie : cookieArray) {
                            String[] pair = cookie.trim().split("=", 2);
                            if (pair.length == 2) {
                                String name = pair[0].trim();
                                String value = pair[1].trim();

                                // Skip malformed fields or problematic session headers causing warnings
                                if (name.contains("__Host-GAPS")) continue;

                                // 3. Populate columns using tabular formats (\t)
                                // Target domain | Access subdomains | Path | Secure connection | Expiration timestamp | Name | Value
                                netscapeCookieBuffer.append(".youtube.com\tTRUE\t/\tTRUE\t2147483647\t")
                                        .append(name).append("\t")
                                        .append(value).append("\n");
                            }
                        }

                        // 4. Save the cleanly reformatted string array to disk
                        FileLoader.writeFile(getApplicationContext(), "cookies.txt", netscapeCookieBuffer.toString().getBytes());

                        // Redirect to HomeActivity
                        Intent intent = new Intent(LoginActivity.this, HomeActivity.class);
                        startActivity(intent);
                        finish();
                    }
                }
            }
        });


        // Open your specific sign-in portal
        webView.loadUrl("https://accounts.google.com/ServiceLogin?service=youtube&uilel=3&passive=true&continue=https%3A%2F%2Fwww.youtube.com%2Fsignin%3Faction_handle_signin%3Dtrue%26app%3Ddesktop%26hl%3Den%26next%3Dhttps%253A%252F%252Fwww.youtube.com%252F&hl=en");
    }
}