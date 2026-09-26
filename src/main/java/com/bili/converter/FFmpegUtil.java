package com.bili.converter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

public class FFmpegUtil {

    private static String cachedHwEncoder = null;
    private static boolean hwChecked = false;

    // ============================================================
    // FFmpeg 提取
    // ============================================================
    public static File extractFFmpeg() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String exeName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";
        InputStream is = FFmpegUtil.class.getResourceAsStream("/ffmpeg/" + exeName);
        if (is == null) throw new RuntimeException("找不到 FFmpeg 资源文件");

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "bili_converter_tmp");
        if (!tempDir.exists()) tempDir.mkdirs();

        File ffmpegFile = new File(tempDir, exeName);
        if (!ffmpegFile.exists() || ffmpegFile.length() == 0) {
            Files.copy(is, ffmpegFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        ffmpegFile.setExecutable(true);
        return ffmpegFile;
    }

    // ============================================================
    // M4S 头部剥离
    // ============================================================
    public static File cleanM4S(File srcFile, String suffix) throws IOException {
        File tempFile = new File(srcFile.getParent(),
                srcFile.getName().replace(".m4s", "_clean_" + suffix + ".m4s"));
        try (FileInputStream fis = new FileInputStream(srcFile);
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tempFile))) {
            if (fis.skip(9) != 9) throw new IOException("文件太小");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
        }
        return tempFile;
    }

    // ============================================================
    // 正式转换（无损封装）
    // ============================================================
    public static boolean merge(File ffmpegExe, File input1, File input2, File output) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    ffmpegExe.getAbsolutePath(),
                    "-i", input1.getAbsolutePath(),
                    "-i", input2.getAbsolutePath(),
                    "-c", "copy",
                    "-y",
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {}
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // ============================================================
    // 硬件编码器实际可用性检测
    // ============================================================
    public static synchronized String detectHwEncoder(File ffmpegExe) {
        if (hwChecked) return cachedHwEncoder;
        hwChecked = true;

        // 优先 AMD（因为你是 780M），再试 Intel，最后 NVIDIA
        String[][] candidates = {
                {"h264_amf",   "AMD AMF"},
                {"h264_qsv",   "Intel Quick Sync"},
                {"h264_nvenc", "NVIDIA NVENC"},
        };
        for (String[] c : candidates) {
            if (testEncoder(ffmpegExe, c[0])) {
                System.out.println("[FFmpegUtil] 检测到可用硬件编码器: " + c[1] + " (" + c[0] + ")");
                cachedHwEncoder = c[0];
                return cachedHwEncoder;
            }
        }
        System.out.println("[FFmpegUtil] 未检测到可用硬件编码器，使用 CPU 编码");
        cachedHwEncoder = null;
        return null;
    }

    /**
     * 实际测试：用 lavfi 生成 1 秒测试画面，跑一遍编码。
     * 只有真正成功的编码器才认为可用（避免"列表里有但实际不能用"）
     */
    private static boolean testEncoder(File ffmpegExe, String encoderName) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExe.getAbsolutePath());
        cmd.add("-hide_banner");
        cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-f"); cmd.add("lavfi");
        cmd.add("-i"); cmd.add("color=c=black:s=320x240:d=1:r=30");
        cmd.add("-c:v"); cmd.add(encoderName);
        switch (encoderName) {
            case "h264_nvenc" -> { cmd.add("-preset"); cmd.add("p1"); }
            case "h264_qsv"   -> { cmd.add("-preset"); cmd.add("veryfast"); }
            case "h264_amf"   -> { cmd.add("-quality"); cmd.add("speed"); }
        }
        cmd.add("-f"); cmd.add("null");
        cmd.add("-");
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                while (r.readLine() != null) {}
            }
            return p.waitFor() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    // ============================================================
    // 预览转码（硬件优先 + 自动回退）
    // ============================================================
    public static boolean mergePreview(File ffmpegExe, File input1, File input2, File output) {
        String hwEncoder = detectHwEncoder(ffmpegExe);
        boolean ok = doPreviewEncode(ffmpegExe, input1, input2, output, hwEncoder);

        // 硬件编码失败 → 自动回退到 CPU
        if (!ok && hwEncoder != null) {
            System.out.println("[FFmpegUtil] 硬件编码失败，回退到 CPU 编码");
            // 清除缓存，避免后续重复尝试
            cachedHwEncoder = null;
            ok = doPreviewEncode(ffmpegExe, input1, input2, output, null);
        }
        return ok;
    }

    private static boolean doPreviewEncode(File ffmpegExe, File input1, File input2,
                                           File output, String hwEncoder) {
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpegExe.getAbsolutePath());

        // 输入
        cmd.add("-fflags"); cmd.add("+genpts");
        cmd.add("-err_detect"); cmd.add("ignore_err");
        cmd.add("-i"); cmd.add(input1.getAbsolutePath());
        cmd.add("-i"); cmd.add(input2.getAbsolutePath());

        // ---------- 视频编码 ----------
        if (hwEncoder != null) {
            switch (hwEncoder) {
                case "h264_nvenc" -> {
                    cmd.add("-c:v"); cmd.add("h264_nvenc");
                    cmd.add("-preset"); cmd.add("p1");
                    cmd.add("-rc"); cmd.add("constqp");
                    cmd.add("-qp"); cmd.add("26");
                    cmd.add("-bf"); cmd.add("0");
                }
                case "h264_qsv" -> {
                    cmd.add("-c:v"); cmd.add("h264_qsv");
                    cmd.add("-preset"); cmd.add("veryfast");
                    cmd.add("-global_quality"); cmd.add("26");
                    cmd.add("-bf"); cmd.add("0");
                }
                case "h264_amf" -> {
                    cmd.add("-c:v"); cmd.add("h264_amf");
                    cmd.add("-quality"); cmd.add("speed");
                    cmd.add("-rc"); cmd.add("cqp");
                    cmd.add("-qp_i"); cmd.add("26");
                    cmd.add("-qp_p"); cmd.add("26");
                    cmd.add("-bf"); cmd.add("0");
                }
            }
        } else {
            cmd.add("-c:v"); cmd.add("libx264");
            cmd.add("-preset"); cmd.add("veryfast");
            cmd.add("-crf"); cmd.add("26");
            cmd.add("-bf"); cmd.add("0");
        }

        // ---------- 视频稳定性优化 ----------
        cmd.add("-pix_fmt"); cmd.add("yuv420p");
        cmd.add("-fps_mode"); cmd.add("cfr");
        cmd.add("-r"); cmd.add("30");
        cmd.add("-g"); cmd.add("30");
        cmd.add("-keyint_min"); cmd.add("30");
        cmd.add("-sc_threshold"); cmd.add("0");
        cmd.add("-vf"); cmd.add("setsar=1");

        // ---------- 音频 ----------
        cmd.add("-c:a"); cmd.add("aac");
        cmd.add("-b:a"); cmd.add("160k");
        cmd.add("-ar"); cmd.add("44100");
        cmd.add("-ac"); cmd.add("2");
        cmd.add("-af"); cmd.add("dynaudnorm=p=0.9");

        // ---------- 输出 ----------
        cmd.add("-shortest");
        cmd.add("-y");
        cmd.add(output.getAbsolutePath());

        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Error") || line.contains("error")) {
                        System.err.println("[FFmpeg] " + line);
                    }
                }
            }
            return process.waitFor() == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}