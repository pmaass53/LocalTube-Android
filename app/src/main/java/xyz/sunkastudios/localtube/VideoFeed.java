package xyz.sunkastudios.localtube;

import java.util.List;

public class VideoFeed {
    private List<VideoItem> entries;

    public List<VideoItem> getEntries() {
        return entries;
    }

    public void setEntries(List<VideoItem> entries) {
        this.entries = entries;
    }
}
