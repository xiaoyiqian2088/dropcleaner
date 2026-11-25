package com.dropcleaner.data;

import com.dropcleaner.config.DropCleanerConfig;
import com.mojang.serialization.Codec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.mojang.serialization.Dynamic;
import com.mojang.serialization.DynamicOps;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryOps;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;
import net.minecraft.world.PersistentStateType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 世界级的清理缓存，存在 {@code <world>/data/dropcleaner_cache.dat}。
 *
 * <p>同时保存三样东西：批次、保护区、物品白名单。旧存档没有这些字段时按空处理。</p>
 */
public class DropCleanerData extends PersistentState {

    private static final String DATA_NAME = "dropcleaner_cache";

    private static final Logger LOGGER = LoggerFactory.getLogger("dropcleaner");

    private final List<CleanBatch> batches = new ArrayList<>();

    /** 从不下手的区块矩形。 */
    private final List<ProtectedZone> zones = new ArrayList<>();

    /** 永不清掉的物品 ID（例如 {@code minecraft:shulker_box}），全局生效。 */
    private final Set<String> whitelist = new LinkedHashSet<>();

    /** 下一个批次编号，永不复用。 */
    private long nextId = 1L;

    /** 读档时拿到的注册表 ops，写档时复用（物品组件序列化需要注册表）。 */
    private static DynamicOps<NbtElement> ops = NbtOps.INSTANCE;

    public static final Codec<DropCleanerData> CODEC = Codec.PASSTHROUGH.xmap(
            dynamic -> fromDynamic(dynamic),
            data -> data.toDynamic());

    private static final PersistentStateType<DropCleanerData> TYPE =
            new PersistentStateType<>(DATA_NAME, DropCleanerData::new, CODEC, DataFixTypes.LEVEL);

    public static DropCleanerData get(MinecraftServer server) {
        DropCleanerData data = server.getOverworld().getPersistentStateManager().getOrCreate(TYPE);
        // 新世界还没读过存档时 ops 还是裸 NbtOps，物品组件需要注册表，所以每次都注入一次
        ops = RegistryOps.of(NbtOps.INSTANCE, server.getRegistryManager());
        return data;
    }

    /** 停服后释放注册表引用。 */
    public static void releaseRegistry() {
        ops = NbtOps.INSTANCE;
    }

    /** 把物品编码成 NBT（数量单独存）。编码失败只记日志，不影响整次保存。 */
    private static Optional<NbtElement> encodeStack(ItemStack stack) {
        return ItemStack.CODEC.encodeStart(ops, stack)
                .resultOrPartial(err -> LOGGER.warn("物品编码失败，已跳过: {} ({})", stack, err));
    }

    /** 从 NBT 解析物品。解析失败只记日志，不中断读档。 */
    private static Optional<ItemStack> decodeStack(NbtElement element) {
        if (element == null) {
            return Optional.empty();
        }
        return ItemStack.CODEC.parse(ops, element)
                .resultOrPartial(err -> LOGGER.warn("物品解析失败，已跳过: {}", err));
    }

    public DropCleanerData() {
    }

    // ---------------------------------------------------------------- 批次

    public List<CleanBatch> batches() {
        return batches;
    }

    public int totalItems() {
        int total = 0;
        for (CleanBatch batch : batches) {
            total += batch.itemCount();
        }
        return total;
    }

    public CleanBatch createBatch(long createdAt, String dimension, BlockPos center, List<ItemStack> items) {
        return createBatch(createdAt, dimension, center, items, false);
    }

    public CleanBatch createBatch(long createdAt, String dimension, BlockPos center, List<ItemStack> items,
                                  boolean manual) {
        CleanBatch batch = manual
                ? CleanBatch.manual(nextId++, createdAt, dimension, center, items)
                : CleanBatch.of(nextId++, createdAt, dimension, center, items);
        if (batch.isEmpty()) {
            return batch;
        }
        batches.add(batch);
        trim();
        markDirty();
        return batch;
    }

    public CleanBatch byId(long id) {
        for (CleanBatch batch : batches) {
            if (batch.id() == id) {
                return batch;
            }
        }
        return null;
    }

    /** 编号重置：下一个新批次用 {@code requested}（被占用则往后顺延），已有批次不动。 */
    public long resetNextId(long requested) {
        long candidate = Math.max(1L, requested);
        while (true) {
            boolean taken = false;
            for (CleanBatch batch : batches) {
                if (batch.id() == candidate) {
                    taken = true;
                    break;
                }
            }
            if (!taken) {
                break;
            }
            candidate++;
        }
        nextId = candidate;
        markDirty();
        return nextId;
    }

