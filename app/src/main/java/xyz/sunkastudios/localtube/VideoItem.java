package xyz.sunkastudios.localtube;

import com.google.gson.annotations.SerializedName;
import java.util.List;

public class VideoItem {
    @SerializedName("title") private String title;
    @SerializedName("thumbnail") private String thumbnail;
    @SerializedName("uploader") private String uploader;
    @SerializedName("view_count") private Long viewCount;
    @SerializedName("upload_date") private String uploadDate;
    @SerializedName("thumbnails") private List<Thumbnail> thumbnails;
    @SerializedName("id") private String id;
    @SerializedName("url") private String url;
    @SerializedName("description") private String description;

    @SerializedName("banner") private String banner;
    @SerializedName("cover_large") private String coverLarge;

    public String getTitle() {
        return title != null ? title : "Unknown Title";
    }

    public boolean isYoutube() {
        if (url == null || url.isEmpty()) {
            // If we have an ID but no URL, assume it's YouTube (legacy/common behavior in this app)
            return id != null && !id.isEmpty();
        }
        return url.contains("youtube.com") || url.contains("youtu.be") || url.contains("watch?v=");
    }

    /**
     * Best-guess YouTube thumbnail from yt-dlp's own metadata. This can be a dead/404 URL
     * for some videos -- callers should chain getYoutubeFallbackThumbnail() as a Glide
     * .error() target rather than trusting this alone.
     */
    public String getYoutubeThumbnail() {
        if (thumbnails != null && !thumbnails.isEmpty()) {
            String url = thumbnails.get(thumbnails.size() - 1).getUrl();
            if (url != null && !url.isEmpty()) return url;
        }
        if (thumbnail != null && !thumbnail.isEmpty()) {
            return thumbnail;
        }
        return getYoutubeFallbackThumbnail();
    }

    /**
     * Deterministically constructed thumbnail URL that only depends on the video ID,
     * not on any metadata yt-dlp returned. Use as the Glide .error() fallback target
     * for getYoutubeThumbnail() -- this is what "used to work" before the fields above
     * were trusted unconditionally.
     */
    public String getYoutubeFallbackThumbnail() {
        if (id != null && !id.isEmpty()) {
            return "https://img.youtube.com/vi/" + id + "/hqdefault.jpg";
        }
        return "";
    }

    /**
     * @deprecated kept for backwards compatibility -- routes YouTube items to
     * getYoutubeThumbnail() and anime items to getAnimeThumbnail(). Prefer calling
     * the specific method directly (e.g. in VideoAdapter) so you can wire up
     * Glide-level fallback chaining per content type.
     */
    @Deprecated
    public String getThumbnail() {
        if (isYoutube()) {
            return getYoutubeThumbnail();
        }
        return getAnimeThumbnail();
    }

    /** Primary anime thumbnail: cover art (2:3 poster), matching current card aspect ratio. */
    public String getAnimeThumbnail() {
        if (coverLarge != null && !coverLarge.isEmpty()) return coverLarge;
        return "";
    }

    /** Fallback for anime thumbnails if cover art fails or is missing. */
    public String getAnimeFallbackThumbnail() {
        if (banner != null && !banner.isEmpty()) return banner;
        if (thumbnail != null && !thumbnail.isEmpty()) return thumbnail;
        return "";
    }

    /** @deprecated use getAnimeThumbnail() -- kept so existing call sites don't break. */
    @Deprecated
    public String getAnimePoster() {
        return getAnimeThumbnail();
    }

    public String getUploader() {
        return uploader != null ? uploader : "";
    }

    public Long getViewCount() {
        return viewCount != null ? viewCount : 0L;
    }

    public String getUploadDate() {
        return uploadDate != null ? uploadDate : "";
    }

    public String getDescription() {
        return description != null ? description : "";
    }

    public String getId() {
        return id;
    }

    public String getUrl() {
        if (url != null && !url.isEmpty()) {
            if (!isYoutube()) return url;

            // Case 1: Handle Mix Playlist URLs (/playlist?list=RD...)
            if (url.contains("list=RD")) {
                int rdIndex = url.indexOf("list=RD");
                String mixId = url.substring(rdIndex + 5);
                if (mixId.contains("&")) mixId = mixId.split("&")[0];
                String videoId = mixId.startsWith("RD") ? mixId.substring(2) : mixId;
                if (videoId.length() > 11) videoId = videoId.substring(0, 11);
                return "https://www.youtube.com/watch?v=" + videoId + "&list=" + mixId;
            }

            // Case 2: Handle Standard Playlist URLs (/playlist?list=PL...)
            if (url.contains("playlist?list=")) {
                String listId = url.substring(url.indexOf("list=") + 5);
                if (listId.contains("&")) listId = listId.split("&")[0];
                if (id != null && !id.isEmpty()) {
                    return "https://www.youtube.com/watch?v=" + id + "&list=" + listId;
                }
            }

            // Case 3: Standard /watch URLs
            if (url.contains("watch?v=")) return url;
        }

        // Fallback: Default to standard watch URL using item ID
        if (id != null && !id.isEmpty() && isYoutube()) {
            return "https://www.youtube.com/watch?v=" + id;
        }

        return url != null ? url : "";
    }

    public void setUrl(String url) { this.url = url; }
    public void setTitle(String title) { this.title = title; }
    public void setId(String id) { this.id = id; }
    public void setThumbnail(String thumbnail) { this.thumbnail = thumbnail; }
    public void setUploader(String uploader) { this.uploader = uploader; }
    public void setBanner(String banner) { this.banner = banner; }
    public void setCoverLarge(String coverLarge) { this.coverLarge = coverLarge; }
    public void setDescription(String description) { this.description = description; }
}
