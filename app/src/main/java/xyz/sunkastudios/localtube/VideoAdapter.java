package xyz.sunkastudios.localtube;

import android.content.Context;
import android.content.Intent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;
import com.google.android.material.button.MaterialButton;

import xyz.sunkastudios.localtube.activity.DownloaderActivity;
import xyz.sunkastudios.localtube.activity.AnimeActivity;
import xyz.sunkastudios.localtube.activity.PlayerActivity;
import xyz.sunkastudios.localtube.activity.PlaylistActivity;

public class VideoAdapter extends RecyclerView.Adapter<VideoAdapter.VideoViewHolder> {

    private static final int TYPE_YOUTUBE = 0;
    private static final int TYPE_ANIME = 1;
    private static final int TYPE_EPISODE = 2;

    public interface VideoAdapterListener {
        void onVideoClick(VideoItem video);
        void onDownloadClick(VideoItem video);
    }

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        public TextView nameView;
        public ImageView thumbnailView;
        public TextView uploaderView;
        public TextView viewsView;
        public TextView uploadedView;
        public MaterialButton downloadView;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.videoName);
            thumbnailView = itemView.findViewById(R.id.videoThumbnail);
            uploaderView = itemView.findViewById(R.id.videoUploader);
            viewsView = itemView.findViewById(R.id.videoViews);
            uploadedView = itemView.findViewById(R.id.videoUploadDate);
            downloadView = itemView.findViewById(R.id.btnDownload);
        }
    }

    private final List<VideoItem> videoList;
    private final VideoAdapterListener listener;

    public VideoAdapter(List<VideoItem> videoList) {
        this(videoList, null);
    }

    public VideoAdapter(List<VideoItem> videoList, VideoAdapterListener listener) {
        this.videoList = videoList;
        this.listener = listener;
    }

    @Override
    public int getItemViewType(int position) {
        VideoItem item = videoList.get(position);
        if (item != null && !item.isYoutube()) {
            String url = item.getUrl();
            if (url != null && url.contains("/ep/")) {
                return TYPE_EPISODE;
            }
            return TYPE_ANIME;
        }
        return TYPE_YOUTUBE;
    }

    @NonNull
    @Override
    public VideoViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId;
        if (viewType == TYPE_EPISODE) {
            layoutId = R.layout.episode_card;
        } else if (viewType == TYPE_ANIME) {
            layoutId = R.layout.anime_card;
        } else {
            layoutId = R.layout.video_card;
        }
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new VideoViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull VideoViewHolder holder, int position) {
        VideoItem currentVideo = videoList.get(position);
        if (currentVideo != null) {
            Context context = holder.itemView.getContext();
            int viewType = getItemViewType(position);

            if (viewType == TYPE_EPISODE) {
                // For episodes, just show the play icon and name
                if (holder.thumbnailView != null) {
                    holder.thumbnailView.setImageResource(R.drawable.icon_play);
                    holder.thumbnailView.setAlpha(0.7f);
                }
                
                if (holder.downloadView != null) {
                    holder.downloadView.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onDownloadClick(currentVideo);
                        } else {
                            Toast.makeText(context, "Episode downloads coming soon", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            } else if (viewType == TYPE_ANIME) {
                String thumbUrl = currentVideo.getAnimeThumbnail();
                if (thumbUrl != null && !thumbUrl.isEmpty()) {
                    Glide.with(context)
                            .load(thumbUrl)
                            .placeholder(R.drawable.icon_hourglass)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .error(
                                    Glide.with(context)
                                            .load(currentVideo.getAnimeFallbackThumbnail())
                                            .centerCrop()
                                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                                            .error(R.drawable.icon_error)
                            )
                            .centerCrop()
                            .into(holder.thumbnailView);
                } else {
                    if (holder.thumbnailView != null) holder.thumbnailView.setImageResource(R.drawable.icon_hourglass);
                }
            } else {
                String youtubeThumb = currentVideo.getYoutubeThumbnail();
                if (youtubeThumb != null && !youtubeThumb.isEmpty()) {
                    Glide.with(context)
                            .load(youtubeThumb)
                            .placeholder(R.drawable.icon_hourglass)
                            .transition(DrawableTransitionOptions.withCrossFade())
                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                            .error(
                                    Glide.with(context)
                                            .load(currentVideo.getYoutubeFallbackThumbnail())
                                            .diskCacheStrategy(DiskCacheStrategy.ALL)
                                            .centerCrop()
                                            .error(R.drawable.icon_error)
                            )
                            .centerCrop()
                            .into(holder.thumbnailView);
                } else {
                    if (holder.thumbnailView != null) holder.thumbnailView.setImageResource(R.drawable.icon_hourglass);
                }
            }
            
            holder.nameView.setText(currentVideo.getTitle());
            if (holder.uploaderView != null) holder.uploaderView.setText(currentVideo.getUploader());

            if (viewType == TYPE_YOUTUBE) {
                if (holder.viewsView != null) holder.viewsView.setText(formatNumber(currentVideo.getViewCount() == null ? 0L : currentVideo.getViewCount()));
                if (holder.uploadedView != null) holder.uploadedView.setText(currentVideo.getUploadDate());

                if (holder.downloadView != null) {
                    holder.downloadView.setOnClickListener(v -> {
                        if (listener != null) {
                            listener.onDownloadClick(currentVideo);
                        } else {
                            Intent intent = new Intent(context, DownloaderActivity.class);
                            intent.putExtra("video_url", currentVideo.getUrl());
                            intent.putExtra("video_id", currentVideo.getId());
                            intent.putExtra("video_title", currentVideo.getTitle());
                            context.startActivity(intent);
                        }
                    });
                }
            }

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoClick(currentVideo);
                } else {
                    String url = currentVideo.getUrl();
                    if (viewType == TYPE_ANIME) {
                        Intent intent = new Intent(context, AnimeActivity.class);
                        intent.putExtra("anime_id", currentVideo.getId());
                        intent.putExtra("anime_title", currentVideo.getTitle());
                        intent.putExtra("thumbnail_url", currentVideo.getAnimeThumbnail());
                        context.startActivity(intent);
                        return;
                    }
                    Intent intent = new Intent(context, url != null && url.contains("list=") ? PlaylistActivity.class : PlayerActivity.class);
                    intent.putExtra("online", true);
                    intent.putExtra("video_uri", url);
                    intent.putExtra("video_id", currentVideo.getId());
                    context.startActivity(intent);
                }
            });
        }
    }

    public String formatNumber(long number) {
        if (number < 1000) return String.valueOf(number);
        if (number < 1000000) return String.format(java.util.Locale.getDefault(), "%.1fK", number / 1000.0);
        if (number < 1000000000) return String.format(java.util.Locale.getDefault(), "%.1fM", number / 1000000.0);
        return String.format(java.util.Locale.getDefault(), "%.1fB", number / 1000000000.0);
    }

    @Override
    public int getItemCount() {
        return videoList != null ? videoList.size() : 0;
    }
}
