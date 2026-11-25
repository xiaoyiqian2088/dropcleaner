package com.dropcleaner.menu;

import com.dropcleaner.data.DropCleanerData;
import com.dropcleaner.util.TextUtil;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 物品类型白名单界面：出现在这里的东西，无论掉在哪都不会被清理。
 *
 * <p>和回收站一样用原版 {@link ScreenHandlerType#GENERIC_9X6}，否则没装 mod 的客户端会被踢。</p>
 *
 * <p>加入白名单<b>不会移动物品</b>：东西留在玩家手上，只记录它的类型，格子里放一个展示用的副本。</p>
 */
public class WhitelistMenu extends GenericContainerScreenHandler {

    public static final int ITEM_SLOTS = 45;
    public static final int PREV_SLOT = 45;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    public static final int TOTAL_SLOTS = 54;

    private final PlayerEntity player;
    private final BlockPos origin;
    private final World world;
    private final Display display;

    /** 当前页显示的物品 ID，已排序。 */
    private final List<String> entries = new ArrayList<>();

    private int page;
    private int totalPages = 1;

    public WhitelistMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player) {
        this(syncId, playerInventory, player, new Display());
    }

    private WhitelistMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player, Display display) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, display, 6);
        this.display = display;
        this.player = player;
        this.origin = player.getBlockPos();
        this.world = player.getEntityWorld();

        if (!player.getEntityWorld().isClient()) {
            refresh();
        }
    }

    // ------------------------------------------------------------------ 视图

    static final class Display implements Inventory {

        private final ItemStack[] stacks = new ItemStack[TOTAL_SLOTS];

        Display() {
            Arrays.fill(stacks, ItemStack.EMPTY);
        }

        @Override
        public int size() {
            return TOTAL_SLOTS;
        }

        @Override
        public boolean isEmpty() {
            for (ItemStack stack : stacks) {
                if (!stack.isEmpty()) {
                    return false;
                }
            }
            return true;
        }

        @Override
        public ItemStack getStack(int slot) {
            return slot >= 0 && slot < TOTAL_SLOTS ? stacks[slot] : ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeStack(int slot, int amount) {
            return ItemStack.EMPTY;
        }

        @Override
        public ItemStack removeStack(int slot) {
            return ItemStack.EMPTY;
        }

        @Override
        public void setStack(int slot, ItemStack stack) {
            if (slot >= 0 && slot < TOTAL_SLOTS) {
                stacks[slot] = stack;
            }
        }

        @Override
        public void markDirty() {
        }

        @Override
        public boolean canPlayerUse(PlayerEntity player) {
            return true;
        }

        @Override
        public void clear() {
            Arrays.fill(stacks, ItemStack.EMPTY);
        }
    }

    // ------------------------------------------------------------------ 刷新

    private void refresh() {
        if (((ServerWorld) player.getEntityWorld()).getServer() == null) {
            return;
        }
        DropCleanerData data = DropCleanerData.get(((ServerWorld) player.getEntityWorld()).getServer());

        entries.clear();
        entries.addAll(data.whitelist());
        entries.sort(String::compareTo);

        totalPages = Math.max(1, (entries.size() + ITEM_SLOTS - 1) / ITEM_SLOTS);
        page = Math.max(0, Math.min(page, totalPages - 1));

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            display.setStack(slot, ItemStack.EMPTY);
        }

        int base = page * ITEM_SLOTS;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            int index = base + slot;
            if (index >= entries.size()) {
                break;
            }
            display.setStack(slot, entryStack(entries.get(index)));
        }

        display.setStack(PREV_SLOT, button(Items.ARROW, "gui.dropcleaner.prev_page", page > 0));
        display.setStack(NEXT_SLOT, button(Items.ARROW, "gui.dropcleaner.next_page", page < totalPages - 1));

        ItemStack info = new ItemStack(entries.isEmpty() ? Items.BARRIER : Items.PAPER);
        if (entries.isEmpty()) {
            info.set(DataComponentTypes.CUSTOM_NAME,
                    TextUtil.of("gui.dropcleaner.whitelist.empty").formatted(Formatting.RED));
        } else {
            info.set(DataComponentTypes.CUSTOM_NAME,
                    TextUtil.of("gui.dropcleaner.whitelist.page", page + 1, totalPages).formatted(Formatting.WHITE));
        }
        info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                TextUtil.of("gui.dropcleaner.whitelist.total", entries.size()).formatted(Formatting.GRAY),
                TextUtil.of("gui.dropcleaner.whitelist.hint").formatted(Formatting.DARK_GRAY),
                TextUtil.of("gui.dropcleaner.whitelist.remove_hint").formatted(Formatting.DARK_GRAY))));
        display.setStack(INFO_SLOT, info);
    }

    /** 某个白名单条目的展示物品；未知 ID（模组被删了）显示成屏障方块。 */
    private static ItemStack entryStack(String itemId) {
        Identifier key = Identifier.tryParse(itemId);
        Item item = key == null ? null : Registries.ITEM.get(key);
        ItemStack stack = new ItemStack(item == null ? Items.BARRIER : item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(itemId).formatted(Formatting.WHITE));
        stack.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                TextUtil.of("gui.dropcleaner.whitelist.remove_hint").formatted(Formatting.DARK_GRAY))));
        return stack;
    }

    private static ItemStack button(Item item, String key, boolean enabled) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, TextUtil.of(key)
                .formatted(enabled ? Formatting.YELLOW : Formatting.DARK_GRAY));
        return stack;
    }

    // ------------------------------------------------------------------ 点击

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        if (slotIndex >= 0 && slotIndex < TOTAL_SLOTS) {
            if (!(clicker instanceof ServerPlayerEntity serverPlayer)) {
                return;
            }
            if (actionType != SlotActionType.PICKUP && actionType != SlotActionType.QUICK_MOVE) {
                return;
            }

            if (slotIndex >= ITEM_SLOTS) {
                if (slotIndex == PREV_SLOT) {
                    changePage(serverPlayer, -1);
                } else if (slotIndex == NEXT_SLOT) {
                    changePage(serverPlayer, 1);
                }
                return;
            }

            ItemStack carried = getCursorStack();
            if (!carried.isEmpty()) {
                // 只记录类型：物品留在玩家手上，不消耗、不复制
                addType(serverPlayer, carried);
                return;
            }

            if (button == 1) {
                removeAt(serverPlayer, slotIndex);
            }
            return;
        }

        super.onSlotClick(slotIndex, button, actionType, clicker);
    }

    @Override
    public ItemStack quickMove(PlayerEntity clicker, int index) {
        // 背包里 Shift+点击 = 把这个类型加进白名单，物品不移动
        if (clicker instanceof ServerPlayerEntity serverPlayer && index >= TOTAL_SLOTS) {
            ItemStack stack = this.slots.get(index).getStack();
            if (!stack.isEmpty()) {
                addType(serverPlayer, stack);
            }
        }
        return ItemStack.EMPTY;
    }

    private void addType(ServerPlayerEntity serverPlayer, ItemStack stack) {
        if (stack.isEmpty() || ((ServerWorld) serverPlayer.getEntityWorld()).getServer() == null) {
            return;
        }
        DropCleanerData data = DropCleanerData.get(((ServerWorld) serverPlayer.getEntityWorld()).getServer());
        String itemId = Registries.ITEM.getId(stack.getItem()).toString();

        if (!data.addWhitelist(itemId)) {
            serverPlayer.sendMessage(
                    TextUtil.of("gui.dropcleaner.whitelist.duplicate", stack.getName()).formatted(Formatting.YELLOW),
                    true);
            return;
        }

        refresh();
        this.sendContentUpdates();
        serverPlayer.sendMessage(
                TextUtil.of("gui.dropcleaner.whitelist.added", stack.getName()).formatted(Formatting.GREEN), true);
    }

    private void removeAt(ServerPlayerEntity serverPlayer, int slot) {
        int index = page * ITEM_SLOTS + slot;
        if (index < 0 || index >= entries.size() || ((ServerWorld) serverPlayer.getEntityWorld()).getServer() == null) {
            return;
        }
        String itemId = entries.get(index);
        DropCleanerData data = DropCleanerData.get(((ServerWorld) serverPlayer.getEntityWorld()).getServer());
        if (!data.removeWhitelist(itemId)) {
            return;
        }

        refresh();
        this.sendContentUpdates();
        serverPlayer.sendMessage(
                TextUtil.of("gui.dropcleaner.whitelist.removed", Text.literal(itemId)).formatted(Formatting.YELLOW),
                true);
    }

    private void changePage(ServerPlayerEntity serverPlayer, int delta) {
        int target = Math.max(0, Math.min(totalPages - 1, page + delta));
        if (target == page) {
            return;
        }
        page = target;
        refresh();
        this.sendContentUpdates();
        serverPlayer.sendMessage(TextUtil.of("gui.dropcleaner.whitelist.page", page + 1, totalPages), true);
    }

    @Override
    public boolean canUse(PlayerEntity clicker) {
        return clicker.getEntityWorld().getRegistryKey() == world.getRegistryKey()
                && clicker.getBlockPos().getSquaredDistance(origin) < 64.0D * 64.0D;
    }
}
