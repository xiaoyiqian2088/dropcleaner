package com.dropcleaner.logic;

import com.dropcleaner.DropCleanerMod;
import com.dropcleaner.config.DropCleanerConfig;
import com.dropcleaner.data.CleanBatch;
import com.dropcleaner.data.DropCleanerData;
import com.dropcleaner.data.ProtectedZone;
import com.dropcleaner.util.TextUtil;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * 整条清理流程：扫描 → 倒计时 → 清理 → 进缓存。
 *
 * <p>状态按（维度, 区块）区分，两个维度不会共用一个倒计时。全部跑在服务端主线程，
 * 遍历实体时先做快照，避免 ConcurrentModificationException。</p>
 *
 * <p>通知策略：</p>
 * <ul>
 *   <li>在通知半径内的玩家，每秒看到一次带坐标的动作栏倒计时；</li>
 *   <li>半径内没人时，这堆东西多半是区块加载器造的农场，直接清，不倒计时；</li>
 *   <li>清理完成的广播是全服跨维度的，音效只在通知半径内。</li>
 * </ul>
 */
public final class DropCleanerManager {

    /** 跨维度的区块身份。 */
    private record ChunkKey(RegistryKey<World> dimension, long packedChunk) {
    }

    /** 正在跑的倒计时，单位 tick。 */
    private record Countdown(int totalTicks, int ticksLeft) {
    }

    private static final Map<ChunkKey, Countdown> COUNTDOWNS = new HashMap<>();

    /** 每个保护区上次通知的时间，避免每次扫描都刷屏。 */
    private static final Map<String, Long> PROTECT_NOTICES = new HashMap<>();

