package xyz.sunkastudios.localtube;

public class DownloadedVideo {
    public String videoId;
    public String title;
    public String videoFilePath;
    public String audioFilePath;
    public String thumbnailFilePath;
    public String folder = "";
    public boolean isPlaceholder = false; // To support empty folders

    public boolean isWatched = false;
    public long lastPosition = 0;

    public String getAudioFilePath() { return audioFilePath; }
    public String getVideoId() { return videoId; }
    public String getThumbnailFilePath() { return thumbnailFilePath; }
    public String getVideoFilePath() { return videoFilePath; }
    public String getTitle() { return title; }
    public String getFolder() { return folder != null ? folder : ""; }
    public boolean isPlaceholder() { return isPlaceholder; }

    public boolean isWatched() { return isWatched; }
    public void setWatched(boolean watched) { isWatched = watched; }

    public long getLastPosition() { return lastPosition; }
    public void setLastPosition(long lastPosition) { this.lastPosition = lastPosition; }

    public String getVideoMimeType() {
        return inferMimeType(videoFilePath);
    }

    public String getAudioMimeType() {
        return inferMimeType(audioFilePath);
    }

    private String inferMimeType(String path) {
        if (path == null) return null;
        String lower = path.toLowerCase();
        if (lower.endsWith(".webm")) return "video/webm"; // Or audio/webm for m4a? 
        if (lower.endsWith(".m4a")) return "audio/mp4";
        if (lower.endsWith(".mp4")) return "video/mp4";
        if (lower.endsWith(".mp3")) return "audio/mpeg";
        return null;
    }

    public static DownloadedVideo fromShortItem(ShortItem shortItem, String videoFilePath, String audioFilePath, String thumbnailFilePath) {
        DownloadedVideo video = new DownloadedVideo();
        video.videoId = shortItem.getVideoId();
        video.title = "Short " + shortItem.getVideoId(); // Or fetch title if available
        video.videoFilePath = videoFilePath;
        video.audioFilePath = audioFilePath;
        video.thumbnailFilePath = thumbnailFilePath;
        video.isWatched = false;
        video.lastPosition = 0;
        return video;
    }
}