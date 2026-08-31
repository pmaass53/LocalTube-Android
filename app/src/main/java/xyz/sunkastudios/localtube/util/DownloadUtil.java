package xyz.sunkastudios.localtube.util;

import android.content.Context;

import androidx.annotation.OptIn;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.database.DatabaseProvider;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.datasource.DataSpec;
import androidx.media3.datasource.cache.CacheWriter;
import android.net.Uri;
import android.util.Log;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@OptIn(markerClass = UnstableApi.class)
public class DownloadUtil {

    private static SimpleCache cache;
    private static DatabaseProvider databaseProvider;
    private static CacheDataSource.Factory cacheDataSourceFactory;
    private static final ExecutorService prefetchExecutor = Executors.newFixedThreadPool(2);

    public static synchronized SimpleCache getCache(Context context) {
        if (cache == null) {
            File cacheDir = new File(context.getExternalCacheDir(), "exoplayer_cache");
            LeastRecentlyUsedCacheEvictor evictor = new LeastRecentlyUsedCacheEvictor(200 * 1024 * 1024); // 200MB
            databaseProvider = new StandaloneDatabaseProvider(context);
            cache = new SimpleCache(cacheDir, evictor, databaseProvider);
        }
        return cache;
    }

    public static synchronized CacheDataSource.Factory getCacheDataSourceFactory(Context context, DataSource.Factory httpDataSourceFactory) {
        if (cacheDataSourceFactory == null) {
            cacheDataSourceFactory = new CacheDataSource.Factory()
                    .setCache(getCache(context))
                    .setUpstreamDataSourceFactory(httpDataSourceFactory)
                    .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
        }
        return cacheDataSourceFactory;
    }

    public static void preCache(Context context, String url, long length) {
        if (url == null) return;
        prefetchExecutor.execute(() -> {
            try {
                SimpleCache cache = getCache(context);
                DataSource dataSource = new DefaultHttpDataSource.Factory().createDataSource();
                DataSpec dataSpec = new DataSpec.Builder()
                        .setUri(Uri.parse(url))
                        .setPosition(0)
                        .setLength(length)
                        .build();

                Log.d("DownloadUtil", "Pre-caching " + length + " bytes for: " + url);
                CacheWriter cacheWriter = new CacheWriter(
                        new CacheDataSource(cache, dataSource),
                        dataSpec,
                        null,
                        null
                );
                cacheWriter.cache();
                Log.d("DownloadUtil", "Pre-caching finished for: " + url);
            } catch (Exception e) {
                Log.e("DownloadUtil", "Pre-caching failed for: " + url, e);
            }
        });
    }
}
