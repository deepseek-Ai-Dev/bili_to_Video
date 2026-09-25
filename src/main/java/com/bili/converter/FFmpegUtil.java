package com.bili.converter;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

public class FFmpegUtil {

    /**
     * 将 FFmpeg 从 JAR 资源中提取到临时目录
     */
    public static File extractFFmpeg() throws IOException {
        String os = System.getProperty("os.name").toLowerCase();
        String exeName = os.contains("win") ? "ffmpeg.exe" : "ffmpeg";

        // 从 JAR 的 resources 目录读取
        InputStream is = FFmpegUtil.class.getResourceAsStream("/ffmpeg/" + exeName);
        if (is == null) {
            throw new RuntimeException("找不到 FFmpeg 资源文件，请确保 src/main/resources/ffmpeg/" + exeName + " 存在");
        }

        File tempDir = new File(System.getProperty("java.io.tmpdir"), "bili_converter_tmp");
        if (!tempDir.exists()) {
            tempDir.mkdirs();
        }

        File ffmpegFile = new File(tempDir, exeName);
        // 如果文件不存在或大小为0，则重新提取
        if (!ffmpegFile.exists() || ffmpegFile.length() == 0) {
            Files.copy(is, ffmpegFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
        ffmpegFile.setExecutable(true);
        return ffmpegFile;
    }

    /**
     * 跳过 M4S 文件的前 9 个字节（00-08 位置的 0x30），写入临时文件
     * 不修改源文件
     */
    public static File cleanM4S(File srcFile, String suffix) throws IOException {
        File tempFile = new File(srcFile.getParent(), srcFile.getName().replace(".m4s", "_clean_" + suffix + ".m4s"));

        try (FileInputStream fis = new FileInputStream(srcFile);
             BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(tempFile))) {

            // 跳过前 9 个字节
            long skipped = fis.skip(9);
            if (skipped != 9) {
                throw new IOException("文件太小，无法跳过前9个字节");
            }

            byte[] buffer = new byte[8192];
            int read;
            while ((read = fis.read(buffer)) != -1) {
                bos.write(buffer, 0, read);
            }
        }
        return tempFile;
    }

    /**
     * 执行 FFmpeg 合并
     */
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

            // 消费输出流，防止缓冲区溢出卡死
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                while (reader.readLine() != null) {}
            }

            int exitCode = process.waitFor();
            return exitCode == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }
}