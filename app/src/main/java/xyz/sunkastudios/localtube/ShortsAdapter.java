package xyz.sunkastudios.localtube;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.media3.ui.PlayerView;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;

import java.util.List;

public class ShortsAdapter extends RecyclerView.Adapter<ShortsAdapter.ShortViewHolder> {

    public interface VideoAdapterListener {
        void onVideoClick(ShortItem video);
    }

    public static class ShortViewHolder extends RecyclerView.ViewHolder {
        public TextView nameView;
        public ImageView thumbnailView;
        public PlayerView playerView;
        public ImageView playPauseIcon;
        public ProgressBar bufferingSpinner;
        public View uiContainer;
        public View clickOverlay;
        public SeekBar seekBar;

        public ShortViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.videoName);
            thumbnailView = itemView.findViewById(R.id.videoThumbnail);
            playerView = itemView.findViewById(R.id.playerView);
            playPauseIcon = itemView.findViewById(R.id.ic_play_pause);
            bufferingSpinner = itemView.findViewById(R.id.bufferingSpinner);
            uiContainer = itemView.findViewById(R.id.uiContainer);
            clickOverlay = itemView.findViewById(R.id.clickOverlay);
            seekBar = itemView.findViewById(R.id.playerSeekBar);
        }
    }

    private final List<ShortItem> videoList;
    private final VideoAdapterListener listener;

    public ShortsAdapter(List<ShortItem> videoList) {
        this(videoList, null);
    }

    public ShortsAdapter(List<ShortItem> videoList, VideoAdapterListener listener) {
        this.videoList = videoList;
        this.listener = listener;
    }

    @NonNull
    @Override
    public ShortViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_short, parent, false);
        return new ShortViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ShortViewHolder holder, int position) {
        ShortItem currentVideo = videoList.get(position);
        if (currentVideo != null) {
            Context context = holder.itemView.getContext();
            
            // Reset state for recycled view
            holder.thumbnailView.setVisibility(View.VISIBLE);
            holder.playerView.setPlayer(null); // Clear player from recycled view
            holder.playPauseIcon.setVisibility(View.GONE);
            holder.bufferingSpinner.setVisibility(View.GONE);
            holder.uiContainer.setVisibility(View.VISIBLE);
            holder.seekBar.setVisibility(View.GONE);
            holder.nameView.setText(currentVideo.getTitle());
            
            String fallbackUrl = "https://i.ytimg.com/vi/" + currentVideo.getVideoId() + "/hqdefault.jpg";

            Glide.with(context)
                    .load(currentVideo.getThumbnail())
                    .placeholder(R.drawable.icon_hourglass)
                    .error(Glide.with(context).load(fallbackUrl))
                    .into(holder.thumbnailView);

            holder.itemView.setOnClickListener(v -> {
                if (listener != null) {
                    listener.onVideoClick(currentVideo);
                }
            });
        }
    }

    @Override
    public void onViewDetachedFromWindow(@NonNull ShortViewHolder holder) {
        super.onViewDetachedFromWindow(holder);
        holder.playerView.setPlayer(null);
    }

    @Override
    public void onViewRecycled(@NonNull ShortViewHolder holder) {
        super.onViewRecycled(holder);
        holder.playerView.setPlayer(null);
    }

    @Override
    public int getItemCount() {
        return videoList != null ? videoList.size() : 0;
    }
}
