package xyz.sunkastudios.localtube.util;

import java.util.ArrayList;
import java.util.List;

import xyz.sunkastudios.localtube.ShortItem;
import xyz.sunkastudios.localtube.VideoItem;

public class PrefetchManager {
    private static List<ShortItem> prefetchedShorts = null;
    private static List<VideoItem> prefetchedAnimeHome = null;

    public static synchronized void setPrefetchedShorts(List<ShortItem> shorts) {
        prefetchedShorts = shorts;
    }

    public static synchronized List<ShortItem> getAndClearPrefetchedShorts() {
        List<ShortItem> temp = prefetchedShorts;
        prefetchedShorts = null;
        return temp;
    }

    public static synchronized void setPrefetchedAnimeHome(List<VideoItem> anime) {
        prefetchedAnimeHome = anime;
    }

    public static synchronized List<VideoItem> getAndClearPrefetchedAnimeHome() {
        List<VideoItem> temp = prefetchedAnimeHome;
        prefetchedAnimeHome = null;
        return temp;
    }
}
