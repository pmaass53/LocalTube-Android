package xyz.sunkastudios.localtube;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class VideoMetaData {
    @SerializedName("id") private String id;
    @SerializedName("title") private String title;
    @SerializedName("formats") private List<FormatItem> formats;
    @SerializedName("webpage_url") private String webpage_url;
    @SerializedName("dash_url") private String dashUrl;
    @SerializedName("hls_url") private String hlsUrl;

    public String getId() { return id; }
    public String getTitle() { return title; }
    public List<FormatItem> getFormats() { return formats; }
    public String getWebpageUrl() { return webpage_url; }
    public String getDashUrl() { return dashUrl; }
    public String getHlsUrl() { return hlsUrl; }
}