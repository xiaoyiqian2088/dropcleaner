package com.dropcleaner.config;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务端配置。
 *
 * <p>NeoForge 有 {@code ModConfigSpec}，Fabric 没有对应物，所以这里自己读一个扁平的
 * TOML（只有 {@code 键 = 值} 和 {@code # 注释}），文件放在 {@code config/dropcleaner-server.toml}。
 * 文件不存在时自动写一份带中文注释的默认配置。</p>
 */
public final class DropCleanerConfig {

    private static final Logger LOGGER = LoggerFactory.getLogger("dropcleaner");
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("dropcleaner-server.toml");

    private static int scanIntervalTicks = 200;
    private static int dropThreshold = 64;
    private static int countdownSeconds = 5;
    private static int clearRadiusChunks = 1;
    private static int notifyRadiusChunks = 10;
    private static int mergeMaxStackSize = 64;
    private static int cacheMaxBatches = 50;
    private static int cacheMaxItems = 3200;
    private static boolean protectNotifyEnabled = true;
    private static String protectNotifyText = "保护区「%s」堆积了 %s 堆掉落物，请管理员过来清理";
    private static int protectNotifyCooldownSeconds = 600;
    private static int protectMaxRadiusChunks = 16;

    private DropCleanerConfig() {
    }

    public static void load() {
        Map<String, String> values = new HashMap<>();
        try {
            if (Files.exists(FILE)) {
                for (String raw : Files.readAllLines(FILE, StandardCharsets.UTF_8)) {
                    // 有人用 PowerShell 编辑过配置会留下 BOM，先去掉
                    String line = stripComment(raw.replace("\ufeff", "")).trim();
                    if (line.isEmpty() || !line.contains("=")) {
                        continue;
                    }
                    int eq = line.indexOf('=');
                    values.put(line.substring(0, eq).trim(), unquote(line.substring(eq + 1).trim()));
                }
            } else {
                writeDefault();
                LOGGER.info("已生成默认配置: {}", FILE);
            }
        } catch (IOException e) {
            LOGGER.warn("读取配置失败，使用默认值: {}", e.getMessage());
        }

        scanIntervalTicks = intOf(values, "scanIntervalTicks", scanIntervalTicks, 20, 72000);
        dropThreshold = intOf(values, "dropThreshold", dropThreshold, 1, 100000);
        countdownSeconds = intOf(values, "countdownSeconds", countdownSeconds, 0, 60);
        clearRadiusChunks = intOf(values, "clearRadiusChunks", clearRadiusChunks, 0, 8);
        notifyRadiusChunks = intOf(values, "notifyRadiusChunks", notifyRadiusChunks, 0, 64);
        mergeMaxStackSize = intOf(values, "mergeMaxStackSize", mergeMaxStackSize, 1, 1_000_000);
        cacheMaxBatches = intOf(values, "cacheMaxBatches", cacheMaxBatches, 1, 10_000);
        cacheMaxItems = intOf(values, "cacheMaxItems", cacheMaxItems, 0, 1_000_000);
        protectNotifyEnabled = boolOf(values, "protectNotifyEnabled", protectNotifyEnabled);
        protectNotifyText = values.getOrDefault("protectNotifyText", protectNotifyText);
        protectNotifyCooldownSeconds = intOf(values, "protectNotifyCooldownSeconds", protectNotifyCooldownSeconds, 0, 86400);
        protectMaxRadiusChunks = intOf(values, "protectMaxRadiusChunks", protectMaxRadiusChunks, 1, 128);
    }

    // ------------------------------------------------------------------ 取值

    public static int scanIntervalTicks() {
        return scanIntervalTicks;
    }

    public static int dropThreshold() {
        return dropThreshold;
    }

    public static int countdownSeconds() {
        return countdownSeconds;
    }

    public static int clearRadiusChunks() {
        return clearRadiusChunks;
    }

    public static int notifyRadiusChunks() {
        return notifyRadiusChunks;
    }

    public static int mergeMaxStackSize() {
        return mergeMaxStackSize;
    }

    public static int cacheMaxBatches() {
        return cacheMaxBatches;
    }

    public static int cacheMaxItems() {
        return cacheMaxItems;
    }

    public static boolean protectNotifyEnabled() {
        return protectNotifyEnabled;
    }

    public static String protectNotifyText() {
        return protectNotifyText;
    }

    public static int protectNotifyCooldownSeconds() {
        return protectNotifyCooldownSeconds;
    }

    public static int protectMaxRadiusChunks() {
        return protectMaxRadiusChunks;
    }

    // ---------------------------------------------------------------- 解析

    private static String stripComment(String line) {
        boolean inQuote = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                inQuote = !inQuote;
            } else if (c == '#' && !inQuote) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    private static int intOf(Map<String, String> values, String key, int fallback, int low, int high) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            if (value < low || value > high) {
                LOGGER.warn("配置项 {} = {} 超出范围 [{}, {}]，使用默认值 {}", key, value, low, high, fallback);
                return fallback;
            }
            return value;
        } catch (NumberFormatException e) {
            LOGGER.warn("配置项 {} 不是整数: {}，使用默认值 {}", key, raw, fallback);
            return fallback;
        }
    }

    private static boolean boolOf(Map<String, String> values, String key, boolean fallback) {
        String raw = values.get(key);
        if (raw == null) {
            return fallback;
        }
        String v = raw.trim().toLowerCase();
        if (v.equals("true")) {
            return true;
        }
        if (v.equals("false")) {
            return false;
        }
        LOGGER.warn("配置项 {} 不是 true/false: {}，使用默认值 {}", key, raw, fallback);
        return fallback;
    }

    private static void writeDefault() throws IOException {
        List<String> lines = List.of(
                "# Drop Cleaner 服务端配置",
                "# 修改后需要重启服务器（配置只在启动时读取一次）",
                "",
                "# 多久扫描一次区块，单位 tick（20 tick = 1 秒）",
                "scanIntervalTicks = 200",
                "",
                "# 一个区块里的掉落物超过这个数量才算“超标”",
                "dropThreshold = 64",
                "",
                "# 倒计时秒数；0 = 不倒计时，直接清理",
                "countdownSeconds = 5",
                "",
                "# 清理由中心区块向四周扩散的半径（区块数）：1 = 3x3",
                "clearRadiusChunks = 1",
                "",
                "# 在这个半径内的玩家会看到倒计时（区块数）",
                "notifyRadiusChunks = 10",
                "",
                "# 相同物品合并成一堆的上限",
                "mergeMaxStackSize = 64",
                "",
                "# 最多保留多少个批次",
                "cacheMaxBatches = 50",
                "",
                "# 最多保留多少堆物品；0 = 不限制",
                "# 注意：一次清理最少产生 dropThreshold 堆，所以这个值应该 >= 批次数 × 阈值",
                "# 默认 3200 = 50 批 × 64 堆，两个上限刚好对齐",
                "cacheMaxItems = 3200",
                "",
                "# 保护区堆积时是否通知管理员",
                "protectNotifyEnabled = true",
                "",
                "# 通知文案：第一个 %s = 保护区名字，第二个 %s = 数量",
                "protectNotifyText = \"保护区「%s」堆积了 %s 堆掉落物，请管理员过来清理\"",
                "",
                "# 同一个保护区两次通知的最小间隔（秒）",
                "protectNotifyCooldownSeconds = 600",
                "",
                "# 一次最多圈多大的保护区（半径，区块数）",
                "protectMaxRadiusChunks = 16"
        );
        Files.createDirectories(FILE.getParent());
        Files.write(FILE, lines, StandardCharsets.UTF_8);
    }
}
