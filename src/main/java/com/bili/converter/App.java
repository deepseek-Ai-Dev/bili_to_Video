package com.bili.converter;

import atlantafx.base.theme.PrimerDark;
import atlantafx.base.theme.PrimerLight;
import com.fasterxml.jackson.databind.ObjectMapper;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.fxml.Initializable;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.*;
import javafx.scene.media.Media;
import javafx.scene.media.MediaPlayer;
import javafx.scene.media.MediaView;
import javafx.stage.DirectoryChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

public class App extends Application implements Initializable {

    private static final String CONFIG_FILE = "config.properties";
    private static final String KEY_LAST_DIR = "last_cache_dir";
    private static final int VIEWPORT_BUFFER = 400;

    // 预览缓存目录
    private static final File PREVIEW_CACHE_DIR =
            new File(System.getProperty("java.io.tmpdir"), "bili_preview_cache");

    @FXML private TextField inputPathField;
    @FXML private FlowPane videoGrid;
    @FXML private ScrollPane contentScroll;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML private Label queueCountLabel;
    @FXML private Label logLabel;
    @FXML private ToggleButton themeToggle;

    private final ObjectMapper mapper = new ObjectMapper();
    private final ExecutorService imageLoader = Executors.newCachedThreadPool();
    private boolean darkMode = true;

    // 打字机
    private Timeline typingTimeline;

    // 虚拟化
    private final Map<ImageView, File> imageViewFileMap = new HashMap<>();
    private final Set<ImageView> loadedImages =
            Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    private volatile long lastVirtualizeCheck = 0;

    // 悬浮跟随弹窗
    private Popup hoverPopup;

