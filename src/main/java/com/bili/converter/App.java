package com.bili.converter;

import com.fasterxml.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.LineBorder;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends JFrame {

    private static final String CONFIG_FILE = "config.properties";
    private static final String KEY_LAST_DIR = "last_cache_dir";

    private JPanel videoGrid;
    private JLabel statusLabel;
    private final ObjectMapper mapper = new ObjectMapper();
    // 使用缓存线程池处理图片加载，避免任务积压
    private final ExecutorService imageLoader = Executors.newCachedThreadPool();
    private volatile boolean isConverting = false;

    public App() {
        setTitle("BiliToVideo 缓存转换器");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(900, 700);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        getContentPane().setBackground(new Color(245, 245, 245));

        // 1. 顶部工具栏
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 10));
        topPanel.setBackground(new Color(255, 255, 255));
        topPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(220, 220, 220)));

        JButton selectDirBtn = new JButton("选择哔哩哔哩缓存目录");
        selectDirBtn.setFocusPainted(false);
        selectDirBtn.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        selectDirBtn.addActionListener(e -> selectDirectory());

        JButton openMp4Btn = new JButton("打开 mp4 文件夹");
        openMp4Btn.setFocusPainted(false);
        openMp4Btn.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        openMp4Btn.addActionListener(e -> openMp4Folder());

        topPanel.add(selectDirBtn);
        topPanel.add(openMp4Btn);

        // 2. 中间视频展示区
        videoGrid = new JPanel(new WrapLayout(FlowLayout.LEFT, 15, 15));
        videoGrid.setBackground(new Color(245, 245, 245));

        JScrollPane scrollPane = new JScrollPane(videoGrid);
        scrollPane.setBorder(null);
        scrollPane.getVerticalScrollBar().setUnitIncrement(20);
        scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

        // 3. 底部状态栏
        statusLabel = new JLabel("就绪");
        statusLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        statusLabel.setBorder(new EmptyBorder(8, 15, 8, 15));
        statusLabel.setForeground(new Color(100, 100, 100));

        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.setBackground(new Color(255, 255, 255));
        bottomPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(220, 220, 220)));
        bottomPanel.add(statusLabel, BorderLayout.CENTER);

        add(topPanel, BorderLayout.NORTH);
        add(scrollPane, BorderLayout.CENTER);
        add(bottomPanel, BorderLayout.SOUTH);

        // 4. 启动时尝试加载上次的目录
        loadLastDirectory();
    }

    private void loadLastDirectory() {
        String lastPath = loadConfig();
        if (lastPath != null) {
            File dir = new File(lastPath);
            if (dir.exists() && dir.isDirectory()) {
                statusLabel.setText("正在加载上次的缓存目录...");
                SwingUtilities.invokeLater(() -> loadDirectory(dir));
            }
        }
    }

    private void selectDirectory() {
        String dirPath = null;
        String os = System.getProperty("os.name").toLowerCase();

        if (os.contains("win")) {
            dirPath = NativeFileDialog.chooseFolder(this, "选择缓存视频根目录");
        } else {
            JFileChooser fileChooser = new JFileChooser();
            fileChooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            String lastPath = loadConfig();
            if (lastPath != null) {
                File lastDir = new File(lastPath);
                if (lastDir.exists()) {
                    fileChooser.setCurrentDirectory(lastDir);
                }
            }
            if (fileChooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                dirPath = fileChooser.getSelectedFile().getAbsolutePath();
            }
        }

        if (dirPath != null) {
            saveConfig(dirPath);
            loadDirectory(new File(dirPath));
        }
    }

    private void loadDirectory(File selectedDirectory) {
        videoGrid.removeAll();
        videoGrid.repaint();
        statusLabel.setText("正在扫描目录，请稍候...");

        new SwingWorker<List<CacheItem>, Void>() {
            @Override
            protected List<CacheItem> doInBackground() {
                return scanAndParseDirectory(selectedDirectory);
            }

            @Override
            protected void done() {
                try {
                    List<CacheItem> items = get();
                    if (items.isEmpty()) {
                        statusLabel.setText("未找到有效的缓存视频：" + selectedDirectory.getAbsolutePath());
                        return;
                    }
                    statusLabel.setText("扫描完成，共发现 " + items.size() + " 个视频，正在加载...");
                    renderCardsInBatches(items);
                } catch (Exception e) {
                    e.printStackTrace();
                    statusLabel.setText("扫描出错: " + e.getMessage());
                }
            }
        }.execute();
    }

    private List<CacheItem> scanAndParseDirectory(File rootDir) {
        List<CacheItem> result = new ArrayList<>();
        File[] dirs = rootDir.listFiles(File::isDirectory);
        if (dirs == null) return result;

        for (File dir : dirs) {
            if (dir.getName().matches("\\d+")) {
                File jsonFile = new File(dir, ".videoInfo/videoinfo.json");
                if (!jsonFile.exists()) jsonFile = new File(dir, "videoInfo.json");

                if (jsonFile.exists()) {
                    try {
                        BiliDownloadMeta meta = mapper.readValue(jsonFile, BiliDownloadMeta.class);
                        result.add(new CacheItem(dir, meta));
                    } catch (Exception e) {
                        System.err.println("解析失败: " + dir.getName());
                    }
                }
            }
        }
        return result;
    }

    private void renderCardsInBatches(List<CacheItem> items) {
        new SwingWorker<Void, List<CacheItem>>() {
            @Override
            protected Void doInBackground() {
                int batchSize = 15; // 分批渲染
                for (int i = 0; i < items.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, items.size());
                    publish(items.subList(i, end));
                    try { Thread.sleep(15); } catch (InterruptedException ignored) {}
                }
                return null;
            }

            @Override
            protected void process(List<List<CacheItem>> chunks) {
                for (List<CacheItem> chunk : chunks) {
                    for (CacheItem item : chunk) {
                        videoGrid.add(createVideoCard(item));
                    }
                    videoGrid.revalidate();
                    videoGrid.repaint();
                }
                statusLabel.setText("加载完成，共 " + videoGrid.getComponentCount() + " 个视频");
            }
        }.execute();
    }

    private JPanel createVideoCard(CacheItem item) {
        JPanel card = new JPanel(new BorderLayout(5, 5));
        card.setPreferredSize(new Dimension(190, 160));
        card.setBackground(Color.WHITE);
        card.setCursor(new Cursor(Cursor.HAND_CURSOR));
        card.setBorder(BorderFactory.createCompoundBorder(
                new LineBorder(new Color(220, 220, 220), 1, true),
                new EmptyBorder(5, 5, 5, 5)
        ));

        JLabel coverLabel = new JLabel("加载中...", SwingConstants.CENTER);
        coverLabel.setPreferredSize(new Dimension(180, 100));
        coverLabel.setOpaque(true);
        coverLabel.setBackground(new Color(230, 230, 230));
        coverLabel.setFont(new Font("微软雅黑", Font.PLAIN, 12));
        coverLabel.setForeground(Color.GRAY);

        loadImageAsync(coverLabel, new File(item.folder, "image.jpg"));

        String shortTitle = item.meta.title.length() > 14 ? item.meta.title.substring(0, 14) + "..." : item.meta.title;
        JLabel titleLabel = new JLabel(shortTitle, SwingConstants.CENTER);
        titleLabel.setFont(new Font("微软雅黑", Font.BOLD, 12));
        titleLabel.setForeground(new Color(50, 50, 50));

        String hoverInfo = String.format(
                "<html><div style='width:250px;'>" +
                        "<b>标题:</b> %s<br>" +
                        "<b>UP主:</b> %s<br>" +
                        "<b>BV号:</b> %s<br>" +
                        "<b>时长:</b> %d 秒<br>" +
                        "<b>大小:</b> %.2f MB<br>" +
                        "<b>播放量:</b> %d</div></html>",
                item.meta.title, item.meta.uname, item.meta.bvid, item.meta.duration, item.meta.totalSize / 1024.0 / 1024.0, item.meta.view
        );
        card.setToolTipText(hoverInfo);

        card.add(coverLabel, BorderLayout.CENTER);
        card.add(titleLabel, BorderLayout.SOUTH);

        // 绑定鼠标事件（左键点击转换 + 右键弹出菜单）
        card.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                card.setBackground(new Color(240, 248, 255));
                card.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(new Color(100, 149, 237), 1, true),
                        new EmptyBorder(5, 5, 5, 5)
                ));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                card.setBackground(Color.WHITE);
                card.setBorder(BorderFactory.createCompoundBorder(
                        new LineBorder(new Color(220, 220, 220), 1, true),
                        new EmptyBorder(5, 5, 5, 5)
                ));
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    showPopupMenu(e, item);
                }
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                if (SwingUtilities.isLeftMouseButton(e)) {
                    if (isConverting) {
                        JOptionPane.showMessageDialog(App.this, "正在转换中，请稍候...", "提示", JOptionPane.WARNING_MESSAGE);
                        return;
                    }
                    int choice = JOptionPane.showConfirmDialog(
                            App.this,
                            "确定要将以下视频转换为 MP4 吗？\n\n标题：" + item.meta.title,
                            "确认转换",
                            JOptionPane.YES_NO_OPTION,
                            JOptionPane.QUESTION_MESSAGE
                    );
                    if (choice == JOptionPane.YES_OPTION) {
                        isConverting = true;
                        statusLabel.setText("开始处理: " + item.meta.title);
                        new SwingWorker<Void, Void>() {
                            @Override
                            protected Void doInBackground() {
                                mergeVideo(item.folder, item.meta);
                                return null;
                            }
                            @Override
                            protected void done() {
                                isConverting = false;
                            }
                        }.execute();
                    }
                }
            }
        });

        return card;
    }

    // ================= 右键菜单功能 =================

    private void showPopupMenu(MouseEvent e, CacheItem item) {
        JPopupMenu menu = new JPopupMenu();
        menu.setFont(new Font("微软雅黑", Font.PLAIN, 12));

        JMenuItem copyTitle = new JMenuItem("复制视频标题");
        copyTitle.addActionListener(ev -> copyToClipboard(item.meta.title));
        menu.add(copyTitle);

        JMenuItem copyCover = new JMenuItem("复制封面");
        copyCover.addActionListener(ev -> copyCoverToClipboard(new File(item.folder, "image.jpg")));
        menu.add(copyCover);

        JMenuItem openFolder = new JMenuItem("打开视频文件夹");
        openFolder.addActionListener(ev -> openFolder(item.folder));
        menu.add(openFolder);

        menu.addSeparator();

        JMenuItem moreInfo = new JMenuItem("更多信息");
        moreInfo.addActionListener(ev -> showMoreInfoDialog(item));
        menu.add(moreInfo);

        menu.show(e.getComponent(), e.getX(), e.getY());
    }

    private void copyToClipboard(String text) {
        StringSelection selection = new StringSelection(text);
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(selection, null);
        statusLabel.setText("已复制到剪贴板");
    }

    private void copyCoverToClipboard(File coverFile) {
        if (!coverFile.exists()) {
            JOptionPane.showMessageDialog(this, "封面文件不存在", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        imageLoader.submit(() -> {
            try {
                BufferedImage img = ImageIO.read(coverFile);
                if (img != null) {
                    Transferable transferable = new ImageSelection(img);
                    SwingUtilities.invokeLater(() -> {
                        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable, null);
                        statusLabel.setText("封面已复制到剪贴板");
                    });
                }
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this, "复制封面失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE)
                );
            }
        });
    }

    private void openFolder(File folder) {
        try {
            Desktop.getDesktop().open(folder);
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "无法打开文件夹: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    private void showMoreInfoDialog(CacheItem item) {
        JDialog dialog = new JDialog(this, "视频详细信息", true);
        dialog.setSize(700, 600);
        dialog.setLocationRelativeTo(this);
        dialog.setLayout(new BorderLayout());

        // 1. 读取原始 JSON
        String rawJsonText = "";
        File jsonFile = new File(item.folder, ".videoInfo/videoinfo.json");
        if (!jsonFile.exists()) jsonFile = new File(item.folder, "videoInfo.json");

        try {
            if (jsonFile.exists()) {
                rawJsonText = new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            } else {
                rawJsonText = "找不到 videoinfo.json 文件";
            }
        } catch (Exception ex) {
            rawJsonText = "读取 JSON 失败: " + ex.getMessage();
        }

        // 2. 构建格式化后的中文信息（包含所有字段，用分割线划分区块）
        StringBuilder info = new StringBuilder();
        BiliDownloadMeta meta = item.meta;

        info.append("=============== 视频基本信息 ===============\n");
        info.append(String.format("视频标题：%s\n", meta.title));
        info.append(String.format("BV号：%s\n", meta.bvid));
        info.append(String.format("AV号：%s\n", meta.aid));
        info.append(String.format("CID：%s\n", meta.cid));
        info.append(String.format("分P号：%d (tabP: %d)\n", meta.p, meta.tabP));
        info.append(String.format("分P名称：%s\n", meta.tabName));
        info.append(String.format("时长：%d 秒\n", meta.duration));
        info.append(String.format("类型：%s (codecid: %d)\n", meta.type, meta.codecid));
        info.append(String.format("状态：%s\n", meta.status));
        info.append(String.format("清晰度：qn %d\n", meta.qn));
        info.append(String.format("弹幕数：%d\n", meta.danmaku));
        info.append(String.format("播放量：%d\n", meta.view));
        info.append(String.format("发布时间：%d\n", meta.pubdate));
        info.append(String.format("是否允许HEVC：%s\n", meta.allowHEVC));
        info.append(String.format("是否激活：%s\n", meta.active));
        info.append(String.format("是否加载：%s\n\n", meta.loaded));

        info.append("=============== UP主与合集信息 ===============\n");
        info.append(String.format("UP主 UID：%s\n", meta.uid));
        info.append(String.format("UP主昵称：%s\n", meta.uname));
        info.append(String.format("UP主头像：%s\n", meta.avatar));
        info.append(String.format("合集标题：%s\n", meta.groupTitle));
        info.append(String.format("合集封面：%s\n", meta.groupCoverUrl));
        info.append(String.format("合集分组ID：%s\n", meta.groupId));
        info.append(String.format("合集内排序：%d\n", meta.groupOrder));
        info.append(String.format("合集封面本地路径：%s\n\n", meta.groupCoverPath));

        info.append("=============== 封面与文件信息 ===============\n");
        info.append(String.format("封面网络地址：%s\n", meta.coverUrl));
        info.append(String.format("封面本地路径：%s\n", meta.coverPath));
        info.append(String.format("总大小：%.2f MB (%d 字节)\n", meta.totalSize / 1024.0 / 1024.0, meta.totalSize));
        info.append(String.format("已加载大小：%.2f MB (%d 字节)\n", meta.loadedSize / 1024.0 / 1024.0, meta.loadedSize));
        info.append(String.format("已上报大小：%.2f MB (%d 字节)\n", meta.reportedSize / 1024.0 / 1024.0, meta.reportedSize));
        info.append(String.format("下载进度：%d%%\n", meta.progress));
        info.append(String.format("下载速度：%d 字节/秒\n\n", meta.speed));

        info.append("=============== 系统与时间信息 ===============\n");
        info.append(String.format("创建时间戳：%d\n", meta.createTime));
        info.append(String.format("更新时间戳：%d\n", meta.updateTime));
        info.append(String.format("完成时间戳：%d\n", meta.completionTime));
        info.append(String.format("vt 参数：%d\n", meta.vt));

        // 3. 创建两个文本区域
        JTextArea formattedArea = new JTextArea(info.toString());
        formattedArea.setEditable(false);
        formattedArea.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        formattedArea.setMargin(new Insets(15, 15, 15, 15));
        formattedArea.setLineWrap(true);
        formattedArea.setWrapStyleWord(true);
        JScrollPane formattedScroll = new JScrollPane(formattedArea);

        JTextArea rawJsonArea = new JTextArea(rawJsonText);
        rawJsonArea.setEditable(false);
        rawJsonArea.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        rawJsonArea.setMargin(new Insets(15, 15, 15, 15));
        rawJsonArea.setLineWrap(true);
        rawJsonArea.setWrapStyleWord(true);
        JScrollPane rawJsonScroll = new JScrollPane(rawJsonArea);

        // 4. 标签页
        JTabbedPane tabbedPane = new JTabbedPane();
        tabbedPane.setFont(new Font("微软雅黑", Font.PLAIN, 14));
        tabbedPane.addTab("格式化信息", formattedScroll);
        tabbedPane.addTab("原始 JSON", rawJsonScroll);

        dialog.add(tabbedPane, BorderLayout.CENTER);

        // 5. 底部复制按钮
        JButton copyCurrentBtn = new JButton("复制当前页内容");
        copyCurrentBtn.setFont(new Font("微软雅黑", Font.PLAIN, 13));
        copyCurrentBtn.addActionListener(ev -> {
            int selectedIndex = tabbedPane.getSelectedIndex();
            String contentToCopy = selectedIndex == 0 ? formattedArea.getText() : rawJsonArea.getText();
            copyToClipboard(contentToCopy);
            JOptionPane.showMessageDialog(dialog, "当前页内容已复制到剪贴板", "提示", JOptionPane.INFORMATION_MESSAGE);
        });

        JPanel bottomPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        bottomPanel.setBackground(new Color(245, 245, 245));
        bottomPanel.add(copyCurrentBtn);
        dialog.add(bottomPanel, BorderLayout.SOUTH);

        dialog.setVisible(true);
    }

    // ================= 性能优化：图片加载 =================

    private void loadImageAsync(JLabel label, File coverFile) {
        if (!coverFile.exists()) {
            label.setText("无封面");
            return;
        }
        imageLoader.submit(() -> {
            try {
                // 在后台线程中读取并缩放图片，避免阻塞 EDT
                BufferedImage img = ImageIO.read(coverFile);
                if (img != null) {
                    Image scaled = img.getScaledInstance(180, 100, Image.SCALE_SMOOTH);
                    SwingUtilities.invokeLater(() -> {
                        label.setText("");
                        label.setIcon(new ImageIcon(scaled));
                    });
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> label.setText("加载失败"));
            }
        });
    }

    private void openMp4Folder() {
        File mp4Dir = new File(System.getProperty("user.dir"), "mp4");
        if (!mp4Dir.exists()) mp4Dir.mkdirs();
        openFolder(mp4Dir);
    }

    private void mergeVideo(File folder, BiliDownloadMeta meta) {
        File[] m4sFiles = folder.listFiles((dir, name) -> name.endsWith(".m4s") && !name.contains("_clean_"));
        if (m4sFiles == null || m4sFiles.length < 2) {
            updateStatus("错误：未找到足够的 m4s 文件 (" + meta.title + ")");
            return;
        }

        Arrays.sort(m4sFiles, Comparator.comparingLong(File::length).reversed());
        File originalVideo = m4sFiles[0];
        File originalAudio = m4sFiles[1];

        File tempVideoClean = null;
        File tempAudioClean = null;

        try {
            updateStatus("正在剥离 M4S 文件头部...");
            tempVideoClean = FFmpegUtil.cleanM4S(originalVideo, "video");
            tempAudioClean = FFmpegUtil.cleanM4S(originalAudio, "audio");

            File ffmpegExe = FFmpegUtil.extractFFmpeg();

            File mp4Dir = new File(System.getProperty("user.dir"), "mp4");
            if (!mp4Dir.exists()) mp4Dir.mkdirs();

            String safeTitle = meta.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            File outputFile = new File(mp4Dir, safeTitle + ".mp4");

            updateStatus("正在融合: " + meta.title);
            boolean success = FFmpegUtil.merge(ffmpegExe, tempVideoClean, tempAudioClean, outputFile);

            if (!success) {
                updateStatus("首次融合失败，交换音频/视频路径重试: " + meta.title);
                success = FFmpegUtil.merge(ffmpegExe, tempAudioClean, tempVideoClean, outputFile);
            }

            if (success) {
                updateStatus("转换成功: " + outputFile.getName());
            } else {
                updateStatus("转换失败: " + meta.title);
            }

        } catch (Exception e) {
            e.printStackTrace();
            updateStatus("发生异常: " + e.getMessage());
        } finally {
            if (tempVideoClean != null && tempVideoClean.exists()) tempVideoClean.delete();
            if (tempAudioClean != null && tempAudioClean.exists()) tempAudioClean.delete();
        }
    }

    private void updateStatus(String text) {
        SwingUtilities.invokeLater(() -> statusLabel.setText(text));
    }

    // ================= 配置持久化相关 =================

    private void saveConfig(String path) {
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            Properties props = new Properties();
            props.setProperty(KEY_LAST_DIR, path);
            props.store(out, "BiliToVideo Config");
        } catch (Exception e) {
            System.err.println("保存配置失败: " + e.getMessage());
        }
    }

    private String loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(KEY_LAST_DIR);
        } catch (Exception e) {
            return null;
        }
    }

    private static class CacheItem {
        File folder;
        BiliDownloadMeta meta;
        CacheItem(File folder, BiliDownloadMeta meta) {
            this.folder = folder;
            this.meta = meta;
        }
    }

    // 自定义 Transferable 用于将图片放入剪贴板
    private static class ImageSelection implements Transferable {
        private final Image image;
        public ImageSelection(Image image) { this.image = image; }
        @Override public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }
        @Override public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!DataFlavor.imageFlavor.equals(flavor)) throw new UnsupportedFlavorException(flavor);
            return image;
        }
    }

    public static void main(String[] args) {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            UIManager.put("Label.font", new Font("微软雅黑", Font.PLAIN, 12));
            UIManager.put("Button.font", new Font("微软雅黑", Font.PLAIN, 13));
            UIManager.put("MenuItem.font", new Font("微软雅黑", Font.PLAIN, 12));
            UIManager.put("OptionPane.messageFont", new Font("微软雅黑", Font.PLAIN, 13));
            UIManager.put("OptionPane.buttonFont", new Font("微软雅黑", Font.PLAIN, 13));
            UIManager.put("TabbedPane.font", new Font("微软雅黑", Font.PLAIN, 14));
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> new App().setVisible(true));
    }
}