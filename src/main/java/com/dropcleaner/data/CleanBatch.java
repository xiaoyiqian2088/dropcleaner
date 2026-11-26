package com.dropcleaner.data;

import net.minecraft.component.ComponentMap;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一次清理：什么时候清的、大概在哪、清到了什么。
 *
 * <p>{@code id} 是持久且永不复用的编号。批次列表会因为裁剪最旧的条目而整体前移，
 * 所以命令一律按 id 寻址（{@code /dc open 12}），而不是按下标。</p>
 *
 * <p>物品列表是可变的——{@link com.dropcleaner.menu.CacheMenu} 会把玩家取走的那堆删掉。</p>
 */
public record CleanBatch(long id, long createdAt, String dimension, BlockPos center, List<ItemStack> items,
                         boolean manual) {

    public static CleanBatch of(long id, long createdAt, String dimension, BlockPos center, List<ItemStack> items) {
        return new CleanBatch(id, createdAt, dimension, center, new ArrayList<>(items), false);
    }

    /** 由 {@code /dc cleanall} 产生，列表里显示为红色。 */
    public static CleanBatch manual(long id, long createdAt, String dimension, BlockPos center,
                                    List<ItemStack> items) {
        return new CleanBatch(id, createdAt, dimension, center, new ArrayList<>(items), true);
    }

    /**
     * 把相同物品合并成不超过 {@code cap} 的堆。
     *
     * <p>只有物品本身<b>以及所有数据组件</b>都相同才合并，所以不同的附魔、不同的耐久
     * 永远不会被并到一起。</p>
     *
     * <p>合并上限还要受物品自身堆叠上限约束：剑是 1、鸡蛋是 16。否则会造出
     * 「64 个剑」这种原版根本不存在的栈，显示和后续处理都会出问题。</p>
     */
    public static List<ItemStack> merge(List<ItemStack> input, int cap) {
        int configured = Math.max(1, cap);

        // 先按「物品 + 全部数据组件」哈希分组（O(n)），再按各自堆叠上限切堆。
        // 以前的写法是每来一堆就跟已合并列表从头比一遍（O(n²)），上万堆时会卡服。
        // LinkedHashMap 是为了让输出顺序稳定：同一种物品排在一起。
        Map<StackKey, Long> totals = new LinkedHashMap<>();
        Map<StackKey, ItemStack> templates = new HashMap<>();

        for (ItemStack stack : input) {
            if (stack.isEmpty()) {
                continue;
            }
            StackKey key = new StackKey(stack.getItem(), stack.getComponents());
            totals.merge(key, (long) stack.getCount(), Long::sum);
            templates.putIfAbsent(key, stack.copyWithCount(1));
        }

        List<ItemStack> merged = new ArrayList<>(totals.size());
        for (Map.Entry<StackKey, Long> entry : totals.entrySet()) {
            ItemStack template = templates.get(entry.getKey());
            long remaining = entry.getValue();
            int limit = Math.min(configured, Math.max(1, template.getMaxCount()));

            while (remaining > 0) {
                int chunk = (int) Math.min(limit, remaining);
                merged.add(template.copyWithCount(chunk));
                remaining -= chunk;
            }
        }
        return merged;
    }

    /**
     * 合并用的 key：物品 + 所有数据组件。
     *
     * <p>{@code ComponentMapImpl} 实现了 equals/hashCode，所以附魔、耐久、自定义名不同的物品
     * 会落到不同的 key，不会被错误合并。</p>
     */
    private record StackKey(Item item, ComponentMap components) {
    }

    public int itemCount() {
        return items.size();
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }
}
