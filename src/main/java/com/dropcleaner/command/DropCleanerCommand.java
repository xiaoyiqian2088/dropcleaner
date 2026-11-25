package com.dropcleaner.command;

import com.dropcleaner.config.DropCleanerConfig;
import com.dropcleaner.data.CleanBatch;
import com.dropcleaner.data.DropCleanerData;
import com.dropcleaner.data.ProtectedZone;
import com.dropcleaner.menu.CacheMenu;
import com.dropcleaner.menu.WhitelistMenu;
import com.dropcleaner.util.ItemDelivery;
import com.dropcleaner.util.TextUtil;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 全部指令。批次按持久编号寻址，而不是列表下标——裁剪旧批次会让下标整体前移。
 *
 * <pre>
 *   /dropcleaner open                      - 打开回收站界面                 (OP2)
 *   /dropcleaner open &lt;id&gt;                 - 只看某个批次                   (OP2)
 *   /dropcleaner list                      - 列出最近批次                   (所有人)
 *   /dropcleaner list &lt;id&gt;                 - 列出该批次内容                 (所有人)
 *   /dropcleaner take &lt;id&gt; &lt;item&gt; [n]      - 按编号取出                     (OP2)
 *   /dropcleaner cleanall [all|force]      - 全服清理                       (OP2)
 *   /dropcleaner whitelist                 - 物品白名单界面                 (OP2)
 *   /dropcleaner protect ...               - 保护区                         (OP2)
 *   /dropcleaner id reset [next]           - 重置批次编号                   (OP2)
 *   /dropcleaner clear [confirm]           - 清空回收站                     (OP2)
 *   /dc ...                                - 别名
 * </pre>
 */
public final class DropCleanerCommand {

    private static final long CONFIRM_WINDOW_MS = 15_000L;
    private static final String CONSOLE_KEY = "server";
    private static final int MAX_LISTED_BATCHES = 10;
    private static final int MAX_LISTED_ITEMS = 54;

    private static final Map<String, PendingClear> PENDING = new HashMap<>();
    private static final Map<UUID, BlockPos[]> SELECTIONS = new HashMap<>();

    private record PendingClear(int batches, int items, long expiresAt) {
    }

    private DropCleanerCommand() {
    }

