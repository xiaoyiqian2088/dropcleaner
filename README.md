# Drop Cleaner

A **server-side only** Fabric mod for Minecraft **1.21.1** that clears excessive
dropped items chunk by chunk — and keeps everything it clears in a cache you can
hand back to your players later.

No client mod required: the recovery GUI is a plain vanilla chest menu, so
players without the mod can join and open it as usual.

--------------------------------------------------------------------------------

## Why

Dropped items from farms pile up, and a single loaded chunk with thousands of
item entities is one of the cheapest ways to ruin a server's tick time.

Most "item clear" plugins delete everything and say sorry. Drop Cleaner keeps a
recoverable copy: players can get their stuff back from the GUI or with a
command, for as long as the cache keeps it.

## How is this different from ClearLag / ItemCleaner?

There are plenty of "clear dropped items every N minutes" mods, and a few that
dump the result into a public chest. Drop Cleaner is deliberately not one of
them:

| | Timed cleaners | Drop Cleaner |
|---|---|---|
| When does it clean | every N minutes, server-wide | only when **one chunk** exceeds a threshold |
| Players nearby | cleaned anyway | **countdown** first; no players → treated as a farm and cleared at once |
| What happens to the items | gone | **batch with a permanent id**, retrievable by GUI or command |
| Protecting a base | impossible | **protected zones** (chunk rectangles) |
| Protecting an item type | impossible | **item whitelist** (e.g. shulker boxes are never touched) |

The point of the threshold is that a technical server is mostly clean — the only
reason to touch anything is an actual pile-up. Clearing on a timer punishes
everyone for one farm's mistake.

## Features

- **Per-chunk detection** — a chunk is only cleaned when it holds more than a
  configurable number of items, so a player's dropped gear lying around does not
  trigger anything.
- **Countdown when it matters** — if a player is within the notification radius,
  they get a 15-second (configurable) action bar countdown and a sound cue.
  If nobody is nearby, the pile is treated as a farm and cleared immediately.
- **Recoverable cache** — every cleanup becomes a batch with a permanent id.
  Batches survive restarts and are stored in the world save.
- **Stack merging** — identical items are merged up to a configurable cap
  (default 64) before being cached, so 146 stacks of stone become one neat entry.
- **Protected zones** — define chunk rectangles where nothing is ever cleaned.
- **Item whitelist** — define item types that are never cleaned, anywhere.
- **Vanilla-only client** — a custom menu type would be synced at login and kick
  players who do not have the mod, so the GUI uses `GENERIC_9X6` instead.

## Requirements

