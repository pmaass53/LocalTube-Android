package xyz.sunkastudios.localtube;

import com.google.gson.annotations.SerializedName;

public class Thumbnail {
    @SerializedName("url")
    public String url;

    @SerializedName("width")
    public Integer width;

    @SerializedName("height")
    public Integer height;

    public String getUrl() { return url; }
    public Integer getWidth() { return width != null ? width : 0; }
    public Integer getHeight() { return height != null ? height : 0; }
}