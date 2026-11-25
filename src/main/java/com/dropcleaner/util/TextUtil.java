package com.dropcleaner.util;

import net.minecraft.text.MutableText;
import net.minecraft.text.Text;

import java.util.Map;

/**
 * 带内置中文兜底的文本。
 *
 * <p>可翻译文本是<b>由客户端解析</b>的，没装这个 mod 的玩家会看到
 * {@code gui.dropcleaner.title} 这种裸键名。所以每条消息都带一个中文兜底：
 * 装了 mod 的客户端走自己的语言文件，其余的直接显示兜底文本。</p>
 */
public final class TextUtil {

    private static final Map<String, String> FALLBACKS = Map.ofEntries(
            Map.entry("gui.dropcleaner.title", "掉落物回收站"),
            Map.entry("gui.dropcleaner.page_short", "第 %s / %s 页"),
            Map.entry("gui.dropcleaner.prev_page", "上一页"),
            Map.entry("gui.dropcleaner.next_page", "下一页"),
            Map.entry("gui.dropcleaner.total_items", "共 %s 堆物品"),
            Map.entry("gui.dropcleaner.batch_title", "回收站 · 批次 #%s"),
            Map.entry("gui.dropcleaner.view_all", "查看全部批次"),
            Map.entry("gui.dropcleaner.batch_gone", "该批次已不存在"),

            Map.entry("gui.dropcleaner.whitelist.title", "掉落物白名单"),
            Map.entry("gui.dropcleaner.whitelist.page", "第 %s / %s 页"),
            Map.entry("gui.dropcleaner.whitelist.total", "共 %s 种物品"),
            Map.entry("gui.dropcleaner.whitelist.hint", "把物品放进格子即可加入白名单，物品不会消失"),
            Map.entry("gui.dropcleaner.whitelist.remove_hint", "右键点击可移出白名单"),
            Map.entry("gui.dropcleaner.whitelist.added", "已加入白名单：%s"),
            Map.entry("gui.dropcleaner.whitelist.duplicate", "已经在白名单里了：%s"),
            Map.entry("gui.dropcleaner.whitelist.removed", "已移出白名单：%s"),
            Map.entry("gui.dropcleaner.whitelist.empty", "白名单还是空的"),

            Map.entry("message.dropcleaner.countdown", "%s 秒后清理 (%s, %s) 附近的掉落物"),
            Map.entry("message.dropcleaner.cleared", "已清理 [%s] (%s, %s) 附近的 %s 堆掉落物"),
            Map.entry("message.dropcleaner.inventory_full", "背包已满，无法取出"),

            Map.entry("dimension.dropcleaner.overworld", "主世界"),
            Map.entry("dimension.dropcleaner.nether", "下界"),
            Map.entry("dimension.dropcleaner.end", "末地"),

            Map.entry("command.dropcleaner.list.empty", "回收站是空的"),
            Map.entry("command.dropcleaner.list.header", "掉落物回收站：共 %s 个批次"),
            Map.entry("command.dropcleaner.list.entry", "#%s [%s] (%s, %s) - %s 堆 - %s"),
            Map.entry("command.dropcleaner.list.truncated", "共 %1$s 个批次，只显示最近 %2$s 个"),
            Map.entry("command.dropcleaner.list.hint",
                    "/dropcleaner list <编号> 查看内容，/dropcleaner take <编号> <物品编号> [数量] 取出"),
            Map.entry("command.dropcleaner.list.batch_header", "第 %s 批 [%s] (%s, %s)：共 %s 项"),
            Map.entry("command.dropcleaner.list.item", "%s. %s ×%s"),
            Map.entry("command.dropcleaner.list.items_truncated", "共 %1$s 项，只显示前 %2$s 项"),

            Map.entry("command.dropcleaner.batch_missing", "找不到编号 #%s 的批次（可能已被清除或超出保留上限）"),
            Map.entry("command.dropcleaner.take.ok", "已取出 %s ×%s"),
            Map.entry("command.dropcleaner.take.bad_item", "物品编号无效（该批次共 %s 项）"),

            Map.entry("command.dropcleaner.id.reset",
                    "批次编号已重置：下一个新批次从 #%s 开始（已有批次的编号不变）"),

            Map.entry("command.dropcleaner.protect.pos1", "已记录第一个角：(%s, %s, %s)"),
            Map.entry("command.dropcleaner.protect.pos2", "已记录第二个角：(%s, %s, %s)"),
            Map.entry("command.dropcleaner.protect.added",
                    "已创建保护区「%s」[%s]：区块 (%s, %s) → (%s, %s)，共 %s 个区块"),
            Map.entry("command.dropcleaner.protect.duplicate", "已存在同名保护区：%s"),
            Map.entry("command.dropcleaner.protect.removed", "已删除保护区「%s」"),
            Map.entry("command.dropcleaner.protect.not_found", "找不到保护区：%s"),
            Map.entry("command.dropcleaner.protect.list.empty", "还没有设置任何保护区"),
            Map.entry("command.dropcleaner.protect.list.header", "共有 %s 个保护区"),
            Map.entry("command.dropcleaner.protect.list.entry",
                    "%s. %s [%s] 区块 (%s, %s) → (%s, %s)，共 %s 个区块"),
            Map.entry("command.dropcleaner.protect.too_big", "区域太大：一次最多 %s 个区块"),

            Map.entry("command.dropcleaner.cache_cleared", "已清空回收站：%s 个批次、%s 堆物品"),
            Map.entry("command.dropcleaner.cleanall.done",
                    "全服清理完成：%s 个批次、%s 堆物品（已存入回收站，可随时取回）"),
            Map.entry("command.dropcleaner.cleanall.done_all",
                    "全服清理完成（包含保护区）：%s 个批次、%s 堆物品（已存入回收站，可随时取回）"),
            Map.entry("command.dropcleaner.cleanall.done_force",
                    "全服清理完成（包含保护区与白名单）：%s 个批次、%s 堆物品（已存入回收站，可随时取回）"),
            Map.entry("command.dropcleaner.cleanall.empty", "全服没有可清理的掉落物"),
            Map.entry("command.dropcleaner.cache_empty", "回收站已经是空的"),
            Map.entry("command.dropcleaner.clear_confirm_prompt",
                    "将永久清空回收站里的 %s 个批次（%s 堆物品）。%s 秒内点击此消息或执行 /dropcleaner clear confirm 确认"),
            Map.entry("command.dropcleaner.clear_no_pending",
                    "没有待确认的清空操作（或已超时），请先执行 /dropcleaner clear"),

            Map.entry("command.dropcleaner.time.just_now", "刚刚"),
            Map.entry("command.dropcleaner.time.minutes", "%s 分钟前"),
            Map.entry("command.dropcleaner.time.hours", "%s 小时前"),
            Map.entry("command.dropcleaner.time.days", "%s 天前"));

    private TextUtil() {
    }

    /** 和 {@code Text.translatable(key, args)} 同样用法，只是多了中文兜底。 */
    public static MutableText of(String key, Object... args) {
        return Text.translatableWithFallback(key, FALLBACKS.getOrDefault(key, key), args);
    }
}
