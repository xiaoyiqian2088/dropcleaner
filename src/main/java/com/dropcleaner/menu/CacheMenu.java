package com.dropcleaner.menu;

import com.dropcleaner.data.CleanBatch;
import com.dropcleaner.data.DropCleanerData;
import com.dropcleaner.util.ItemDelivery;
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
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 回收站界面：5 行缓存物品 + 底部一行控制按钮，用原版大箱子菜单实现。
 *
 * <p>用原版 {@link ScreenHandlerType#GENERIC_9X6} 而不是自定义菜单类型：自定义类型会在
 * 登录时同步给客户端，没装这个 mod 的玩家会被踢下线。原版菜单类型不需要任何客户端代码。</p>
 */
public class CacheMenu extends GenericContainerScreenHandler {

    public static final int ITEM_SLOTS = 45;
    public static final int PREV_SLOT = 45;
    public static final int ALL_SLOT = 47;
    public static final int INFO_SLOT = 49;
    public static final int NEXT_SLOT = 53;
    public static final int TOTAL_SLOTS = 54;

    private static final List<CacheMenu> OPEN = new ArrayList<>();

    static {
        // 停服/退档时把界面引用清掉，避免旧 server/world 被静态表拖住
        net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.SERVER_STOPPING
                .register(server -> OPEN.clear());
    }

    private final PlayerEntity player;
    private final BlockPos origin;
    private final World world;
    private final Display display;

    /** 0 = 全部批次，否则只看这个编号的批次。 */
    private final long batchId;

    /** 当前页里的（批次下标, 物品下标）。 */
    private final List<int[]> entries = new ArrayList<>();

    private int page;
    private int totalPages = 1;
    private int totalItems;
    private boolean batchGone;

    public CacheMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player, long batchId) {
        this(syncId, playerInventory, player, batchId, new Display());
    }

    private CacheMenu(int syncId, PlayerInventory playerInventory, PlayerEntity player, long batchId, Display display) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, display, 6);
        this.display = display;
        this.player = player;
        this.batchId = batchId;
        this.origin = player.getBlockPos();
        this.world = player.getEntityWorld();

        if (!player.getEntityWorld().isClient()) {
            OPEN.add(this);
            refresh();
        }
    }

    // ------------------------------------------------------------------ 视图

    /** 支撑 54 个格子；每次刷新都从缓存重建内容。 */
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

        // 物品不经过原版槽位逻辑移动，点击都在 onSlotClick 里拦掉
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
        List<CleanBatch> batches = DropCleanerData.get(((ServerWorld) player.getEntityWorld()).getServer()).batches();

        entries.clear();
        batchGone = batchId > 0L;
        for (int b = 0; b < batches.size(); b++) {
            CleanBatch batch = batches.get(b);
            if (batchId > 0L && batch.id() != batchId) {
                continue;
            }
            batchGone = false;
            for (int i = 0; i < batch.itemCount(); i++) {
                entries.add(new int[]{b, i});
            }
        }

        totalItems = entries.size();
        totalPages = Math.max(1, (totalItems + ITEM_SLOTS - 1) / ITEM_SLOTS);
        page = Math.max(0, Math.min(page, totalPages - 1));

        for (int slot = 0; slot < TOTAL_SLOTS; slot++) {
            display.setStack(slot, ItemStack.EMPTY);
        }

        int base = page * ITEM_SLOTS;
        for (int slot = 0; slot < ITEM_SLOTS; slot++) {
            int index = base + slot;
            if (index >= totalItems) {
                break;
            }
            int[] entry = entries.get(index);
            display.setStack(slot, batches.get(entry[0]).items().get(entry[1]).copy());
        }

        display.setStack(PREV_SLOT, button(Items.ARROW, "gui.dropcleaner.prev_page", page > 0));
        display.setStack(NEXT_SLOT, button(Items.ARROW, "gui.dropcleaner.next_page", page < totalPages - 1));

        if (batchId > 0L) {
            display.setStack(ALL_SLOT, button(Items.HOPPER, "gui.dropcleaner.view_all", true));
        }

        ItemStack info = new ItemStack(batchGone ? Items.BARRIER : Items.PAPER);
        if (batchGone) {
            info.set(DataComponentTypes.CUSTOM_NAME,
                    TextUtil.of("gui.dropcleaner.batch_gone").formatted(Formatting.RED));
        } else {
            info.set(DataComponentTypes.CUSTOM_NAME,
                    TextUtil.of("gui.dropcleaner.page_short", page + 1, totalPages).formatted(Formatting.WHITE));
            info.set(DataComponentTypes.LORE, new LoreComponent(List.of(
                    TextUtil.of("gui.dropcleaner.total_items", totalItems).formatted(Formatting.GRAY))));
        }
        display.setStack(INFO_SLOT, info);
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
                } else if (slotIndex == ALL_SLOT && batchId > 0L) {
                    reopenAll(serverPlayer);
                }
                return;
            }

            int amount = (actionType == SlotActionType.PICKUP && button == 1) ? 1 : -1;
            take(serverPlayer, slotIndex, amount);
            return;
        }
        super.onSlotClick(slotIndex, button, actionType, clicker);
    }

    private void changePage(ServerPlayerEntity serverPlayer, int delta) {
        int target = Math.max(0, Math.min(totalPages - 1, page + delta));
        if (target == page) {
            return;
        }
        page = target;
        refresh();
        this.sendContentUpdates();
        serverPlayer.sendMessage(TextUtil.of("gui.dropcleaner.page_short", page + 1, totalPages), true);
    }

    /** 切回“全部批次”视图；延后一 tick 执行，因为现在还在处理点击包。 */
    private void reopenAll(ServerPlayerEntity serverPlayer) {
        var server = ((ServerWorld) serverPlayer.getEntityWorld()).getServer();
        if (server == null) {
            return;
        }
        server.execute(() -> {
            serverPlayer.closeHandledScreen();
            serverPlayer.openHandledScreen(new SimpleNamedScreenHandlerFactory(
                    (syncId, inventory, owner) -> new CacheMenu(syncId, inventory, owner, 0L),
                    TextUtil.of("gui.dropcleaner.title")));
        });
    }

    /** 把最多 {@code amount} 个（负数 = 整堆）放进玩家背包。 */
    private void take(ServerPlayerEntity serverPlayer, int slot, int amount) {
        ItemStack clickedStack = display.getStack(slot);
        if (clickedStack.isEmpty() || ((ServerWorld) serverPlayer.getEntityWorld()).getServer() == null) {
            return;
        }

        DropCleanerData data = DropCleanerData.get(((ServerWorld) serverPlayer.getEntityWorld()).getServer());
        List<CleanBatch> batches = data.batches();

        // 必须按"点的是哪一格"定位，不能按物品内容全局去找：
        // 同一物品可能出现在多个批次/同批次的多个堆里，那样会扣错地方
        int index = page * ITEM_SLOTS + slot;
        if (index < 0 || index >= entries.size()) {
            syncFromData();
            return;
        }
        int[] entry = entries.get(index);
        if (entry[0] < 0 || entry[0] >= batches.size()) {
            syncFromData();
            return;
        }
        CleanBatch batch = batches.get(entry[0]);
        if (entry[1] < 0 || entry[1] >= batch.items().size()) {
            syncFromData();
            return;
        }
        ItemStack cached = batch.items().get(entry[1]);
        int take = amount < 0 ? cached.getCount() : Math.min(amount, cached.getCount());

        int given = ItemDelivery.give(serverPlayer, cached, take);
        if (given <= 0) {
            serverPlayer.sendMessage(TextUtil.of("message.dropcleaner.inventory_full").formatted(Formatting.RED), true);
            return;
        }

        if (given >= cached.getCount()) {
            data.removeItem(batch, entry[1]);
        } else {
            cached.decrement(given);
            data.markDirty();
        }

        syncFromData();
        // 同一批次别人也开着：一起刷新，免得他们还看着已经没了的那堆
        for (CacheMenu other : new ArrayList<>(OPEN)) {
            if (other != this) {
                other.syncFromData();
            }
        }
    }

    @Override
    public void onClosed(PlayerEntity clicker) {
        OPEN.remove(this);
        super.onClosed(clicker);
    }

    private static int[] findEntry(List<CleanBatch> batches, ItemStack clicked) {
        for (int b = 0; b < batches.size(); b++) {
            List<ItemStack> items = batches.get(b).items();
            for (int i = 0; i < items.size(); i++) {
                if (ItemStack.areItemsAndComponentsEqual(items.get(i), clicked)) {
                    return new int[]{b, i};
                }
            }
        }
        return null;
    }

    @Override
    public ItemStack quickMove(PlayerEntity clicker, int index) {
        // 缓存格只出不进，玩家格也不能往里塞
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity clicker) {
        return clicker.getEntityWorld().getRegistryKey() == world.getRegistryKey()
                && clicker.getBlockPos().getSquaredDistance(origin) < 64.0D * 64.0D;
    }

    /** 从实时缓存重建本界面并推给客户端。 */
    public void syncFromData() {
        if (player.getEntityWorld().isClient()) {
            return;
        }
        refresh();
        this.sendContentUpdates();
    }
}
