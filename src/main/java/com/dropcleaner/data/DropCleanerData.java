package com.dropcleaner.data;

import com.dropcleaner.config.DropCleanerConfig;
import net.minecraft.datafixer.DataFixTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtElement;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtString;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.PersistentState;

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

    private final List<CleanBatch> batches = new ArrayList<>();

    /** 从不下手的区块矩形。 */
    private final List<ProtectedZone> zones = new ArrayList<>();

    /** 永不清掉的物品 ID（例如 {@code minecraft:shulker_box}），全局生效。 */
    private final Set<String> whitelist = new LinkedHashSet<>();

    /** 下一个批次编号，永不复用。 */
    private long nextId = 1L;

    public static PersistentState.Type<DropCleanerData> type() {
        return new PersistentState.Type<>(DropCleanerData::new, DropCleanerData::fromNbt, DataFixTypes.LEVEL);
    }

    public static DropCleanerData get(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(type(), DATA_NAME);
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

    /** 同时执行「最多多少批次」和「最多多少堆」，都是先丢最旧的。 */
    public void trim() {
        int maxBatches = DropCleanerConfig.cacheMaxBatches();
        while (batches.size() > maxBatches) {
            batches.remove(0);
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

    @Override
    public NbtCompound writeNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registries) {
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
                // 合并后的堆可能有上千个，而 1.21.1 编码时强校验数量，所以只存「单个物品模板 + 数量」
                NbtCompound stored = new NbtCompound();
                stored.put("item", stack.copyWithCount(1).encode(registries));
                stored.putInt("amount", stack.getCount());
                items.add(stored);
            }
            entry.put("items", items);
            list.add(entry);
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
            zoneList.add(entry);
        }
        tag.put("zones", zoneList);

        NbtList whiteList = new NbtList();
        for (String itemId : whitelist) {
            whiteList.add(NbtString.of(itemId));
        }
        tag.put("whitelist", whiteList);
        return tag;
    }

    private static DropCleanerData fromNbt(NbtCompound tag, RegistryWrapper.WrapperLookup registries) {
        DropCleanerData data = new DropCleanerData();
        long declaredNext = tag.contains("next_id") ? tag.getLong("next_id") : 0L;
        long maxId = 0L;

        NbtList list = tag.getList("batches", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < list.size(); i++) {
            NbtCompound entry = list.getCompound(i);
            BlockPos center = new BlockPos(entry.getInt("x"), entry.getInt("y"), entry.getInt("z"));

            long id = entry.contains("id") ? entry.getLong("id") : 0L;
            if (id <= 0L) {
                id = maxId + 1L;
            }
            maxId = Math.max(maxId, id);

            List<ItemStack> items = new ArrayList<>();
            NbtList itemsTag = entry.getList("items", NbtElement.COMPOUND_TYPE);
            for (int j = 0; j < itemsTag.size(); j++) {
                NbtCompound stored = itemsTag.getCompound(j);
                if (stored.contains("item", NbtElement.COMPOUND_TYPE)) {
                    Optional<ItemStack> parsed = ItemStack.fromNbt(registries, stored.get("item"));
                    if (parsed.isEmpty() || parsed.get().isEmpty()) {
                        continue;
                    }
                    ItemStack stack = parsed.get();
                    int amount = Math.max(1, stored.getInt("amount"));
                    // 旧版本可能存过超过物品自身上限的堆（比如 64 把剑），读出来时拆成合法的小堆
                    int max = Math.max(1, stack.getMaxCount());
                    while (amount > max) {
                        items.add(stack.copyWithCount(max));
                        amount -= max;
                    }
                    stack.setCount(amount);
                    items.add(stack);
                } else {
                    Optional<ItemStack> parsed = ItemStack.fromNbt(registries, stored);
                    parsed.filter(s -> !s.isEmpty()).ifPresent(items::add);
                }
            }

            data.batches.add(new CleanBatch(id, entry.getLong("created_at"), entry.getString("dimension"),
                    center, items, entry.getBoolean("manual")));
        }

        data.nextId = Math.max(declaredNext, maxId + 1L);

        NbtList zoneList = tag.getList("zones", NbtElement.COMPOUND_TYPE);
        for (int i = 0; i < zoneList.size(); i++) {
            NbtCompound entry = zoneList.getCompound(i);
            String name = entry.getString("name");
            if (name.isEmpty()) {
                continue;
            }
            data.zones.add(ProtectedZone.ofChunks(name, entry.getString("dimension"),
                    entry.getInt("min_x"), entry.getInt("min_z"),
                    entry.getInt("max_x"), entry.getInt("max_z")));
        }

        NbtList whiteList = tag.getList("whitelist", NbtElement.STRING_TYPE);
        for (int i = 0; i < whiteList.size(); i++) {
            String itemId = whiteList.getString(i);
            if (!itemId.isEmpty()) {
                data.whitelist.add(itemId);
            }
        }
        return data;
    }
}
