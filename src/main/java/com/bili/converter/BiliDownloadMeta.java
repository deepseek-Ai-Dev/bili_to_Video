package com.bili.converter;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class BiliDownloadMeta {
    public String type;
    public int codecid;
    public String groupId;
    public long itemId;
    public long aid;
    public long cid;
    public String bvid;
    public int p;
    public int tabP;
    public String tabName;
    public String uid;
    public String uname;
    public String avatar;
    public String coverUrl;
    public String title;
    public int duration;
    public String groupTitle;
    public String groupCoverUrl;
    public int danmaku;
    public long view;
    public long pubdate;
    public int vt;
    public String status;
    public boolean active;
    public boolean loaded;
    public int qn;
    public boolean allowHEVC;
    public long createTime;
    public String coverPath;
    public String groupCoverPath;
    public long updateTime;
    public long totalSize;
    public long loadedSize;
    public int progress;
    public long speed;
    public long completionTime;
    public long reportedSize;
    public int groupOrder;
}