    public static void register() {
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                .register(server -> {
                    PENDING.clear();
                    SELECTIONS.clear();
                });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(tree("dropcleaner"));
            dispatcher.register(tree("dc"));
        });
    }

    private static LiteralArgumentBuilder<ServerCommandSource> tree(String name) {
        return CommandManager.literal(name)
                .then(CommandManager.literal("open")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(context -> open(context, 0L))
                        .then(CommandManager.argument("batch", IntegerArgumentType.integer(1))
                                .executes(context -> open(context,
                                        IntegerArgumentType.getInteger(context, "batch")))))
                .then(CommandManager.literal("list")
                        .executes(DropCleanerCommand::listBatches)
                        .then(CommandManager.argument("batch", IntegerArgumentType.integer(1))
                                .executes(context -> listItems(context,
                                        IntegerArgumentType.getInteger(context, "batch")))))
                .then(CommandManager.literal("take")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(CommandManager.argument("batch", IntegerArgumentType.integer(1))
                                .then(CommandManager.argument("item", IntegerArgumentType.integer(1))
                                        .executes(context -> take(context,
                                                IntegerArgumentType.getInteger(context, "batch"),
                                                IntegerArgumentType.getInteger(context, "item"), -1))
                                        .then(CommandManager.argument("count", IntegerArgumentType.integer(1))
                                                .executes(context -> take(context,
                                                        IntegerArgumentType.getInteger(context, "batch"),
                                                        IntegerArgumentType.getInteger(context, "item"),
                                                        IntegerArgumentType.getInteger(context, "count")))))))
                .then(CommandManager.literal("cleanall")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(context -> cleanAll(context, false, false))
                        .then(CommandManager.literal("all")
                                .executes(context -> cleanAll(context, true, false)))
                        .then(CommandManager.literal("force")
                                .executes(context -> cleanAll(context, true, true))))
                .then(CommandManager.literal("whitelist")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(DropCleanerCommand::openWhitelist))
                .then(CommandManager.literal("clear")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .executes(DropCleanerCommand::clearRequest)
                        .then(CommandManager.literal("confirm").executes(DropCleanerCommand::clearConfirm)))
                .then(CommandManager.literal("id")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(CommandManager.literal("reset")
                                .executes(context -> resetId(context, 1))
                                .then(CommandManager.argument("next", IntegerArgumentType.integer(1))
                                        .executes(context -> resetId(context,
                                                IntegerArgumentType.getInteger(context, "next"))))))
                .then(CommandManager.literal("protect")
                        .requires(CommandManager.requirePermissionLevel(CommandManager.GAMEMASTERS_CHECK))
                        .then(CommandManager.literal("pos1").executes(context -> setCorner(context, 0)))
                        .then(CommandManager.literal("pos2").executes(context -> setCorner(context, 1)))
                        .then(CommandManager.literal("add")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> protectAdd(context,
                                                StringArgumentType.getString(context, "name"), 0))
                                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 128))
                                                .executes(context -> protectAdd(context,
                                                        StringArgumentType.getString(context, "name"),
                                                        IntegerArgumentType.getInteger(context, "radius"))))))
                        .then(CommandManager.literal("list").executes(DropCleanerCommand::protectList))
                        .then(CommandManager.literal("remove")
                                .then(CommandManager.argument("name", StringArgumentType.word())
                                        .executes(context -> protectRemove(context,
                                                StringArgumentType.getString(context, "name"))))));
    }

    // ------------------------------------------------------------------- 界面

    private static int open(CommandContext<ServerCommandSource> context, long batchId) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("该指令只能由玩家执行"));
            return 0;
        }
        long id = batchId;
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, owner) -> new CacheMenu(syncId, inventory, owner, id),
                TextUtil.of("gui.dropcleaner.title")));
        return 1;
    }

    private static int openWhitelist(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayer();
        if (player == null) {
            source.sendError(Text.literal("该指令只能由玩家执行"));
            return 0;
        }
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                (syncId, inventory, owner) -> new WhitelistMenu(syncId, inventory, owner),
                TextUtil.of("gui.dropcleaner.whitelist.title")));
        return 1;
    }

    // ------------------------------------------------------------------- 列表

    private static int listBatches(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<CleanBatch> batches = DropCleanerData.get(source.getServer()).batches();

        if (batches.isEmpty()) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.empty").formatted(Formatting.GRAY), false);
            return 0;
        }

        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.header", batches.size())
                .formatted(Formatting.GOLD), false);

        int from = Math.max(0, batches.size() - MAX_LISTED_BATCHES);
        if (from > 0) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.truncated",
                    batches.size(), MAX_LISTED_BATCHES).formatted(Formatting.DARK_GRAY), false);
        }

        for (int i = from; i < batches.size(); i++) {
            CleanBatch batch = batches.get(i);
            Formatting color = batch.manual() ? Formatting.RED : Formatting.GRAY;
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.entry",
                            batch.id(), dimension(batch.dimension()), batch.center().getX(),
                            batch.center().getZ(), batch.itemCount(), ago(batch.createdAt()))
                    .formatted(color), false);
        }

        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.hint").formatted(Formatting.DARK_GRAY), false);
        return batches.size();
    }

    private static int listItems(CommandContext<ServerCommandSource> context, int batchId) {
        ServerCommandSource source = context.getSource();
        DropCleanerData data = DropCleanerData.get(source.getServer());
        CleanBatch batch = data.byId(batchId);

        if (batch == null) {
            source.sendError(TextUtil.of("command.dropcleaner.batch_missing", batchId));
            return 0;
        }

        // 完全相同的物品合并后显示，界面里仍按原样一堆一堆放
        List<ItemStack> items = mergedItems(batch.items());
        int shown = Math.min(items.size(), MAX_LISTED_ITEMS);

        Formatting accent = batch.manual() ? Formatting.RED : Formatting.GOLD;
        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.batch_header",
                        batch.id(), dimension(batch.dimension()), batch.center().getX(),
                        batch.center().getZ(), batch.itemCount())
                .formatted(accent), false);

        for (int i = 0; i < shown; i++) {
            ItemStack stack = items.get(i);
            int number = i + 1;
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.item",
                    number, stack.getName(), stack.getCount()).formatted(Formatting.GRAY), false);
        }

        if (items.size() > shown) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.list.items_truncated",
                    items.size(), shown).formatted(Formatting.DARK_GRAY), false);
        }
        return items.size();
    }

    // ------------------------------------------------------------------- 取出

    private static int take(CommandContext<ServerCommandSource> context, int batchId, int itemNumber, int requested)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DropCleanerData data = DropCleanerData.get(source.getServer());
        CleanBatch batch = data.byId(batchId);

        if (batch == null) {
            source.sendError(TextUtil.of("command.dropcleaner.batch_missing", batchId));
            return 0;
        }

        // 编号跟着合并后的列表走，和 list <id> 显示的编号一致
        List<ItemStack> merged = mergedItems(batch.items());
        if (itemNumber < 1 || itemNumber > merged.size()) {
            source.sendError(TextUtil.of("command.dropcleaner.take.bad_item", merged.size()));
            return 0;
        }

        ItemStack target = merged.get(itemNumber - 1);
        Text name = target.getName();
        int amount = requested < 0 ? target.getCount() : Math.min(requested, target.getCount());

        // 一个合并条目可能跨好几堆，逐堆发
        List<ItemStack> raw = batch.items();
        List<Integer> emptied = new ArrayList<>();
        int remaining = amount;
        int given = 0;
        for (int i = 0; i < raw.size() && remaining > 0; i++) {
            ItemStack stack = raw.get(i);
            if (!ItemStack.areItemsAndComponentsEqual(stack, target)) {
                continue;
            }
            int attempt = Math.min(remaining, stack.getCount());
            int moved = ItemDelivery.give(player, stack, attempt);
            given += moved;
            remaining -= moved;
            if (moved >= stack.getCount()) {
                emptied.add(i);
            } else if (moved > 0) {
                stack.decrement(moved);
            }
            if (moved < attempt) {
                break;
            }
        }

        if (given <= 0) {
            source.sendError(TextUtil.of("message.dropcleaner.inventory_full"));
            return 0;
        }

        for (int i = emptied.size() - 1; i >= 0; i--) {
            raw.remove((int) emptied.get(i));
        }
        if (batch.isEmpty()) {
            data.batches().remove(batch);
        }
        data.markDirty();

        if (player.currentScreenHandler instanceof CacheMenu menu) {
            menu.syncFromData();
        }

        final int givenTotal = given;
        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.take.ok", name, givenTotal), false);
        return given;
    }

    // ------------------------------------------------------------- 全服清理

    private static int cleanAll(CommandContext<ServerCommandSource> context, boolean includeProtected,
                                boolean includeWhitelisted) {
        ServerCommandSource source = context.getSource();
        MinecraftServer server = source.getServer();
        DropCleanerData data = DropCleanerData.get(server);
        int mergeCap = DropCleanerConfig.mergeMaxStackSize();

        ServerPlayerEntity player = source.getPlayer();
        BlockPos center = player != null ? player.getBlockPos() : BlockPos.ORIGIN;

        int createdBatches = 0;
        int collectedStacks = 0;

        for (ServerWorld level : server.getWorlds()) {
            String dimension = level.getRegistryKey().getValue().toString();

            List<Entity> snapshot = new ArrayList<>();
            level.iterateEntities().forEach(snapshot::add);

            List<ItemStack> collected = new ArrayList<>();
            for (Entity entity : snapshot) {
                if (!(entity instanceof ItemEntity item) || !item.isAlive()) {
                    continue;
                }
                if (!includeProtected) {
                    ChunkPos pos = item.getChunkPos();
                    if (data.isProtected(dimension, pos.x, pos.z)) {
                        continue;
                    }
                }
                if (!includeWhitelisted && data.isWhitelisted(item.getStack())) {
                    continue; // 白名单默认连 cleanall all 也不动，只有 force 才越过
                }
                ItemStack stack = item.getStack().copy();
                if (!stack.isEmpty()) {
                    collected.add(stack);
                }
                item.discard();
            }

            if (collected.isEmpty()) {
                continue;
            }

            data.createBatch(System.currentTimeMillis(), dimension, center,
                    CleanBatch.merge(collected, mergeCap), true);
            createdBatches++;
            collectedStacks += collected.size();
        }

        if (createdBatches == 0) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.cleanall.empty").formatted(Formatting.GRAY), false);
            return 0;
        }

        final int batches = createdBatches;
        final int stacks = collectedStacks;
        String key = includeWhitelisted
                ? "command.dropcleaner.cleanall.done_force"
                : includeProtected
                        ? "command.dropcleaner.cleanall.done_all"
                        : "command.dropcleaner.cleanall.done";
        source.sendFeedback(() -> TextUtil.of(key, batches, stacks).formatted(Formatting.RED), true);
        return batches;
    }

    // ------------------------------------------------------------------- 清空

    private static int clearRequest(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        DropCleanerData data = DropCleanerData.get(source.getServer());

        if (data.batches().isEmpty()) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.cache_empty").formatted(Formatting.GRAY), false);
            return 0;
        }

        int batches = data.batches().size();
        int items = data.totalItems();
        PENDING.put(keyOf(source), new PendingClear(batches, items, System.currentTimeMillis() + CONFIRM_WINDOW_MS));

        // 原版客户端就支持点击执行命令和悬停提示，不需要装任何东西
        MutableText message = TextUtil.of("command.dropcleaner.clear_confirm_prompt",
                        batches, items, CONFIRM_WINDOW_MS / 1000)
                .formatted(Formatting.RED)
                .styled(style -> style
                        .withClickEvent(new ClickEvent.RunCommand("/dropcleaner clear confirm"))
                        .withHoverEvent(new HoverEvent.ShowText(Text.literal("点击确认清空"))));
        source.sendFeedback(() -> message, false);
        return 1;
    }

    private static int clearConfirm(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        String key = keyOf(source);
        PendingClear pending = PENDING.get(key);

        if (pending == null || System.currentTimeMillis() > pending.expiresAt()) {
            PENDING.remove(key);
            source.sendError(TextUtil.of("command.dropcleaner.clear_no_pending"));
            return 0;
        }
        PENDING.remove(key);

        DropCleanerData data = DropCleanerData.get(source.getServer());
        data.clearAll();
        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.cache_cleared",
                pending.batches(), pending.items()).formatted(Formatting.GRAY), true);
        return 1;
    }

    // ------------------------------------------------------------------- 编号

    private static int resetId(CommandContext<ServerCommandSource> context, int requested) {
        ServerCommandSource source = context.getSource();
        DropCleanerData data = DropCleanerData.get(source.getServer());
        long next = data.resetNextId(requested);
        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.id.reset", next), true);
        return (int) Math.min(Integer.MAX_VALUE, next);
    }

    // ----------------------------------------------------------------- 保护区

    private static int setCorner(CommandContext<ServerCommandSource> context, int index)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos pos = player.getBlockPos();

        BlockPos[] corners = SELECTIONS.computeIfAbsent(player.getUuid(), key -> new BlockPos[2]);
        corners[index] = pos;

        String key = index == 0 ? "command.dropcleaner.protect.pos1" : "command.dropcleaner.protect.pos2";
        source.sendFeedback(() -> TextUtil.of(key, pos.getX(), pos.getY(), pos.getZ()), false);
        return 1;
    }

    private static int protectAdd(CommandContext<ServerCommandSource> context, String name, int radius)
            throws CommandSyntaxException {
        ServerCommandSource source = context.getSource();
        ServerPlayerEntity player = source.getPlayerOrThrow();
        DropCleanerData data = DropCleanerData.get(source.getServer());

        if (data.zoneByName(name) != null) {
            source.sendError(TextUtil.of("command.dropcleaner.protect.duplicate", name));
            return 0;
        }

        int limit = DropCleanerConfig.protectMaxRadiusChunks();
        ChunkPos here = player.getChunkPos();
        int minX;
        int minZ;
        int maxX;
        int maxZ;

        if (radius > 0) {
            int r = Math.min(radius, limit);
            minX = here.x - r;
            maxX = here.x + r;
            minZ = here.z - r;
            maxZ = here.z + r;
        } else {
            BlockPos[] corners = SELECTIONS.get(player.getUuid());
            if (corners != null && corners[0] != null && corners[1] != null) {
                int x1 = corners[0].getX() >> 4;
                int z1 = corners[0].getZ() >> 4;
                int x2 = corners[1].getX() >> 4;
                int z2 = corners[1].getZ() >> 4;
                minX = Math.min(x1, x2);
                maxX = Math.max(x1, x2);
                minZ = Math.min(z1, z2);
                maxZ = Math.max(z1, z2);
            } else {
                minX = here.x;
                maxX = here.x;
                minZ = here.z;
                maxZ = here.z;
            }
        }

        String dimension = player.getEntityWorld().getRegistryKey().getValue().toString();
        ProtectedZone zone = ProtectedZone.ofChunks(name, dimension, minX, minZ, maxX, maxZ);

        int maxChunks = (2 * limit + 1) * (2 * limit + 1);
        if (zone.chunkCount() > maxChunks) {
            source.sendError(TextUtil.of("command.dropcleaner.protect.too_big", maxChunks));
            return 0;
        }

        data.addZone(zone);
        SELECTIONS.remove(player.getUuid());

        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.protect.added", zone.name(),
                dimension(zone.dimension()), zone.minChunkX(), zone.minChunkZ(),
                zone.maxChunkX(), zone.maxChunkZ(), zone.chunkCount()), false);
        return 1;
    }

    private static int protectList(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        List<ProtectedZone> zones = DropCleanerData.get(source.getServer()).zones();

        if (zones.isEmpty()) {
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.protect.list.empty")
                    .formatted(Formatting.GRAY), false);
            return 0;
        }

        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.protect.list.header", zones.size())
                .formatted(Formatting.GOLD), false);

        for (int i = 0; i < zones.size(); i++) {
            ProtectedZone zone = zones.get(i);
            int index = i + 1;
            source.sendFeedback(() -> TextUtil.of("command.dropcleaner.protect.list.entry", index, zone.name(),
                            dimension(zone.dimension()), zone.minChunkX(), zone.minChunkZ(),
                            zone.maxChunkX(), zone.maxChunkZ(), zone.chunkCount())
                    .formatted(Formatting.GRAY), false);
        }
        return zones.size();
    }

    private static int protectRemove(CommandContext<ServerCommandSource> context, String name) {
        ServerCommandSource source = context.getSource();
        DropCleanerData data = DropCleanerData.get(source.getServer());

        if (!data.removeZone(name)) {
            source.sendError(TextUtil.of("command.dropcleaner.protect.not_found", name));
            return 0;
        }
        source.sendFeedback(() -> TextUtil.of("command.dropcleaner.protect.removed", name), true);
        return 1;
    }

    // ------------------------------------------------------------------- 工具

    /** 只用于显示：完全相同的物品相加，数量可以超过 99。 */
    private static List<ItemStack> mergedItems(List<ItemStack> items) {
        List<ItemStack> merged = new ArrayList<>();
        for (ItemStack stack : items) {
            if (stack.isEmpty()) {
                continue;
            }
            ItemStack same = null;
            for (ItemStack candidate : merged) {
                if (ItemStack.areItemsAndComponentsEqual(candidate, stack)) {
                    same = candidate;
                    break;
                }
            }
            if (same == null) {
                merged.add(stack.copy());
            } else {
                same.increment(stack.getCount());
            }
        }
        return merged;
    }

    private static Text dimension(String dimensionId) {
        return switch (dimensionId) {
            case "minecraft:overworld" -> TextUtil.of("dimension.dropcleaner.overworld");
            case "minecraft:the_nether" -> TextUtil.of("dimension.dropcleaner.nether");
            case "minecraft:the_end" -> TextUtil.of("dimension.dropcleaner.end");
            default -> Text.literal(dimensionId);
        };
    }

    private static Text ago(long createdAt) {
        long seconds = Math.max(0L, (System.currentTimeMillis() - createdAt) / 1000L);
        if (seconds < 60) {
            return TextUtil.of("command.dropcleaner.time.just_now");
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return TextUtil.of("command.dropcleaner.time.minutes", minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return TextUtil.of("command.dropcleaner.time.hours", hours);
        }
        return TextUtil.of("command.dropcleaner.time.days", hours / 24);
    }

    private static String keyOf(ServerCommandSource source) {
        ServerPlayerEntity player = source.getPlayer();
        return player != null ? player.getUuid().toString() : CONSOLE_KEY;
    }
}