    private DropCleanerManager() {
    }

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(DropCleanerManager::onServerTick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            COUNTDOWNS.clear();
            PROTECT_NOTICES.clear();
        });
    }

    private static void onServerTick(MinecraftServer server) {
        tickCountdowns(server);

        int interval = DropCleanerConfig.scanIntervalTicks();
        if (interval > 0 && server.getTicks() % interval == 0) {
            scan(server);
        }
    }

    // ------------------------------------------------------------------ 扫描

    private static void scan(MinecraftServer server) {
        int threshold = DropCleanerConfig.dropThreshold();
        DropCleanerData data = DropCleanerData.get(server);

        // 缓存过期：只在开关打开、且时间上限非 0 时生效
        if (DropCleanerConfig.cacheExpireEnabled()) {
            int seconds = DropCleanerConfig.cacheExpireSeconds();
            if (seconds > 0) {
                long cutoff = System.currentTimeMillis() - seconds * 1000L;
                int removed = data.expireBefore(cutoff);
                if (removed > 0) {
                    DropCleanerMod.LOGGER.info("缓存过期：删除了 {} 个超过 {} 秒的批次", removed, seconds);
                }
            }
        }

        for (ServerWorld level : server.getWorlds()) {
            String dimension = level.getRegistryKey().getValue().toString();
            Map<Long, Integer> counts = new HashMap<>();

            for (Entity entity : snapshot(level)) {
                if (!(entity instanceof ItemEntity item) || !item.isAlive() || item.getStack().isEmpty()) {
                    continue;
                }
                if (data.isWhitelisted(item.getStack())) {
                    continue; // 白名单里的物品不参与统计
                }
                ChunkPos pos = item.getChunkPos();
                counts.merge(ChunkPos.toLong(pos.x, pos.z), 1, Integer::sum);
            }

            for (Map.Entry<Long, Integer> entry : counts.entrySet()) {
                int count = entry.getValue();
                if (count <= threshold) {
                    continue;
                }
                ChunkPos pos = new ChunkPos(entry.getKey());
                ProtectedZone zone = data.zoneAt(dimension, pos.x, pos.z);
                if (zone != null) {
                    notifyProtected(server, zone, count);
                    continue;
                }
                ChunkKey key = new ChunkKey(level.getRegistryKey(), entry.getKey());
                if (COUNTDOWNS.containsKey(key)) {
                    continue;
                }
                startCountdown(server, level, key);
            }
        }
    }

    private static List<Entity> snapshot(ServerWorld level) {
        List<Entity> copy = new ArrayList<>();
        level.iterateEntities().forEach(copy::add);
        return copy;
    }

    private static void startCountdown(MinecraftServer server, ServerWorld level, ChunkKey key) {
        ChunkPos center = new ChunkPos(key.packedChunk());
        int seconds = DropCleanerConfig.countdownSeconds();

        if (seconds <= 0 || !hasPlayersAround(level, center)) {
            clear(server, key);
            return;
        }

        COUNTDOWNS.put(key, new Countdown(seconds * 20, seconds * 20));
        notifyAround(level, center, seconds);
        playAround(level, center, SoundEvents.BLOCK_NOTE_BLOCK_BASS.value(), 0.8F, 0.9F);
    }

    private static boolean hasPlayersAround(ServerWorld level, ChunkPos center) {
        int radius = DropCleanerConfig.notifyRadiusChunks();
        for (ServerPlayerEntity player : level.getPlayers()) {
            if (chunkDistance(player.getChunkPos(), center) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** 切比雪夫距离，和受影响区域的方形边界一致。 */
    private static int chunkDistance(ChunkPos a, ChunkPos b) {
        return Math.max(Math.abs(a.x - b.x), Math.abs(a.z - b.z));
    }

    // ---------------------------------------------------------------- 倒计时

    private static void tickCountdowns(MinecraftServer server) {
        Iterator<Map.Entry<ChunkKey, Countdown>> it = COUNTDOWNS.entrySet().iterator();
        List<ChunkKey> finished = new ArrayList<>();

        while (it.hasNext()) {
            Map.Entry<ChunkKey, Countdown> entry = it.next();
            ChunkKey key = entry.getKey();
            Countdown countdown = entry.getValue();

            int left = countdown.ticksLeft() - 1;
            if (left <= 0) {
                it.remove();
                finished.add(key);
                continue;
            }

            if (left % 20 == 0) {
                ServerWorld level = server.getWorld(key.dimension());
                if (level != null) {
                    notifyAround(level, new ChunkPos(key.packedChunk()), left / 20);
                }
            }
            entry.setValue(new Countdown(countdown.totalTicks(), left));
        }

        for (ChunkKey key : finished) {
            clear(server, key);
        }
    }

    private static void notifyAround(ServerWorld level, ChunkPos center, int secondsLeft) {
        int radius = DropCleanerConfig.notifyRadiusChunks();
        Text message = TextUtil.of("message.dropcleaner.countdown",
                secondsLeft, center.getCenterX(), center.getCenterZ()).formatted(Formatting.YELLOW);

        for (ServerPlayerEntity player : level.getPlayers()) {
            if (chunkDistance(player.getChunkPos(), center) <= radius) {
                player.sendMessage(message, true); // true = 动作栏
            }
        }
    }

    private static void playAround(ServerWorld level, ChunkPos center, SoundEvent sound, float volume, float pitch) {
        int radius = DropCleanerConfig.notifyRadiusChunks();
        for (ServerPlayerEntity player : level.getPlayers()) {
            if (chunkDistance(player.getChunkPos(), center) <= radius) {
                // 用 playSoundToPlayer：普通音效 16 格就衰减没了，而通知半径可能有 160 格
                level.playSound(null, player.getX(), player.getY(), player.getZ(), sound, SoundCategory.MASTER, volume, pitch);
            }
        }
    }

    // ---------------------------------------------------------------- 保护区

    private static void notifyProtected(MinecraftServer server, ProtectedZone zone, int count) {
        if (!DropCleanerConfig.protectNotifyEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        long cooldown = Math.max(0, DropCleanerConfig.protectNotifyCooldownSeconds()) * 1000L;
        Long last = PROTECT_NOTICES.get(zone.name());
        if (last != null && now - last < cooldown) {
            return;
        }
        PROTECT_NOTICES.put(zone.name(), now);

        Text message = Text.literal(fill(DropCleanerConfig.protectNotifyText(), zone.name(), count))
                .formatted(Formatting.YELLOW);

        List<ServerPlayerEntity> operators = new ArrayList<>();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (CommandManager.GAMEMASTERS_CHECK.allows(player.getPermissions())) {
                operators.add(player);
            }
        }
        if (operators.isEmpty()) {
            server.getPlayerManager().broadcast(message, false);
        } else {
            for (ServerPlayerEntity operator : operators) {
                operator.sendMessage(message);
            }
        }
    }

    /** 依次替换 {@code %s}，配置文案写错也不会抛异常。 */
    private static String fill(String template, Object... args) {
        StringBuilder out = new StringBuilder(template.length() + 16);
        int from = 0;
        int used = 0;
        while (from < template.length()) {
            int at = template.indexOf("%s", from);
            if (at < 0 || used >= args.length) {
                out.append(template, from, template.length());
                break;
            }
            out.append(template, from, at).append(args[used++]);
            from = at + 2;
        }
        return out.toString();
    }

    // ------------------------------------------------------------------ 清理

    private static void clear(MinecraftServer server, ChunkKey key) {
        ServerWorld level = server.getWorld(key.dimension());
        if (level == null) {
            return;
        }

        DropCleanerData data = DropCleanerData.get(server);
        String dimension = level.getRegistryKey().getValue().toString();

        ChunkPos center = new ChunkPos(key.packedChunk());
        int radius = DropCleanerConfig.clearRadiusChunks();
        int minX = center.x - radius;
        int maxX = center.x + radius;
        int minZ = center.z - radius;
        int maxZ = center.z + radius;

        List<ItemEntity> targets = new ArrayList<>();
        for (Entity entity : snapshot(level)) {
            if (!(entity instanceof ItemEntity item) || !item.isAlive()) {
                continue;
            }
            ChunkPos pos = item.getChunkPos();
            if (pos.x >= minX && pos.x <= maxX && pos.z >= minZ && pos.z <= maxZ) {
                if (data.isProtected(dimension, pos.x, pos.z)) {
                    continue; // 保护区优先于清理半径
                }
                if (data.isWhitelisted(item.getStack())) {
                    continue;
                }
                targets.add(item);
            }
        }

        if (targets.isEmpty()) {
            return;
        }

        List<ItemStack> collected = new ArrayList<>(targets.size());
        for (ItemEntity item : targets) {
            ItemStack stack = item.getStack().copy();
            if (!stack.isEmpty()) {
                collected.add(stack);
            }
            item.discard();
        }

        List<ItemStack> merged = CleanBatch.merge(collected, DropCleanerConfig.mergeMaxStackSize());

        BlockPos centerPos = new BlockPos(center.getCenterX(), level.getSeaLevel(), center.getCenterZ());
        data.createBatch(System.currentTimeMillis(), dimension, centerPos, merged, false);

        Text message = TextUtil.of("message.dropcleaner.cleared",
                dimensionName(level), centerPos.getX(), centerPos.getZ(), collected.size())
                .formatted(Formatting.GRAY);
        server.getPlayerManager().broadcast(message, false);

        playAround(level, center, SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 0.9F, 1.4F);

        DropCleanerMod.LOGGER.info("已清理 {} 个掉落物，中心区块 {}，维度 {}",
                collected.size(), center, dimension);
    }

    private static Text dimensionName(ServerWorld level) {
        String id = level.getRegistryKey().getValue().toString();
        return switch (id) {
            case "minecraft:overworld" -> TextUtil.of("dimension.dropcleaner.overworld");
            case "minecraft:the_nether" -> TextUtil.of("dimension.dropcleaner.nether");
            case "minecraft:the_end" -> TextUtil.of("dimension.dropcleaner.end");
            default -> Text.literal(id);
        };
    }
}