    /**
     * 删除早于给定时间的批次（缓存过期）。
     *
     * @param cutoffMillis 早于这个时间点创建的批次会被删掉
     * @return 删掉的批次数
     */
    public int expireBefore(long cutoffMillis) {
        int before = batches.size();
        batches.removeIf(batch -> batch.createdAt() < cutoffMillis);
        int removed = before - batches.size();
        if (removed > 0) {
            markDirty();
        }
        return removed;
    }

    /** 同时执行「最多多少批次」和「最多多少堆」，都是先丢最旧的。 */
    public void trim() {
        int maxBatches = DropCleanerConfig.cacheMaxBatches();
        // 0 = 不按批次数限制（堆数上限仍然生效）
        if (maxBatches > 0) {
            while (batches.size() > maxBatches) {
                batches.remove(0);
            }
        }

        int maxItems = DropCleanerConfig.cacheMaxItems();
        if (maxItems <= 0) {
            return;
        }
        while (totalItems() > maxItems && !batches.isEmpty()) {
            CleanBatch oldest = batches.get(0);
            int overflow = totalItems() - maxItems;
            if (oldest.itemCount() <= overflow) {
                batches.remove(0);
            } else {
                int keep = oldest.itemCount() - overflow;
                while (oldest.items().size() > keep) {
                    oldest.items().remove(oldest.items().size() - 1);
                }
            }
        }
    }

    public void removeItem(CleanBatch batch, int index) {
        if (index < 0 || index >= batch.items().size()) {
            return;
        }
        batch.items().remove(index);
        if (batch.isEmpty()) {
            batches.remove(batch);
        }
        markDirty();
    }

    public void clearAll() {
        if (batches.isEmpty()) {
            return;
        }
        batches.clear();
        markDirty();
    }

    // -------------------------------------------------------------- 保护区

    public List<ProtectedZone> zones() {
        return zones;
    }

    public ProtectedZone zoneByName(String name) {
        for (ProtectedZone zone : zones) {
            if (zone.name().equalsIgnoreCase(name)) {
                return zone;
            }
        }
        return null;
    }

    public ProtectedZone zoneAt(String dimensionId, int chunkX, int chunkZ) {
        for (ProtectedZone zone : zones) {
            if (zone.contains(dimensionId, chunkX, chunkZ)) {
                return zone;
            }
        }
        return null;
    }

    public boolean isProtected(String dimensionId, int chunkX, int chunkZ) {
        return zoneAt(dimensionId, chunkX, chunkZ) != null;
    }

    public void addZone(ProtectedZone zone) {
        zones.add(zone);
        markDirty();
    }

    public boolean removeZone(String name) {
        ProtectedZone zone = zoneByName(name);
        if (zone == null) {
            return false;
        }
        zones.remove(zone);
        markDirty();
        return true;
    }

    // -------------------------------------------------------------- 白名单

    public Set<String> whitelist() {
        return whitelist;
    }

    public boolean isWhitelisted(String itemId) {
        return whitelist.contains(itemId);
    }

    public boolean isWhitelisted(ItemStack stack) {
        if (whitelist.isEmpty() || stack.isEmpty()) {
            return false;
        }
        return whitelist.contains(net.minecraft.registry.Registries.ITEM.getId(stack.getItem()).toString());
    }

    public boolean addWhitelist(String itemId) {
        if (!whitelist.add(itemId)) {
            return false;
        }
        markDirty();
        return true;
    }

    public boolean removeWhitelist(String itemId) {
        if (!whitelist.remove(itemId)) {
            return false;
        }
        markDirty();
        return true;
    }


    // -------------------------------------------------------------- 存档

