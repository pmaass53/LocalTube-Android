package xyz.sunkastudios.localtube;

import com.google.gson.annotations.SerializedName;

public class FormatItem {
    @SerializedName("format_id") private String formatId;
    @SerializedName("url") private String url;
    @SerializedName("ext") private String ext;
    @SerializedName("protocol") private String protocol;
    @SerializedName("vcodec") private String vcodec;
    @SerializedName("acodec") private String acodec;
    @SerializedName("height") private Integer height;
    @SerializedName("tbr") private Double tbr;
    @SerializedName("format_note") private String formatNote;
    @SerializedName("language") private String language;
    @SerializedName("filesize") private Long filesize;
    @SerializedName("filesize_approx") private Long filesizeApprox;

    public boolean isVideoOnly() {
        return vcodec != null && !vcodec.equals("none") && (acodec == null || acodec.equals("none"));
    }
    public boolean isAudioOnly() {
        return acodec != null && !acodec.equals("none") && (vcodec == null || vcodec.equals("none"));
    }
    public boolean isCombined() {
        return vcodec != null && !vcodec.equals("none") && acodec != null && !acodec.equals("none");
    }
    public String getFormatId() {
        return formatId;
    }
    public String getUrl() {
        return url;
    }
    public String getExt() {
        return ext;
    }
    public String getProtocol() {
        return protocol;
    }
    public String getVcodec() {
        return vcodec;
    }
    public String getAcodec() {
        return acodec;
    }
    public Integer getHeight() {
        return height != null ? height : 0;
    }
    public Double getTbr() {
        return tbr != null ? tbr : 0.0;
    }
    public String getFormatNote() {
        return formatNote;
    }
    public String getLanguage() {
        return language;
    }
    public long getFilesize() {
        if (filesize != null) return filesize;
        if (filesizeApprox != null) return filesizeApprox;
        return 0L;
    }

    public boolean isDirectStream() {
        if (getUrl() == null) return false;
        String protocol = getProtocol();
        if (protocol != null && (protocol.contains("m3u8") || protocol.contains("dash"))) {
            return false;
        }
        return getUrl().startsWith("http");
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        if (isVideoOnly()) {
            sb.append(getHeight()).append("p");
            if (formatNote != null) sb.append(" (").append(formatNote).append(")");
        } else if (isAudioOnly()) {
            sb.append(getAcodec());
            if (language != null) sb.append(" [").append(language).append("]");
            if (getTbr() > 0) sb.append(" ").append(Math.round(getTbr())).append("kbps");
        } else {
            sb.append(getHeight()).append("p (Combined)");
        }
        sb.append(" .").append(getExt());
        return sb.toString();
    }
}
