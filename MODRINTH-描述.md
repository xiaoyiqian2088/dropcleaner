# Drop Cleaner

A server-side Fabric mod that clears excessive dropped items **chunk by chunk** — and keeps everything it clears in a cache you can hand back to your players later.

No client mod required: the recovery GUI is a plain vanilla chest menu, so players without the mod can join and open it as usual.

---

## Why

Dropped items from farms pile up, and a single loaded chunk with thousands of item entities is one of the cheapest ways to ruin a server's tick time.

Most "item clear" plugins delete everything and say sorry. Drop Cleaner keeps a recoverable copy.

## How is this different from ClearLag / ItemCleaner?

| | Timed cleaners | Drop Cleaner |
|---|---|---|
| When does it clean | every N minutes, server-wide | only when **one chunk** exceeds a threshold |
| Players nearby | cleaned anyway | **countdown** first; no players → treated as a farm and cleared at once |
| What happens to the items | gone | **batch with a permanent id**, retrievable by GUI or command |
| Protecting a base | impossible | **protected zones** (chunk rectangles) |
| Protecting an item type | impossible | **item whitelist** (e.g. shulker boxes are never touched) |

A technical server is mostly clean; the only reason to touch anything is an actual pile-up. Clearing on a timer punishes everyone for one farm's mistake.

## Features

- **Per-chunk detection** — a chunk is only cleaned when it holds more than a configurable number of items
- **Countdown when it matters** — players within the notification radius get an action bar countdown and a sound cue; if nobody is nearby, the pile is treated as a farm and cleared immediately
- **Recoverable cache** — every cleanup becomes a batch with a permanent id, stored in the world save and surviving restarts
- **Stack merging** — identical items are merged up to a configurable cap (default 64)
- **Protected zones** — chunk rectangles where nothing is ever cleaned
- **Item whitelist** — item types that are never cleaned, anywhere
- **Vanilla-only client** — the GUI uses `GENERIC_9X6`, so players without the mod can open it

## Commands

`/dropcleaner` and `/dc` are interchangeable.

| Command | Permission | What it does |
|---|---|---|
| `/dc list` | everyone | List recent batches |
| `/dc list <id>` | everyone | Show one batch |
| `/dc open [id]` | OP 2 | Open the recovery GUI |
| `/dc take <id> <index> [count]` | OP 2 | Take an item without opening the GUI |
| `/dc cleanall` | OP 2 | Clean every dimension now (skips zones and whitelist) |
| `/dc cleanall all` | OP 2 | Same, including protected zones |
| `/dc cleanall force` | OP 2 | Same, including zones **and** the whitelist |
| `/dc whitelist` | OP 2 | Open the item whitelist GUI |
| `/dc protect pos1` / `pos2` | OP 2 | Mark two corners of a zone |
| `/dc protect add <name> [radius]` | OP 2 | Create a zone |
| `/dc protect list` | OP 2 | List zones |
| `/dc protect remove <name>` | OP 2 | Delete a zone |
| `/dc id reset [next]` | OP 2 | Reset the batch id counter |
| `/dc clear` → `/dc clear confirm` | OP 2 | Two-step cache wipe |

### Priority

```
item whitelist  >  protected zone  >  clear radius  >  threshold
```

## Configuration

`config/dropcleaner-server.toml`, generated on first start.

| Key | Default | Meaning |
|---|---|---|
| `scanIntervalTicks` | 200 | How often to scan (20 ticks = 1s) |
| `dropThreshold` | 64 | A chunk is "dirty" above this many stacks |
| `countdownSeconds` | 5 | Countdown length; 0 = clear instantly |
| `clearRadiusChunks` | 1 | Also clean this many chunks around (1 = 3×3) |
| `notifyRadiusChunks` | 10 | Players within this radius see the countdown |
| `mergeMaxStackSize` | 64 | Merge cap for identical items |
| `cacheMaxBatches` | 50 | Keep at most this many batches |
| `cacheMaxItems` | 3200 | Keep at most this many stacks (0 = unlimited) |
| `protectNotifyEnabled` | true | Tell operators when a zone piles up |
| `protectNotifyText` | … | Message template, `%s` = zone name, count |
| `protectNotifyCooldownSeconds` | 600 | Minimum gap between notices per zone |
| `protectMaxRadiusChunks` | 16 | Largest zone per command |

## Requirements & install

- Minecraft **1.21.1**, Fabric Loader, **Fabric API**
- Drop the jar into `mods/`, start the server, edit the generated config, restart once (the config is read at startup)

## Language

In-game messages are **Chinese only** for now, with built-in Chinese fallbacks so vanilla clients still read something sensible.

## License

MIT

## Credits

- 阿拾 (Aishi) — design and implementation
- 霄忆阡 (Xiaoyiqian) — the idea

---

# 中文说明

## 这是什么

按区块清理超量掉落物，**清理结果可随时取回**。纯服务端，客户端零安装。

## 和 ClearLag / ItemCleaner 有什么不同

| | 定时清理类 | Drop Cleaner |
|---|---|---|
| 什么时候清 | 每 N 分钟，全服一起清 | 只有某个区块超过阈值才清 |
| 附近有玩家 | 照清不误 | 先倒计时；附近没人 → 判定为农场，直接清 |
| 清掉的东西 | 没了 | 批次 + 永久编号，界面或指令随时取回 |
| 保护一片基地 | 做不到 | 保护区（区块矩形） |
| 保护一类物品 | 做不到 | 物品白名单 |

阈值这套设计的出发点是：生电服平时地上是干净的，真要动手说明确实堆了。定时清理等于让所有人替一个农场的失误买单。

## 功能

- **按区块判定** — 只有超过阈值的区块才清
- **有人才倒计时** — 通知半径内有玩家则倒计时 + 音效；没人则判定为农场直接清
- **可恢复缓存** — 每次清理是一个批次，编号永久、重启不丢
- **合并堆叠** — 相同物品合并到上限（默认 64）
- **保护区** — 区块矩形，里面的东西永远不清
- **物品白名单** — 指定物品类型，掉在哪都不清
- **客户端零安装** — 界面用原版大箱子

## 指令

`/dropcleaner` 和 `/dc` 等价：`list`、`open`、`take`、`cleanall [all|force]`、`whitelist`、`protect pos1/pos2/add/list/remove`、`id reset`、`clear` → `clear confirm`。

优先级：物品白名单 > 保护区 > 清理半径 > 阈值。

## 配置

`config/dropcleaner-server.toml`，首次启动自动生成，带中文注释。改完需重启。

## 需求

Minecraft 1.21.1 / Fabric Loader / Fabric API，仅服务端。

## 许可

MIT
