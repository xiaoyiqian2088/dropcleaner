package com.dropcleaner;

import com.dropcleaner.command.DropCleanerCommand;
import com.dropcleaner.config.DropCleanerConfig;
import com.dropcleaner.logic.DropCleanerManager;
import net.fabricmc.api.ModInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 入口。
 *
 * <p>纯服务端：不注册自定义菜单类型、不发自定义通道，回收站界面用的是原版箱子菜单，
 * 所以没装这个 mod 的客户端也能正常进服、正常开界面。</p>
 */
public class DropCleanerMod implements ModInitializer {

    public static final String MODID = "dropcleaner";
    public static final Logger LOGGER = LoggerFactory.getLogger(MODID);

    @Override
    public void onInitialize() {
        DropCleanerConfig.load();
        DropCleanerManager.register();
        DropCleanerCommand.register();
        LOGGER.info("Drop Cleaner 已加载（Fabric 版）");
    }
}
