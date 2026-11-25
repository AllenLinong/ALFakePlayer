# ALFakePlayer

适用于 Paper / Folia 的假人插件。玩家可以购买并召唤自己的假人，让它在原地挖矿、打怪、放方块，背包满了自动往箱子里卸货。假人是服务端里真实的玩家实体，行为遵循原版规则；也支持多个子服共用数据，跨服管理和召回假人。

## 功能

- **购买与召唤**：购买可以要求金币、点券、物品，或几种同时要求。假人分 Normal / Pro / Pro Max 三个等级，加载的区块范围和召唤价格各不相同，都可以配置。
- **自动工作**：挖掘正前方的方块，自动攻击（可调攻击间隔），放置方块并自动补货，背包满时向相邻箱子卸货，饿了自动进食。
- **背包与设置**：随时打开假人背包，未召唤或死亡时也能打开；可以设置工作方向、是否会被推动、是否保持蹲下。
- **传送**：传送到假人身边，或把假人叫到自己身边，可以跨世界。
- **跨服**：多个子服连接同一个 MySQL，在任意子服都能管理自己的假人、传送过去或召回到当前子服；可选用 Redis 让跨服操作即时生效。
- **菜单可自定义**：所有菜单的布局和文字都能修改，图标支持资源包 CustomModelData、CraftEngine 和 ItemsAdder 物品。
- **显示**：假人在 TAB 列表中显示 LuckPerms 前缀和 PlaceholderAPI 称号。
- **其他**：内置中文和英文；插件更新时配置文件自动升级，自定义内容保留，原文件会备份。

## 运行环境

- Paper 或 Folia 26.1.2 及以上，Java 25。已在 Folia 26.1.2、26.2、26.3 上验证，不支持 Spigot。
- 可选插件：

| 插件 | 用途 |
| --- | --- |
| Vault + 任意经济插件 | 用金币购买和召唤 |
| PlayerPoints | 用点券购买 |
| LuckPerms | 给假人设置权限组，在 TAB 中显示前缀 |
| PlaceholderAPI | 在 TAB 中显示称号 |
| CraftEngine / ItemsAdder | 菜单图标使用自定义物品 |

## 安装

1. 把 `ALFakePlayer-1.1.0.jar` 放进 `plugins/`，重启服务器。
2. 首次启动会在 `plugins/ALFakePlayer/` 下生成 `config.yml`、`database.yml`、`menus/` 和 `lang/`。
3. 召唤假人默认要扣金币，需要安装 Vault 和经济插件。不想收费的话，在 `config.yml` 中设置 `economy.enabled: false`。

从 1.0.x 升级时，配置和数据会自动迁移，旧文件备份在 `config-backups/`。

## 指令

主指令 `/alfakeplayer`，简写 `/afp`。

**玩家**

| 指令 | 说明 |
| --- | --- |
| `/afp` | 打开主菜单，购买、召唤和管理假人 |
| `/afp spawn [编号] [normal\|pro\|promax]` | 在自己的位置召唤假人 |
| `/afp despawn [编号]` | 收回假人，背包和设置保留 |
| `/afp list` | 查看自己的假人 |
| `/afp b <编号>` | 打开假人背包 |
| `/afp dir <编号>` | 让假人面向自己当前看的方向 |
| `/afp rename <编号> <名字>` | 给假人改名，消耗一张改名卡 |
| `/afp delete <编号>` | 永久删除假人和它的背包 |
| `/afp tp <编号>` | 传送到假人身边 |
| `/afp tph <编号>` | 把假人传送到自己身边 |

**管理员**（在后台输入时不用加 `/`）

| 指令 | 说明 |
| --- | --- |
| `/afp admin help` | 查看管理员指令 |
| `/afp grant <玩家> <数量>` | 给玩家增加假人 |
| `/afp set <玩家> <总数>` | 把玩家的假人补到指定数量 |
| `/afp card <玩家> [数量]` | 发放改名卡 |
| `/afp items` | 设置购买假人需要的物品（只能在游戏内用） |
| `/afp despawn <名字>` | 收回任意玩家的假人 |
| `/afp delete <名字>` | 永久删除任意玩家的假人 |
| `/afp clear [服务器ID\|all]` | 收回本服、指定子服或全部子服的所有假人（10 秒内输入两次才执行） |
| `/afp servers` | 查看各子服状态和假人数量 |
| `/afp test <数量>` / `/afp test stop` | 在 TAB 中添加或清除测试用的虚拟玩家 |

## 权限

| 权限 | 说明 | 默认 |
| --- | --- | --- |
| `alfakeplayer.use` | 购买、召唤和管理自己的假人 | 所有人 |
| `alfakeplayer.teleport` | 使用 `/afp tp` 和 `/afp tph` | 所有人 |
| `alfakeplayer.teleport.bypass` | 传送时不用等待，也没有冷却 | OP |
| `alfakeplayer.admin` | 使用管理员指令 | OP |

## 配置

| 文件 | 内容 |
| --- | --- |
| `config.yml` | 服务器 ID、语言、假人数量上限、购买和召唤价格、等级范围、传送、性能 |
| `database.yml` | 数据库（默认 SQLite，跨服用 MySQL）和 Redis |
| `menus/*.yml` | 各个菜单的布局、图标和文字 |
| `lang/*.yml` | 中文和英文消息 |

每个配置文件里都有详细注释。

菜单图标的写法：

```yaml
type: DIAMOND                       # 原版物品
custom_model_data: 10001            # 资源包模型
craftengine: "namespace:item_id"    # CraftEngine 物品
itemsadder: "namespace:item_id"     # ItemsAdder 物品
# 也可以写成 type: "ce:namespace:item_id" 或 type: "ia:namespace:item_id"
```

找不到自定义物品时显示 `type` 中的原版物品。

## 跨服设置

1. 每个子服在 `config.yml` 中设置不同的 `server_id`，要和 BungeeCord / Velocity 配置里的子服名一致。
2. 每个子服在 `database.yml` 中设置 `type: mysql`，连接同一个数据库，`prefix` 保持一致。
3. 推荐所有子服开启 `redis.enabled`，并使用相同的 `channel`，这样跨服操作即时生效；不开 Redis 也能用，大约延迟 1 秒。
4. Velocity 需要保持 `bungee-plugin-message-channel = true`（默认就是开启的）。

同一个假人同一时间只会在一个子服运行，换服不会复制物品。
