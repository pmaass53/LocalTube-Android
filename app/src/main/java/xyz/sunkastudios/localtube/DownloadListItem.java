package xyz.sunkastudios.localtube;

public class DownloadListItem {
    public static final int TYPE_VIDEO = 0;
    public static final int TYPE_FOLDER = 1;

    private int type;
    private String folderName;
    private DownloadedVideo video;

    private DownloadListItem(int type, String folderName, DownloadedVideo video) {
        this.type = type;
        this.folderName = folderName;
        this.video = video;
    }

    public static DownloadListItem forFolder(String name) {
        return new DownloadListItem(TYPE_FOLDER, name, null);
    }

    public static DownloadListItem forVideo(DownloadedVideo video) {
        return new DownloadListItem(TYPE_VIDEO, null, video);
    }

    public int getType() { return type; }
    public String getFolderName() { return folderName; }
    public DownloadedVideo getVideo() { return video; }
}
