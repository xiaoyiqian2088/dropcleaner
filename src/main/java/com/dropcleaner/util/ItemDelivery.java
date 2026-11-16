package com.dropcleaner.util;

import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;

/** 把缓存里的东西发给玩家，且永远不会造出非法的堆叠数量。 */
public final class ItemDelivery {

    private ItemDelivery() {
    }

    /**
     * 最多给玩家 {@code amount} 个，按物品自身的上限拆成多堆。
     * 装不下时余下的掉在脚下（不会凭空消失）。
     *
     * @return 实际交出去的个数
     */
    public static int give(PlayerEntity player, ItemStack prototype, int amount) {
        int perStack = Math.max(1, prototype.getMaxCount());
        int remaining = amount;
        int given = 0;

        while (remaining > 0) {
            int chunk = Math.min(perStack, remaining);
            ItemStack piece = prototype.copyWithCount(chunk);
            player.getInventory().insertStack(piece);

            int moved = chunk - piece.getCount();
            given += moved;
            remaining -= moved;

            if (!piece.isEmpty()) {
                // 背包满了：剩下的丢在脚下，总比凭空抹掉好
                given += piece.getCount();
                player.dropStack(piece);
                remaining = 0;
            }
        }
        return given;
    }
}