    // 转换队列
    private final ConcurrentLinkedQueue<ConversionJob> conversionQueue = new ConcurrentLinkedQueue<>();
    private final Object queueMonitor = new Object();

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage primaryStage) throws Exception {
        Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        loader.setController(this);
        BorderPane root = loader.load();
        Scene scene = new Scene(root, 1000, 800);
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
        primaryStage.setTitle("BiliToVideo 缓存转换器");
        primaryStage.setScene(scene);
        primaryStage.show();

        clearPreviewCache();
        startQueueWorker();
        setupVirtualization();
        loadLastDirectory();
    }

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        themeToggle.setSelected(true);
        themeToggle.setText("🌙 暗色");
        themeToggle.setOnAction(e -> toggleTheme());
        log("欢迎使用 BiliToVideo ✨");
        log("点击封面/标题即可预览，右键加入转换队列。");
    }

    // ============================================================
    // 主题切换
    // ============================================================
    private void toggleTheme() {
        darkMode = themeToggle.isSelected();
        if (darkMode) {
            Application.setUserAgentStylesheet(new PrimerDark().getUserAgentStylesheet());
            themeToggle.setText("🌙 暗色");
        } else {
            Application.setUserAgentStylesheet(new PrimerLight().getUserAgentStylesheet());
            themeToggle.setText("☀ 亮色");
        }
        Scene scene = themeToggle.getScene();
        scene.getStylesheets().clear();
        scene.getStylesheets().add(getClass().getResource("/css/style.css").toExternalForm());
    }

    // ============================================================
    // 虚拟化滚动
    // ============================================================
    private void setupVirtualization() {
        contentScroll.vvalueProperty().addListener((obs, o, n) -> scheduleVirtualize());
        videoGrid.heightProperty().addListener((obs, o, n) -> scheduleVirtualize());
        videoGrid.widthProperty().addListener((obs, o, n) -> scheduleVirtualize());
    }

    private void scheduleVirtualize() {
        long now = System.currentTimeMillis();
        if (now - lastVirtualizeCheck < 80) return;
        lastVirtualizeCheck = now;
        Platform.runLater(this::updateVisibleImages);
    }

    private void updateVisibleImages() {
        if (contentScroll.getViewportBounds() == null) return;
        double viewportH = contentScroll.getViewportBounds().getHeight();
        double scrollY = contentScroll.getVvalue() * Math.max(0, videoGrid.getHeight() - viewportH);

        for (Node node : videoGrid.getChildren()) {
            if (!(node instanceof VBox card)) continue;
            Object ivObj = card.getProperties().get("imageView");
            if (!(ivObj instanceof ImageView iv)) continue;

            Bounds b = card.getBoundsInParent();
            boolean visible = b.getMaxY() > scrollY - VIEWPORT_BUFFER
                    && b.getMinY() < scrollY + viewportH + VIEWPORT_BUFFER;

            File src = imageViewFileMap.get(iv);
            if (src == null) continue;

            if (visible && !loadedImages.contains(iv)) {
                loadedImages.add(iv);
                loadImageInto(iv, src);
            } else if (!visible && loadedImages.contains(iv)) {
                iv.setImage(null);
                loadedImages.remove(iv);
            }
        }
    }

    private void loadImageInto(ImageView iv, File coverFile) {
        if (!coverFile.exists()) return;
        imageLoader.submit(() -> {
            try {
                Image img = new Image(coverFile.toURI().toString(), 180, 100, true, true);
                if (!img.isError()) Platform.runLater(() -> iv.setImage(img));
            } catch (Exception ignored) {}
        });
    }

    // ============================================================
    // 预览缓存管理
    // ============================================================
    private void clearPreviewCache() {
        if (!PREVIEW_CACHE_DIR.exists()) {
            PREVIEW_CACHE_DIR.mkdirs();
            return;
        }
        File[] files = PREVIEW_CACHE_DIR.listFiles();
        if (files != null) {
            for (File f : files) {
                // 只清 tmp 文件，保留已完成的 mp4
                if (f.getName().endsWith(".tmp.mp4") || f.getName().endsWith(".tmp")) {
                    f.delete();
                }
            }
        }
    }

    /**
     * 等待文件真正可读：连续 3 次大小一致 + 能打开读取
     */
    private boolean waitFileReady(File f, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        long lastSize = -1;
        int stableCount = 0;
        while (System.currentTimeMillis() < deadline) {
            long size = f.length();
            if (size > 0 && size == lastSize) {
                stableCount++;
                if (stableCount >= 3) {
                    try (FileInputStream fis = new FileInputStream(f)) {
                        byte[] buf = new byte[16];
                        if (fis.read(buf) == 16) return true;
                    } catch (Exception ignored) {}
                }
            } else {
                stableCount = 0;
            }
            lastSize = size;
            try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        }
        return f.length() > 0;
    }

    // ============================================================
    // 扫描与解析
    // ============================================================
    @FXML
    private void onChooseInput() {
        String dirPath = null;
        String os = System.getProperty("os.name").toLowerCase();
        if (os.contains("win")) {
            dirPath = NativeFileDialog.chooseFolder("选择缓存视频根目录");
        } else {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle("选择缓存视频根目录");
            File selected = chooser.showDialog(inputPathField.getScene().getWindow());
            if (selected != null) dirPath = selected.getAbsolutePath();
        }
        if (dirPath != null) {
            inputPathField.setText(dirPath);
            saveConfig(dirPath);
            loadDirectory(new File(dirPath));
        }
    }

    @FXML
    private void onRefresh() {
        String path = inputPathField.getText();
        if (path == null || path.isBlank()) { log("⚠ 请先选择目录"); return; }
        File dir = new File(path);
        if (!dir.exists()) { log("⚠ 目录不存在"); return; }
        loadDirectory(dir);
    }

    private void loadLastDirectory() {
        String lastPath = loadConfig();
        if (lastPath != null) {
            File dir = new File(lastPath);
            if (dir.exists() && dir.isDirectory()) {
                inputPathField.setText(lastPath);
                log("自动加载上次缓存目录：" + lastPath);
                loadDirectory(dir);
            }
        }
    }

    private void loadDirectory(File selectedDirectory) {
        videoGrid.getChildren().clear();
        imageViewFileMap.clear();
        loadedImages.clear();
        statusLabel.setText("正在扫描目录...");
        progressBar.setProgress(0);

        Task<List<VideoGroup>> task = new Task<>() {
            @Override protected List<VideoGroup> call() {
                return scanAndParseDirectory(selectedDirectory);
            }
        };

        task.setOnSucceeded(e -> {
            List<VideoGroup> groups = task.getValue();
            if (groups.isEmpty()) {
                statusLabel.setText("未找到有效的缓存视频");
                log("⚠ 在目录中未找到有效的缓存视频");
                return;
            }
            int totalParts = groups.stream().mapToInt(g -> g.parts.size()).sum();
            log(String.format("扫描完成，共 %d 个视频（%d 个分P）", groups.size(), totalParts));
            statusLabel.setText("加载中...");
            renderCardsInBatches(groups);
        });

        task.setOnFailed(e -> {
            statusLabel.setText("扫描出错");
            log("❌ 扫描出错: " + task.getException().getMessage());
        });

        new Thread(task).start();
    }

    private List<VideoGroup> scanAndParseDirectory(File rootDir) {
        Map<String, VideoGroup> groups = new LinkedHashMap<>();
        File[] dirs = rootDir.listFiles(File::isDirectory);
        if (dirs == null) return new ArrayList<>();
        Arrays.sort(dirs, Comparator.comparing(File::getName));

        for (File dir : dirs) {
            if (!dir.getName().matches("\\d+")) continue;
            File jsonFile = new File(dir, ".videoInfo/videoinfo.json");
            if (!jsonFile.exists()) jsonFile = new File(dir, "videoInfo.json");
            if (!jsonFile.exists()) continue;
            try {
                BiliDownloadMeta meta = mapper.readValue(jsonFile, BiliDownloadMeta.class);
                CacheItem item = new CacheItem(dir, meta);
                String key = (meta.bvid != null && !meta.bvid.isEmpty()) ? meta.bvid : dir.getName();
                groups.computeIfAbsent(key, k -> new VideoGroup(key, meta.title)).parts.add(item);
            } catch (Exception ignored) {}
        }
        for (VideoGroup g : groups.values()) {
            g.parts.sort(Comparator.comparingInt(p -> p.meta.p));
        }
        return new ArrayList<>(groups.values());
    }

    private void renderCardsInBatches(List<VideoGroup> groups) {
        Task<Void> renderTask = new Task<>() {
            @Override protected Void call() throws Exception {
                int batchSize = 20;
                for (int i = 0; i < groups.size(); i += batchSize) {
                    int end = Math.min(i + batchSize, groups.size());
                    List<VideoGroup> chunk = groups.subList(i, end);
                    Platform.runLater(() -> {
                        for (VideoGroup g : chunk) videoGrid.getChildren().add(createVideoCard(g));
                    });
                    Thread.sleep(15);
                }
                return null;
            }
        };
        renderTask.setOnSucceeded(e -> {
            statusLabel.setText("加载完成，共 " + videoGrid.getChildren().size() + " 个视频");
            progressBar.setProgress(0);
            Platform.runLater(() -> {
                videoGrid.layout();
                updateVisibleImages();
            });
        });
        new Thread(renderTask).start();
    }

    // ============================================================
    // 视频卡片
    // ============================================================
    private VBox createVideoCard(VideoGroup group) {
        VBox card = new VBox(6);
        card.setAlignment(Pos.CENTER);
        card.getStyleClass().add("video-card");
        card.setPrefWidth(200);
        card.setMaxWidth(200);

        ImageView coverView = new ImageView();
        coverView.setFitWidth(180);
        coverView.setFitHeight(100);
        coverView.setPreserveRatio(true);

        File coverFile = new File(group.parts.get(0).folder, "image.jpg");
        imageViewFileMap.put(coverView, coverFile);
        card.getProperties().put("imageView", coverView);

        String title = group.parts.size() > 1 ? group.title : group.parts.get(0).meta.title;
        String shortTitle = title.length() > 14 ? title.substring(0, 14) + "..." : title;
        Label titleLabel = new Label(shortTitle);
        titleLabel.getStyleClass().add("card-title");
        titleLabel.setWrapText(true);
        titleLabel.setAlignment(Pos.CENTER);

        if (group.parts.size() > 1) {
            Label badge = new Label("共 " + group.parts.size() + " P");
            badge.getStyleClass().add("parts-badge");
            card.getChildren().addAll(coverView, titleLabel, badge);
        } else {
            card.getChildren().addAll(coverView, titleLabel);
        }

        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(150), card);
        scaleIn.setToX(1.03); scaleIn.setToY(1.03);
        ScaleTransition scaleOut = new ScaleTransition(Duration.millis(150), card);
        scaleOut.setToX(1.0); scaleOut.setToY(1.0);

        card.setOnMouseEntered(e -> {
            scaleIn.playFromStart();
            showHoverPopup(group, e.getScreenX(), e.getScreenY());
        });
        card.setOnMouseMoved(e -> {
            if (hoverPopup != null && hoverPopup.isShowing()) {
                hoverPopup.setX(e.getScreenX() + 18);
                hoverPopup.setY(e.getScreenY() + 18);
            }
        });
        card.setOnMouseExited(e -> {
            scaleOut.playFromStart();
            hideHoverPopup();
        });

        card.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY) {
                showPreviewDialog(group, group.parts.get(0));
            } else if (e.getButton() == MouseButton.SECONDARY) {
                showContextMenu(e, group);
            }
        });

        return card;
    }

    // ============================================================
    // 悬浮跟随弹窗
    // ============================================================
    private void showHoverPopup(VideoGroup group, double screenX, double screenY) {
        if (hoverPopup == null) {
            hoverPopup = new Popup();
            hoverPopup.setAutoFix(true);
            hoverPopup.setAutoHide(false);
        }
        hoverPopup.getContent().clear();
        hoverPopup.getContent().add(buildHoverContent(group));
        hoverPopup.show(videoGrid, screenX + 18, screenY + 18);
    }

    private void hideHoverPopup() {
        if (hoverPopup != null && hoverPopup.isShowing()) {
            hoverPopup.hide();
        }
    }

    private VBox buildHoverContent(VideoGroup group) {
        VBox box = new VBox(6);
        box.setMaxWidth(320);
        box.setStyle(
                "-fx-background-color: rgba(28, 28, 30, 0.96);" +
                        "-fx-background-radius: 8;" +
                        "-fx-padding: 12 16 12 16;" +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.55), 18, 0.3, 0, 6);" +
                        "-fx-border-color: rgba(255,255,255,0.12);" +
                        "-fx-border-radius: 8;" +
                        "-fx-border-width: 1;"
        );

        BiliDownloadMeta first = group.parts.get(0).meta;
        long totalSize = group.parts.stream().mapToLong(p -> p.meta.totalSize).sum();
        int totalDuration = group.parts.stream().mapToInt(p -> p.meta.duration).sum();

        Label title = new Label(group.title);
        title.setStyle("-fx-text-fill: #ffffff; -fx-font-weight: bold; -fx-font-size: 13px;");
        title.setWrapText(true);
        title.setMaxWidth(300);

        Label info = new Label(String.format(
                "UP主: %s\nBV号: %s\n分P数: %d\n总时长: %s\n总大小: %.2f MB\n播放量: %d",
                first.uname, group.bvid, group.parts.size(),
                formatDuration(totalDuration),
                totalSize / 1024.0 / 1024.0, first.view));
        info.setStyle("-fx-text-fill: #c8c8c8; -fx-font-size: 12px; -fx-line-spacing: 3;");

        box.getChildren().addAll(title, new Separator(), info);
        return box;
    }

    // ============================================================
    // 预览对话框
    // ============================================================
    private void showPreviewDialog(VideoGroup group, CacheItem initialPart) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("预览：" + group.title);
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(1100, 650);

        MediaView mediaView = new MediaView();
        mediaView.setFitWidth(820);
        mediaView.setFitHeight(461);
        mediaView.setPreserveRatio(true);

        StackPane mediaPane = new StackPane(mediaView);
        mediaPane.setStyle("-fx-background-color: black;");
        mediaPane.setPrefSize(820, 461);

        Slider timeSlider = new Slider(0, 100, 0);
        timeSlider.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(timeSlider, Priority.ALWAYS);
        Label timeLabel = new Label("00:00 / 00:00");
        timeLabel.setMinWidth(120);
        timeLabel.setStyle("-fx-font-family: 'Consolas'; -fx-text-fill: -color-fg-default;");

        Button playBtn = new Button("播放");
        Button pauseBtn = new Button("暂停");
        playBtn.getStyleClass().add("btn-primary");
        pauseBtn.getStyleClass().add("btn-ghost");

        Label volumeIcon = new Label("🔊");
        Slider volumeSlider = new Slider(0, 1, 0.8);
        volumeSlider.setPrefWidth(120);
        ToggleButton muteBtn = new ToggleButton("静音");
        muteBtn.getStyleClass().add("btn-ghost");

        HBox controls = new HBox(8, playBtn, pauseBtn, timeSlider, timeLabel,
                new Separator(Orientation.VERTICAL),
                volumeIcon, volumeSlider, muteBtn);
        controls.setAlignment(Pos.CENTER_LEFT);

        Label loadingLabel = new Label("");
        loadingLabel.setStyle("-fx-text-fill: -color-fg-muted; -fx-font-size: 12px;");

        VBox playerBox = new VBox(8, mediaPane, controls, loadingLabel);
        playerBox.setPadding(new Insets(10));
        HBox.setHgrow(playerBox, Priority.ALWAYS);

        ListView<CacheItem> partsList = new ListView<>();
        partsList.getItems().addAll(group.parts);
        partsList.setPrefWidth(230);
        partsList.setStyle("-fx-font-size: 13px;");
        partsList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(CacheItem item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) setText(null);
                else setText(String.format("P%-3d  %s\n%.1f MB",
                        item.meta.p, item.meta.tabName, item.meta.totalSize / 1024.0 / 1024.0));
            }
        });

        final MediaPlayer[] playerRef = new MediaPlayer[1];
        final AtomicInteger requestId = new AtomicInteger(0);
        final boolean[] userSeeking = {false};

        Consumer<CacheItem> loadPart = (part) -> {
            int myId = requestId.incrementAndGet();

            if (playerRef[0] != null) {
                playerRef[0].stop();
                playerRef[0].dispose();
                playerRef[0] = null;
            }
            mediaView.setMediaPlayer(null);
            timeSlider.setValue(0);
            timeLabel.setText("00:00 / 00:00");

            String cacheKey = part.meta.bvid + "_P" + part.meta.p;
            File cachedMp4 = new File(PREVIEW_CACHE_DIR, cacheKey + ".mp4");
            log("切换分P: P" + part.meta.p + " " + part.meta.tabName);

            // 命中缓存
            if (cachedMp4.exists() && cachedMp4.length() > 1024) {
                loadingLabel.setText("加载缓存...");
                log("✅ 命中预览缓存: " + cachedMp4.getName());
                playWithRetry(cachedMp4, myId, mediaView, timeSlider, timeLabel,
                        loadingLabel, volumeSlider, muteBtn, playerRef, userSeeking, requestId);
                return;
            }

            loadingLabel.setText("正在准备预览文件...（首次转码，请稍候）");
            log("开始转码预览: " + cacheKey);

            new Thread(() -> {
                try {
                    File[] m4s = part.folder.listFiles((d, name) ->
                            name.endsWith(".m4s") && !name.contains("_clean_"));
                    if (m4s == null || m4s.length < 2) {
                        Platform.runLater(() -> {
                            loadingLabel.setText("❌ 未找到视频文件");
                            log("❌ 未找到足够的 m4s 文件");
                        });
                        return;
                    }
                    Arrays.sort(m4s, Comparator.comparingLong(File::length).reversed());
                    if (requestId.get() != myId) return;

                    log("剥离 M4S 头部...");
                    File tempV = FFmpegUtil.cleanM4S(m4s[0], "preview_v");
                    File tempA = FFmpegUtil.cleanM4S(m4s[1], "preview_a");
                    if (requestId.get() != myId) { tempV.delete(); tempA.delete(); return; }

                    File ffmpegExe = FFmpegUtil.extractFFmpeg();
                    if (!PREVIEW_CACHE_DIR.exists()) PREVIEW_CACHE_DIR.mkdirs();
                    File tempMp4 = new File(PREVIEW_CACHE_DIR, cacheKey + ".tmp.mp4");
                    if (tempMp4.exists()) tempMp4.delete();

                    log("FFmpeg 转码中...");
                    boolean ok = FFmpegUtil.mergePreview(ffmpegExe, tempV, tempA, tempMp4);
                    tempV.delete();
                    tempA.delete();

                    if (requestId.get() != myId) { tempMp4.delete(); return; }
                    if (!ok) {
                        Platform.runLater(() -> {
                            loadingLabel.setText("❌ 转码预览失败");
                            log("❌ FFmpeg 转码失败");
                        });
                        return;
                    }

                    log("等待文件写入完成...");
                    boolean ready = waitFileReady(tempMp4, 5000);
                    if (!ready) {
                        Platform.runLater(() -> {
                            loadingLabel.setText("❌ 文件写入超时");
                            log("❌ 文件写入超时");
                        });
                        return;
                    }

                    if (cachedMp4.exists()) cachedMp4.delete();
                    File playFile = cachedMp4;
                    if (!tempMp4.renameTo(cachedMp4)) {
                        playFile = tempMp4;
                    }
                    final File finalPlayFile = playFile;
                    final long fileSize = finalPlayFile.length() / 1024;
                    Platform.runLater(() -> {
                        log("✅ 转码完成 (" + fileSize + " KB)，开始播放");
                        playWithRetry(finalPlayFile, myId, mediaView, timeSlider,
                                timeLabel, loadingLabel, volumeSlider, muteBtn, playerRef, userSeeking, requestId);
                    });

                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        loadingLabel.setText("❌ " + ex.getMessage());
                        log("❌ 预览异常: " + ex.getMessage());
                    });
                }
            }).start();
        };

        timeSlider.setOnMousePressed(e -> userSeeking[0] = true);
        timeSlider.setOnMouseReleased(e -> {
            if (playerRef[0] != null) playerRef[0].seek(Duration.seconds(timeSlider.getValue()));
            userSeeking[0] = false;
        });

        playBtn.setOnAction(ev -> { if (playerRef[0] != null) playerRef[0].play(); });
        pauseBtn.setOnAction(ev -> { if (playerRef[0] != null) playerRef[0].pause(); });

        volumeSlider.valueProperty().addListener((obs, o, n) -> {
            if (playerRef[0] != null) playerRef[0].setVolume(n.doubleValue());
            if (muteBtn.isSelected() && n.doubleValue() > 0) muteBtn.setSelected(false);
        });
        muteBtn.setOnAction(ev -> {
            if (playerRef[0] == null) return;
            if (muteBtn.isSelected()) {
                playerRef[0].setMute(true);
                muteBtn.setText("取消静音");
            } else {
                playerRef[0].setMute(false);
                muteBtn.setText("静音");
            }
        });

        partsList.getSelectionModel().selectedItemProperty().addListener((obs, o, n) -> {
            if (n != null) loadPart.accept(n);
        });

        HBox mainContent = new HBox(10, playerBox, partsList);
        mainContent.setPadding(new Insets(10));
        if (group.parts.size() <= 1) {
            partsList.setVisible(false);
            partsList.setManaged(false);
        }
        dialog.getDialogPane().setContent(mainContent);

        ButtonType convertBtn = new ButtonType("加入转换队列", ButtonBar.ButtonData.LEFT);
        ButtonType closeBtn = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(convertBtn, closeBtn);

        dialog.getDialogPane().lookupButton(convertBtn).addEventFilter(ActionEvent.ACTION, ev -> {
            CacheItem sel = partsList.getSelectionModel().getSelectedItem();
            if (sel != null) {
                enqueue(sel);
                log("＋ 已加入队列: P" + sel.meta.p + " " + sel.meta.tabName);
            }
            ev.consume();
        });

        dialog.setOnCloseRequest(e -> {
            requestId.incrementAndGet();
            if (playerRef[0] != null) {
                playerRef[0].stop();
                playerRef[0].dispose();
                playerRef[0] = null;
            }
        });

        partsList.getSelectionModel().select(group.parts.indexOf(initialPart));
        dialog.showAndWait();
    }

    // ============================================================
    // 带自动重试的播放
    // ============================================================
    private void playWithRetry(File mp4, int myId, MediaView mediaView, Slider timeSlider,
                               Label timeLabel, Label loadingLabel, Slider volumeSlider,
                               ToggleButton muteBtn, MediaPlayer[] playerRef,
                               boolean[] userSeeking, AtomicInteger requestId) {
        playWithRetry(mp4, myId, mediaView, timeSlider, timeLabel, loadingLabel,
                volumeSlider, muteBtn, playerRef, userSeeking, requestId, 0);
    }

    private void playWithRetry(File mp4, int myId, MediaView mediaView, Slider timeSlider,
                               Label timeLabel, Label loadingLabel, Slider volumeSlider,
                               ToggleButton muteBtn, MediaPlayer[] playerRef,
                               boolean[] userSeeking, AtomicInteger requestId, int attempt) {
        if (requestId.get() != myId) return;

        if (!mp4.exists() || mp4.length() < 1024) {
            loadingLabel.setText("❌ 预览文件无效");
            log("❌ 预览文件无效: " + mp4.getName());
            return;
        }

        try {
            // 每次重试时用文件副本，避免 Media 缓存
            File playFile = mp4;
            if (attempt > 0) {
                File retryFile = new File(mp4.getParent(),
                        mp4.getName().replace(".mp4", "_r" + attempt + ".mp4"));
                java.nio.file.Files.copy(mp4.toPath(), retryFile.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                playFile = retryFile;
                log("↻ 播放重试 " + attempt + "/3: " + mp4.getName());
            }

            Media media = new Media(playFile.toURI().toString());
            MediaPlayer player = new MediaPlayer(media);
            mediaView.setMediaPlayer(player);
            playerRef[0] = player;
            player.setVolume(volumeSlider.getValue());
            player.setMute(muteBtn.isSelected());

            final File finalPlayFile = playFile;

            player.setOnReady(() -> {
                if (requestId.get() != myId) return;
                loadingLabel.setText("");
                timeSlider.setMax(media.getDuration().toSeconds());
                player.play();
                log("▶ 开始播放: " + finalPlayFile.getName()
                        + "  (" + formatDuration((int) media.getDuration().toSeconds()) + ")");
            });

            player.currentTimeProperty().addListener((obs, o, n) -> {
                if (requestId.get() != myId) return;
                if (!userSeeking[0]) timeSlider.setValue(n.toSeconds());
                timeLabel.setText(formatDuration((int) n.toSeconds()) + " / "
                        + formatDuration((int) media.getDuration().toSeconds()));
            });

            player.setOnError(() -> {
                if (requestId.get() != myId) return;
                String errMsg = player.getError() != null ? player.getError().getMessage() : "未知";
                System.err.println("[MediaPlayer] attempt=" + attempt + " error=" + errMsg);

                player.stop();
                player.dispose();
                mediaView.setMediaPlayer(null);
                playerRef[0] = null;

                if (attempt < 3) {
                    loadingLabel.setText("⚠ 播放失败，正在重试 (" + (attempt + 1) + "/3)...");
                    log("⚠ 播放失败，重试中: " + errMsg);
                    javafx.animation.PauseTransition delay =
                            new javafx.animation.PauseTransition(Duration.millis(800));
                    delay.setOnFinished(ev -> playWithRetry(mp4, myId, mediaView, timeSlider,
                            timeLabel, loadingLabel, volumeSlider, muteBtn, playerRef,
                            userSeeking, requestId, attempt + 1));
                    delay.play();
                } else {
                    loadingLabel.setText("❌ 播放失败（已重试3次）: " + errMsg);
                    log("❌ 播放失败（已重试3次）: " + errMsg);
                    if (mp4.exists()) mp4.delete();
                }
            });

        } catch (Exception ex) {
            loadingLabel.setText("❌ 播放异常: " + ex.getMessage());
            log("❌ 播放异常: " + ex.getMessage());
        }
    }

    // ============================================================
    // 转换队列
    // ============================================================
    private void startQueueWorker() {
        Thread worker = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                ConversionJob job;
                synchronized (queueMonitor) {
                    while (conversionQueue.isEmpty()) {
                        try { queueMonitor.wait(); }
                        catch (InterruptedException e) { return; }
                    }
                    job = conversionQueue.poll();
                }

                Platform.runLater(() -> {
                    statusLabel.setText("转换中: " + truncate(job.meta.title, 30));
                    updateQueueCount();
                    log("▶ 开始转换: " + job.meta.title);
                });

                try { doMerge(job.folder, job.meta); }
                catch (Exception ex) {
                    Platform.runLater(() -> log("❌ 转换异常: " + ex.getMessage()));
                }

                Platform.runLater(() -> {
                    updateQueueCount();
                    if (conversionQueue.isEmpty()) statusLabel.setText("就绪（队列已清空）");
                });
            }
        }, "conversion-queue-worker");
        worker.setDaemon(true);
        worker.start();
    }

    private void enqueue(CacheItem item) {
        conversionQueue.add(new ConversionJob(item.folder, item.meta));
        synchronized (queueMonitor) { queueMonitor.notifyAll(); }
        Platform.runLater(() -> {
            updateQueueCount();
            log("＋ 已加入队列: " + item.meta.title);
        });
    }

    private void updateQueueCount() {
        queueCountLabel.setText("队列: " + conversionQueue.size());
    }

    @FXML
    private void onClearQueue() {
        int n = conversionQueue.size();
        conversionQueue.clear();
        updateQueueCount();
        log("🧹 已清空队列，移除 " + n + " 个任务");
    }

    // ============================================================
    // 右键菜单
    // ============================================================
    private void showContextMenu(javafx.scene.input.MouseEvent e, VideoGroup group) {
        ContextMenu menu = new ContextMenu();
        CacheItem first = group.parts.get(0);

        MenuItem preview = new MenuItem("预览视频");
        preview.setOnAction(ev -> showPreviewDialog(group, first));
        menu.getItems().add(preview);

        menu.getItems().add(new SeparatorMenuItem());

        MenuItem copyTitle = new MenuItem("复制视频标题");
        copyTitle.setOnAction(ev -> copyToClipboard(group.title));
        menu.getItems().add(copyTitle);

        MenuItem copyCover = new MenuItem("复制封面");
        copyCover.setOnAction(ev -> copyCoverToClipboard(new File(first.folder, "image.jpg")));
        menu.getItems().add(copyCover);

        MenuItem openFolder = new MenuItem("打开视频文件夹");
        openFolder.setOnAction(ev -> openFolder(first.folder));
        menu.getItems().add(openFolder);

        menu.getItems().add(new SeparatorMenuItem());

        if (group.parts.size() > 1) {
            MenuItem addAll = new MenuItem("全部加入队列 (" + group.parts.size() + " P)");
            addAll.setOnAction(ev -> { for (CacheItem p : group.parts) enqueue(p); });
            menu.getItems().add(addAll);
        } else {
            MenuItem addQueue = new MenuItem("加入转换队列");
            addQueue.setOnAction(ev -> enqueue(first));
            menu.getItems().add(addQueue);
        }

        MenuItem moreInfo = new MenuItem("更多信息");
        moreInfo.setOnAction(ev -> showMoreInfoDialog(group));
        menu.getItems().add(moreInfo);

        menu.show(videoGrid, e.getScreenX(), e.getScreenY());
    }

    // ============================================================
    // 工具
    // ============================================================
    private void copyToClipboard(String text) {
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
        log("已复制到剪贴板");
    }

    private void copyCoverToClipboard(File coverFile) {
        if (!coverFile.exists()) return;
        imageLoader.submit(() -> {
            try {
                Image img = new Image(coverFile.toURI().toString());
                if (!img.isError()) {
                    Platform.runLater(() -> {
                        ClipboardContent content = new ClipboardContent();
                        content.putImage(img);
                        Clipboard.getSystemClipboard().setContent(content);
                        log("封面已复制到剪贴板");
                    });
                }
            } catch (Exception ignored) {}
        });
    }

    private void openFolder(File folder) {
        try { Desktop.getDesktop().open(folder); }
        catch (Exception e) { log("❌ 无法打开文件夹: " + e.getMessage()); }
    }

    @FXML
    private void onOpenOutput() {
        File mp4Dir = new File(System.getProperty("user.dir"), "mp4");
        if (!mp4Dir.exists()) mp4Dir.mkdirs();
        openFolder(mp4Dir);
    }

    private String formatDuration(int seconds) {
        int h = seconds / 3600;
        int m = (seconds % 3600) / 60;
        int s = seconds % 60;
        if (h > 0) return String.format("%d:%02d:%02d", h, m, s);
        return String.format("%02d:%02d", m, s);
    }

    // ============================================================
    // 更多信息
    // ============================================================
    private void showMoreInfoDialog(VideoGroup group) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("视频详细信息");
        dialog.setResizable(true);
        dialog.getDialogPane().setPrefSize(720, 620);

        TextArea formattedArea = new TextArea();
        formattedArea.setEditable(false); formattedArea.setWrapText(true);
        formattedArea.setStyle("-fx-font-family: 'Segoe UI Variable', 'Microsoft YaHei UI'; -fx-font-size: 14px;");

        TextArea rawJsonArea = new TextArea();
        rawJsonArea.setEditable(false); rawJsonArea.setWrapText(true);
        rawJsonArea.setStyle("-fx-font-family: 'Consolas', 'Microsoft YaHei UI'; -fx-font-size: 13px;");

        TabPane tabPane = new TabPane();
        Tab tab1 = new Tab("格式化信息", formattedArea);
        Tab tab2 = new Tab("原始 JSON", rawJsonArea);
        tab1.setClosable(false); tab2.setClosable(false);
        tabPane.getTabs().addAll(tab1, tab2);

        ComboBox<CacheItem> partSelector = new ComboBox<>();
        partSelector.getItems().addAll(group.parts);
        partSelector.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CacheItem item) {
                return item == null ? "" : "P" + item.meta.p + " - " + item.meta.tabName;
            }
            @Override public CacheItem fromString(String s) { return null; }
        });
        partSelector.getSelectionModel().selectFirst();
        partSelector.setVisible(group.parts.size() > 1);
        partSelector.setManaged(group.parts.size() > 1);

        Runnable refresh = () -> {
            CacheItem sel = partSelector.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            formattedArea.setText(buildFormattedInfo(sel.meta));
            rawJsonArea.setText(readRawJson(sel.folder));
        };
        partSelector.setOnAction(ev -> refresh.run());
        refresh.run();

        VBox top = new VBox(8, partSelector);
        top.setPadding(new Insets(8));

        BorderPane content = new BorderPane(tabPane);
        content.setTop(top);
        dialog.getDialogPane().setContent(content);

        ButtonType copyBtn = new ButtonType("复制当前页内容", ButtonBar.ButtonData.LEFT);
        ButtonType closeBtn = new ButtonType("关闭", ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().addAll(copyBtn, closeBtn);
        dialog.getDialogPane().lookupButton(copyBtn).addEventFilter(ActionEvent.ACTION, ev -> {
            int idx = tabPane.getSelectionModel().getSelectedIndex();
            copyToClipboard(idx == 0 ? formattedArea.getText() : rawJsonArea.getText());
            ev.consume();
        });

        dialog.showAndWait();
    }

    private String buildFormattedInfo(BiliDownloadMeta meta) {
        StringBuilder info = new StringBuilder();
        info.append("=============== 视频基本信息 ===============\n");
        info.append(String.format("视频标题：%s\nBV号：%s\nAV号：%s\nCID：%s\n分P号：%d (tabP: %d)\n分P名称：%s\n时长：%d 秒\n类型：%s (codecid: %d)\n状态：%s\n清晰度：qn %d\n弹幕数：%d\n播放量：%d\n发布时间：%d\n是否允许HEVC：%s\n是否激活：%s\n是否加载：%s\n\n",
                meta.title, meta.bvid, meta.aid, meta.cid, meta.p, meta.tabP, meta.tabName, meta.duration, meta.type, meta.codecid, meta.status, meta.qn, meta.danmaku, meta.view, meta.pubdate, meta.allowHEVC, meta.active, meta.loaded));
        info.append("=============== UP主与合集信息 ===============\n");
        info.append(String.format("UP主 UID：%s\nUP主昵称：%s\nUP主头像：%s\n合集标题：%s\n合集封面：%s\n合集分组ID：%s\n合集内排序：%d\n合集封面本地路径：%s\n\n",
                meta.uid, meta.uname, meta.avatar, meta.groupTitle, meta.groupCoverUrl, meta.groupId, meta.groupOrder, meta.groupCoverPath));
        info.append("=============== 封面与文件信息 ===============\n");
        info.append(String.format("封面网络地址：%s\n封面本地路径：%s\n总大小：%.2f MB (%d 字节)\n已加载大小：%.2f MB (%d 字节)\n已上报大小：%.2f MB (%d 字节)\n下载进度：%d%%\n下载速度：%d 字节/秒\n\n",
                meta.coverUrl, meta.coverPath, meta.totalSize/1024.0/1024.0, meta.totalSize, meta.loadedSize/1024.0/1024.0, meta.loadedSize, meta.reportedSize/1024.0/1024.0, meta.reportedSize, meta.progress, meta.speed));
        info.append("=============== 系统与时间信息 ===============\n");
        info.append(String.format("创建时间戳：%d\n更新时间戳：%d\n完成时间戳：%d\nvt 参数：%d\n",
                meta.createTime, meta.updateTime, meta.completionTime, meta.vt));
        return info.toString();
    }

    private String readRawJson(File folder) {
        File jsonFile = new File(folder, ".videoInfo/videoinfo.json");
        if (!jsonFile.exists()) jsonFile = new File(folder, "videoInfo.json");
        try {
            if (jsonFile.exists()) return new String(java.nio.file.Files.readAllBytes(jsonFile.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            return "找不到 videoinfo.json 文件";
        } catch (Exception ex) { return "读取 JSON 失败: " + ex.getMessage(); }
    }

    // ============================================================
    // 合并逻辑
    // ============================================================
    private void doMerge(File folder, BiliDownloadMeta meta) {
        File[] m4sFiles = folder.listFiles((dir, name) ->
                name.endsWith(".m4s") && !name.contains("_clean_"));
        if (m4sFiles == null || m4sFiles.length < 2) {
            Platform.runLater(() -> log("❌ 未找到足够的 m4s 文件 (" + meta.title + ")"));
            return;
        }

        Arrays.sort(m4sFiles, Comparator.comparingLong(File::length).reversed());
        File originalVideo = m4sFiles[0];
        File originalAudio = m4sFiles[1];

        File tempVideoClean = null, tempAudioClean = null;
        try {
            tempVideoClean = FFmpegUtil.cleanM4S(originalVideo, "video");
            tempAudioClean = FFmpegUtil.cleanM4S(originalAudio, "audio");
            File ffmpegExe = FFmpegUtil.extractFFmpeg();
            File mp4Dir = new File(System.getProperty("user.dir"), "mp4");
            if (!mp4Dir.exists()) mp4Dir.mkdirs();

            String safeTitle = meta.title.replaceAll("[\\\\/:*?\"<>|]", "_");
            String fileName = safeTitle;
            if (meta.p > 1) fileName += " P" + meta.p;
            File outputFile = new File(mp4Dir, fileName + ".mp4");

            boolean success = FFmpegUtil.merge(ffmpegExe, tempVideoClean, tempAudioClean, outputFile);
            if (!success) {
                Platform.runLater(() -> log("↻ 首次失败，交换路径重试: " + meta.title));
                success = FFmpegUtil.merge(ffmpegExe, tempAudioClean, tempVideoClean, outputFile);
            }
            final boolean ok = success;
            final File out = outputFile;
            Platform.runLater(() -> {
                if (ok) log("✅ 转换成功: " + out.getName());
                else log("❌ 转换失败: " + meta.title);
            });
        } catch (Exception e) {
            Platform.runLater(() -> log("❌ 异常: " + e.getMessage()));
        } finally {
            if (tempVideoClean != null && tempVideoClean.exists()) tempVideoClean.delete();
            if (tempAudioClean != null && tempAudioClean.exists()) tempAudioClean.delete();
        }
    }

    // ============================================================
    // 打字机日志
    // ============================================================
    private void log(String msg) {
        Platform.runLater(() -> typeLog(msg));
    }

    private void typeLog(String message) {
        if (typingTimeline != null) typingTimeline.stop();
        logLabel.setText("");
        String displayMsg = message.length() > 60 ? message.substring(0, 60) + "..." : message;
        final int[] index = {0};
        typingTimeline = new Timeline();
        typingTimeline.getKeyFrames().add(new javafx.animation.KeyFrame(
                Duration.millis(35),
                e -> {
                    if (index[0] < displayMsg.length()) {
                        logLabel.setText(logLabel.getText() + displayMsg.charAt(index[0]));
                        index[0]++;
                    }
                }
        ));
        typingTimeline.setCycleCount(displayMsg.length());
        typingTimeline.play();
    }

    private String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    // ============================================================
    // 配置持久化
    // ============================================================
    private void saveConfig(String path) {
        try (FileOutputStream out = new FileOutputStream(CONFIG_FILE)) {
            Properties props = new Properties();
            props.setProperty(KEY_LAST_DIR, path);
            props.store(out, "BiliToVideo Config");
        } catch (Exception ignored) {}
    }

    private String loadConfig() {
        File file = new File(CONFIG_FILE);
        if (!file.exists()) return null;
        try (FileInputStream in = new FileInputStream(file)) {
            Properties props = new Properties();
            props.load(in);
            return props.getProperty(KEY_LAST_DIR);
        } catch (Exception e) { return null; }
    }

    // ============================================================
    // 数据结构
    // ============================================================
    private static class CacheItem {
        File folder;
        BiliDownloadMeta meta;
        CacheItem(File folder, BiliDownloadMeta meta) { this.folder = folder; this.meta = meta; }
    }

    private static class VideoGroup {
        String bvid;
        String title;
        List<CacheItem> parts = new ArrayList<>();
        VideoGroup(String bvid, String title) { this.bvid = bvid; this.title = title; }
    }

    private static class ConversionJob {
        File folder;
        BiliDownloadMeta meta;
        ConversionJob(File folder, BiliDownloadMeta meta) { this.folder = folder; this.meta = meta; }
    }
}