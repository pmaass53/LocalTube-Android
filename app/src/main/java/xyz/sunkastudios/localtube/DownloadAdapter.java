package xyz.sunkastudios.localtube;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.bumptech.glide.Glide;
import com.google.android.material.button.MaterialButton;

import java.io.File;
import java.util.List;
import java.util.Locale;

public class DownloadAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    public interface DownloadAdapterListener {
        void onFolderClick(String folderName);
        void onVideoClick(DownloadedVideo video);
        void onDeleteClick(DownloadedVideo video);
        void onFolderDeleteClick(String folderName);
    }

    private final List<DownloadListItem> itemList;
    private final DownloadAdapterListener listener;

    public DownloadAdapter(List<DownloadListItem> itemList, DownloadAdapterListener listener) {
        this.itemList = itemList;
        this.listener = listener;
    }

    public static class VideoViewHolder extends RecyclerView.ViewHolder {
        public TextView nameView;
        public ImageView thumbnailView;
        public TextView sizeView;
        public MaterialButton deleteView;
        public View mainContent;
        public View dragHandle;
        public View dragIcon;

        public VideoViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.videoName);
            thumbnailView = itemView.findViewById(R.id.videoThumbnail);
            sizeView = itemView.findViewById(R.id.fileSize);
            deleteView = itemView.findViewById(R.id.btnDelete);
            mainContent = itemView.findViewById(R.id.mainContent);
            dragHandle = itemView.findViewById(R.id.dragHandle);
            dragIcon = itemView.findViewById(R.id.dragIcon);
        }

        public void setDragging(boolean dragging) {
            if (mainContent != null) mainContent.setVisibility(dragging ? View.INVISIBLE : View.VISIBLE);
            if (dragIcon != null) dragIcon.setVisibility(dragging ? View.VISIBLE : View.GONE);
        }
    }

    public static class FolderViewHolder extends RecyclerView.ViewHolder {
        public TextView nameView;
        public MaterialButton deleteView;
        public View mainContent;
        public View dragIcon;

        public FolderViewHolder(@NonNull View itemView) {
            super(itemView);
            nameView = itemView.findViewById(R.id.folderName);
            deleteView = itemView.findViewById(R.id.btnDelete);
            mainContent = itemView.findViewById(R.id.mainContent);
            dragIcon = itemView.findViewById(R.id.dragIcon);
        }

        public void setDragging(boolean dragging) {
            if (mainContent != null) mainContent.setVisibility(dragging ? View.INVISIBLE : View.VISIBLE);
            if (dragIcon != null) dragIcon.setVisibility(dragging ? View.VISIBLE : View.GONE);
        }
    }

    @Override
    public int getItemViewType(int position) {
        return itemList.get(position).getType();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        if (viewType == DownloadListItem.TYPE_FOLDER) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.folder_card, parent, false);
            return new FolderViewHolder(view);
        } else {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.download_card, parent, false);
            return new VideoViewHolder(view);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof VideoViewHolder) {
            ((VideoViewHolder) holder).setDragging(false);
        } else if (holder instanceof FolderViewHolder) {
            ((FolderViewHolder) holder).setDragging(false);
        }

        DownloadListItem item = itemList.get(position);
        if (item.getType() == DownloadListItem.TYPE_FOLDER) {
            FolderViewHolder folderHolder = (FolderViewHolder) holder;
            folderHolder.nameView.setText(item.getFolderName());
            folderHolder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onFolderClick(item.getFolderName());
            });

            if (item.getFolderName().equals("..")) {
                folderHolder.deleteView.setVisibility(View.GONE);
            } else {
                folderHolder.deleteView.setVisibility(View.VISIBLE);
                folderHolder.deleteView.setOnClickListener(v -> {
                    if (listener != null) listener.onFolderDeleteClick(item.getFolderName());
                });
            }
        } else {
            VideoViewHolder videoHolder = (VideoViewHolder) holder;
            DownloadedVideo currentVideo = item.getVideo();
            Context context = holder.itemView.getContext();

            if (currentVideo.getThumbnailFilePath() != null) {
                String fallbackUrl = "https://i.ytimg.com/vi/" + currentVideo.getVideoId() + "/hqdefault.jpg";
                Glide.with(context)
                        .load(new File(currentVideo.getThumbnailFilePath()))
                        .placeholder(R.drawable.icon_hourglass)
                        .error(Glide.with(context).load(fallbackUrl).centerCrop())
                        .centerCrop()
                        .into(videoHolder.thumbnailView);
            }

            videoHolder.nameView.setText(currentVideo.getTitle());
            videoHolder.sizeView.setText(getFormattedFileSize(currentVideo));

            videoHolder.itemView.setOnClickListener(v -> {
                if (listener != null) listener.onVideoClick(currentVideo);
            });

            videoHolder.deleteView.setOnClickListener(v -> {
                if (listener != null) listener.onDeleteClick(currentVideo);
            });
        }
    }

    private String getFormattedFileSize(DownloadedVideo item) {
        long totalBytes = 0;
        if (item.getVideoFilePath() != null) {
            File vFile = new File(item.getVideoFilePath());
            if (vFile.exists()) totalBytes += vFile.length();
        }
        if (item.getAudioFilePath() != null) {
            File aFile = new File(item.getAudioFilePath());
            if (aFile.exists()) totalBytes += aFile.length();
        }
        if (totalBytes <= 0) return "0 MB";
        double sizeInMb = totalBytes / (1024.0 * 1024.0);
        if (sizeInMb >= 1024) {
            return String.format(Locale.getDefault(), "%.2f GB", sizeInMb / 1024.0);
        } else {
            return String.format(Locale.getDefault(), "%.1f MB", sizeInMb);
        }
    }

    @Override
    public int getItemCount() {
        return itemList != null ? itemList.size() : 0;
    }
}
