package xyz.sunkastudios.localtube;

import com.google.gson.annotations.SerializedName;

public class ShortItem {
    @SerializedName("videoId") private String videoId;
    @SerializedName("url") private String url;
    @SerializedName("thumbnailUrl") private String thumbnailUrl;
    @SerializedName("playerParams") private String playerParams;
    @SerializedName("params") private String params;

    private String title;
    private String resolvedVideoUrl;
    private String resolvedAudioUrl;
    private String videoMimeType;
    private String audioMimeType;

    public String getVideoId() { return videoId; }
    public String getUrl() { return url; }
    public String getThumbnail() { return thumbnailUrl; }
    public String getPlayerParams() { return playerParams; }
    public String getParams() { return params; }

    public String getTitle() { return title != null ? title : (videoId != null ? videoId : "Short"); }
    public void setTitle(String title) { this.title = title; }

    public String getResolvedVideoUrl() { return resolvedVideoUrl; }
    public String getResolvedAudioUrl() { return resolvedAudioUrl; }
    public void setResolvedUrls(String videoUrl, String audioUrl) {
        this.resolvedVideoUrl = videoUrl;
        this.resolvedAudioUrl = audioUrl;
    }

    public String getVideoMimeType() { return videoMimeType; }
    public void setVideoMimeType(String videoMimeType) { this.videoMimeType = videoMimeType; }

    public String getAudioMimeType() { return audioMimeType; }
    public void setAudioMimeType(String audioMimeType) { this.audioMimeType = audioMimeType; }

    public void setVideoId(String videoId) { this.videoId = videoId; }
    public void setUrl(String url) { this.url = url; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }
}