    private Dynamic<?> toDynamic() {
        NbtCompound tag = new NbtCompound();
        tag.putLong("next_id", nextId);

        NbtList list = new NbtList();
        for (CleanBatch batch : batches) {
            NbtCompound entry = new NbtCompound();
            entry.putLong("id", batch.id());
            entry.putLong("created_at", batch.createdAt());
            entry.putString("dimension", batch.dimension());
            entry.putInt("x", batch.center().getX());
            entry.putInt("y", batch.center().getY());
            entry.putInt("z", batch.center().getZ());
            entry.putBoolean("manual", batch.manual());

            NbtList items = new NbtList();
            for (ItemStack stack : batch.items()) {
                if (stack.isEmpty()) {
                    continue;
                }
                // 只存「单个物品模板 + 数量」，避免造出超过上限的非法栈
                Optional<NbtElement> encoded = encodeStack(stack.copyWithCount(1));
                if (encoded.isEmpty()) {
                    continue;
                }
                NbtCompound stored = new NbtCompound();
                stored.put("item", encoded.get());
                stored.putInt("amount", stack.getCount());
                items.unwrapAndAdd(stored);
            }
            entry.put("items", items);
            list.unwrapAndAdd(entry);
        }
        tag.put("batches", list);

        NbtList zoneList = new NbtList();
        for (ProtectedZone zone : zones) {
            NbtCompound entry = new NbtCompound();
            entry.putString("name", zone.name());
            entry.putString("dimension", zone.dimension());
            entry.putInt("min_x", zone.minChunkX());
            entry.putInt("min_z", zone.minChunkZ());
            entry.putInt("max_x", zone.maxChunkX());
            entry.putInt("max_z", zone.maxChunkZ());
            zoneList.unwrapAndAdd(entry);
        }
        tag.put("zones", zoneList);

        NbtList whiteList = new NbtList();
        for (String itemId : whitelist) {
            whiteList.unwrapAndAdd(NbtString.of(itemId));
        }
        tag.put("whitelist", whiteList);

        return new Dynamic<>(ops, tag);
    }

    @SuppressWarnings("unchecked")
    private static DropCleanerData fromDynamic(Dynamic<?> dynamic) {
        ops = (DynamicOps<NbtElement>) dynamic.getOps();
        if (!(dynamic.getValue() instanceof NbtCompound tag)) {
            LOGGER.warn("存档格式异常，本次按空缓存处理");
            return new DropCleanerData();
        }

        DropCleanerData data = new DropCleanerData();
        long declaredNext = tag.getLong("next_id", 0L);
        long maxId = 0L;

        NbtList list = tag.getListOrEmpty("batches");
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompoundOrEmpty(i);
            BlockPos center = new BlockPos(entry.getInt("x", 0), entry.getInt("y", 0), entry.getInt("z", 0));

            long id = entry.getLong("id", 0L);
            if (id <= 0L) {
                id = maxId + 1L;
            }
            maxId = Math.max(maxId, id);

            List<ItemStack> items = new ArrayList<>();
            NbtList itemsTag = entry.getListOrEmpty("items");
            for (int j = 0; j < itemsTag.size(); j++) {
                NbtCompound stored = itemsTag.getCompoundOrEmpty(j);
                if (stored.contains("item")) {
                    Optional<ItemStack> parsed = decodeStack(stored.get("item"));
                    if (parsed.isEmpty() || parsed.get().isEmpty()) {
                        continue;
                    }
                    ItemStack stack = parsed.get();
                    int amount = Math.max(1, stored.getInt("amount", 1));
                    int max = Math.max(1, stack.getMaxCount());
                    // 损坏/恶意存档里的超大数量：直接 clamp，避免读档时爆内存
                    int cap = max * 4096;
                    if (amount > cap) {
                        LOGGER.warn("缓存里的物品数量异常（{} 个），已限制为 {}: {}", amount, cap, stack);
                        amount = cap;
                    }
                    // 旧版本可能存过超过物品自身上限的堆（比如 64 把剑），读出来时拆成合法的小堆
                    while (amount > max) {
                        items.add(stack.copyWithCount(max));
                        amount -= max;
                    }
                    stack.setCount(amount);
                    items.add(stack);
                } else {
                    decodeStack(stored).filter(s -> !s.isEmpty()).ifPresent(items::add);
                }
            }

            data.batches.add(new CleanBatch(id, entry.getLong("created_at", 0L),
                    entry.getString("dimension", ""), center, items, entry.getBoolean("manual", false)));
        }

        data.nextId = Math.max(declaredNext, maxId + 1L);

        NbtList zoneList = tag.getListOrEmpty("zones");
        for (int i = 0; i < zoneList.size(); i++) {
            NbtCompound entry = zoneList.getCompoundOrEmpty(i);
            String name = entry.getString("name", "");
            if (name.isEmpty()) {
                continue;
            }
            data.zones.add(ProtectedZone.ofChunks(name, entry.getString("dimension", ""),
                    entry.getInt("min_x", 0), entry.getInt("min_z", 0),
                    entry.getInt("max_x", 0), entry.getInt("max_z", 0)));
        }

        NbtList whiteList = tag.getListOrEmpty("whitelist");
        for (int i = 0; i < whiteList.size(); i++) {
            String itemId = whiteList.getString(i, "");
            if (!itemId.isEmpty()) {
                data.whitelist.add(itemId);
            }
        }
        return data;
    }
}