| | |
|---|---|
| Minecraft | 1.21.1 |
| Loader | Fabric Loader 0.16.0+ |
| Dependency | [Fabric API](https://modrinth.com/mod/fabric-api) |
| Side | **Server only** |

## Installation

1. Install Fabric Loader on your server.
2. Put `fabric-api` and `dropcleaner-1.0.0.jar` into `mods/`.
3. Start the server. `config/dropcleaner-server.toml` is generated on first run.
4. Restart after editing the config (it is read once at startup).

## Commands

`/dropcleaner` and `/dc` are interchangeable.

| Command | Permission | What it does |
|---|---|---|
| `/dc list` | everyone | List the most recent batches |
| `/dc list <id>` | everyone | Show the contents of one batch (identical items merged) |
| `/dc open [id]` | OP 2 | Open the recovery GUI (all batches, or one batch) |
| `/dc take <id> <index> [count]` | OP 2 | Take an item without opening the GUI |
| `/dc cleanall` | OP 2 | Clean every dimension now (skips zones and whitelist) |
| `/dc cleanall all` | OP 2 | Same, but including protected zones |
| `/dc cleanall force` | OP 2 | Same, including zones **and** the whitelist |
| `/dc whitelist` | OP 2 | Open the item whitelist GUI |
| `/dc protect pos1` / `pos2` | OP 2 | Mark two corners of a zone |
| `/dc protect add <name> [radius]` | OP 2 | Create a zone (from corners, or a square around you) |
| `/dc protect list` | OP 2 | List zones |
| `/dc protect remove <name>` | OP 2 | Delete a zone |
| `/dc id reset [next]` | OP 2 | Reset the batch id counter (existing ids stay) |
| `/dc clear` | OP 2 | Step 1 of wiping the cache (only prints a prompt) |
| `/dc clear confirm` | OP 2 | Step 2, within 15 seconds: actually wipe it |

### Priority

```
item whitelist  >  protected zone  >  clear radius  >  threshold
```

## Configuration

`config/dropcleaner-server.toml`

| Key | Default | Meaning |
|---|---|---|
| `scanIntervalTicks` | 200 | How often to scan (20 ticks = 1s) |
| `dropThreshold` | 64 | A chunk is "dirty" above this many item stacks |
| `countdownSeconds` | 5 | Countdown length; 0 = clear instantly |
| `clearRadiusChunks` | 1 | Also clean this many chunks around the trigger (1 = 3×3) |
| `notifyRadiusChunks` | 10 | Players within this radius see the countdown |
| `mergeMaxStackSize` | 64 | Merge cap for identical items |
| `cacheMaxBatches` | 50 | Keep at most this many batches |
| `cacheMaxItems` | 3200 | Keep at most this many stacks in total (0 = unlimited) |
| `protectNotifyEnabled` | true | Tell operators when a protected zone piles up |
| `protectNotifyText` | ... | Message template, `%s` = zone name, count |
| `protectNotifyCooldownSeconds` | 600 | Minimum gap between two notices per zone |
| `protectMaxRadiusChunks` | 16 | Largest zone you may create in one command |

> `cacheMaxItems` should be at least `cacheMaxBatches × dropThreshold`,
> otherwise the batch limit is never reached. The defaults are aligned
> (50 × 64 = 3200).

## Language note

In-game messages are **Chinese only** for now. The mod ships Chinese fallback
text so that vanilla clients still read something sensible instead of raw
translation keys. English in-game text may be added later; command names and
config keys are English.

## License

MIT. See [LICENSE](LICENSE).

## Credits

- 阿拾 (Aishi) — design and implementation
- 霄忆阡 (Xiaoyiqian) — the idea

================================================================================
中文说明
================================================================================

## 这是什么

一个**纯服务端**的 Minecraft **1.21.1** Fabric mod，按区块清理过多的掉落物，
**但清掉的东西不会消失**——它们进“回收站”，随时可以还给你服里的玩家。

客户端不用装任何东西：回收站界面用的是原版大箱子，没装 mod 的玩家也能正常打开。

## 为什么

刷怪塔、农场堆下来的掉落物，一个区块里几千个实体，是拖垮服务器 tick 最便宜的方式。

大多数“清理”插件删完就一句抱歉。这个 mod 会留一份可以取回来的副本。

## 和 ClearLag / ItemCleaner 有什么不同

“每 N 分钟清一次掉落物”的 mod 已经有很多，也有把清掉的东西倒进公共箱子的。Drop Cleaner 故意不做成那样：

| | 定时清理类 | Drop Cleaner |
|---|---|---|
| 什么时候清 | 每 N 分钟，全服一起清 | 只有**某个区块**超过阈值才清 |
| 附近有玩家 | 照清不误 | 先**倒计时**；附近没人 → 判定为农场，直接清 |
| 清掉的东西 | 没了 | **批次 + 永久编号**，界面或指令随时取回 |
| 保护一片基地 | 做不到 | **保护区**（区块矩形） |
| 保护一类物品 | 做不到 | **物品白名单**（比如潜影盒永不清） |

阈值这套设计的出发点是：**生电服平时地上是干净的**，真要动手说明确实堆了。定时清理等于让所有人替一个农场的失误买单。

## 功能

- **按区块判定**：只有超过阈值的区块才清，玩家随手丢在脚边的东西不会被误伤
- **有人才倒计时**：通知半径内有玩家 → 动作栏倒计时 + 音效；一个人都没有 → 判定为农场，直接清
- **可恢复缓存**：每次清理是一个批次，编号永久、重启不丢，存在世界存档里
- **合并堆叠**：相同物品合并到上限（默认 64），146 堆石头变成一行
- **保护区**：圈出区块矩形，里面的东西永远不清
- **物品白名单**：指定物品类型，掉在哪都不清
- **客户端零安装**：不用自定义菜单类型（会导致没装 mod 的玩家被踢），改用原版 `GENERIC_9X6`

## 需求

| | |
|---|---|
| 游戏版本 | 1.21.1 |
| 加载器 | Fabric Loader 0.16.0+ |
| 前置 | [Fabric API](https://modrinth.com/mod/fabric-api) |
| 安装位置 | **仅服务端** |

## 安装

1. 服务端装好 Fabric Loader
2. 把 `fabric-api` 和 `dropcleaner-1.0.0.jar` 放进 `mods/`
3. 启动服务器，首次运行会生成 `config/dropcleaner-server.toml`
4. 改完配置要重启（配置只在启动时读一次）

## 指令

`/dropcleaner` 和 `/dc` 完全等价。

| 指令 | 权限 | 作用 |
|---|---|---|
| `/dc list` | 所有人 | 列出最近的批次 |
| `/dc list <编号>` | 所有人 | 看某一批的内容（相同物品合并显示） |
| `/dc open [编号]` | OP2 | 打开回收站界面 |
| `/dc take <编号> <物品编号> [数量]` | OP2 | 不开界面直接取 |
| `/dc cleanall` | OP2 | 全服立即清理（跳过保护区和白名单） |
| `/dc cleanall all` | OP2 | 同上，连保护区一起清 |
| `/dc cleanall force` | OP2 | 同上，连白名单也清 |
| `/dc whitelist` | OP2 | 打开物品白名单界面 |
| `/dc protect pos1` / `pos2` | OP2 | 记录保护区的两个角 |
| `/dc protect add <名字> [半径]` | OP2 | 创建保护区 |
| `/dc protect list` | OP2 | 列出保护区 |
| `/dc protect remove <名字>` | OP2 | 删除保护区 |
| `/dc id reset [起始编号]` | OP2 | 重置批次编号（已有批次不变） |
| `/dc clear` | OP2 | 清空回收站第一步（只提示） |
| `/dc clear confirm` | OP2 | 15 秒内确认，真正清空 |

### 优先级

```
物品白名单  >  保护区  >  清理半径  >  阈值
```

## 配置

`config/dropcleaner-server.toml`，首次启动自动生成，带中文注释。

| 配置项 | 默认 | 含义 |
|---|---|---|
| `scanIntervalTicks` | 200 | 扫描间隔（20 tick = 1 秒） |
| `dropThreshold` | 64 | 一个区块超过多少堆算超标 |
| `countdownSeconds` | 5 | 倒计时秒数，0 = 直接清 |
| `clearRadiusChunks` | 1 | 清理半径（区块），1 = 3×3 |
| `notifyRadiusChunks` | 10 | 通知半径（区块） |
| `mergeMaxStackSize` | 64 | 相同物品合并上限 |
| `cacheMaxBatches` | 50 | 最多保留批次 |
| `cacheMaxItems` | 3200 | 最多保留堆数（0 = 不限） |
| `protectNotifyEnabled` | true | 保护区堆积时通知管理员 |
| `protectNotifyText` | ... | 通知文案，`%s` = 保护区名、数量 |
| `protectNotifyCooldownSeconds` | 600 | 同一保护区两次通知的最小间隔 |
| `protectMaxRadiusChunks` | 16 | 单次可圈的最大半径 |

> `cacheMaxItems` 建议 ≥ `cacheMaxBatches × dropThreshold`，
> 否则批次数上限永远到不了。默认值已经对齐（50 × 64 = 3200）。

## 语言说明

游戏内文本目前**只有中文**。mod 自带中文兜底，所以原版客户端也不会看到
`gui.dropcleaner.title` 这种裸键名。指令名和配置项是英文。

## 许可

MIT，见 [LICENSE](LICENSE)。

## 致谢

- 阿拾 (Aishi) —— 设计与实现
- 霄忆阡 (Xiaoyiqian) —— 最初的点子
