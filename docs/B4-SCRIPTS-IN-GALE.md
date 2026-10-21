# 在 Gale 里直接跑 B4 脚本，和 Trajectories

## Script Manager：不装 B4 也能跑 B4 脚本

Gale 带了一份 B4 的脚本运行时（包 `gale.b4script`，从 OpenB4 搬来）：同样的 API（`client`、`modules`、`world`、`render`、`bridge`……）、同样的事件、同样的设置。B4 脚本放进文件夹就能在 Gale 里跑，每个脚本是 ClickGUI「脚本」分类里的一个模块。

Script Manager 的设置：

| 设置 | 作用 |
|---|---|
| Folder | 脚本文件夹，相对 `.minecraft`，默认 `galemod/scripts`；填 `keystrokes/scripts` 就直接用 B4 的那个文件夹 |
| Auto load | 第一次进世界时自己加载（和 B4 一样） |
| Load scripts | 重新读文件夹：新的和改过的编译加载，没改的继续跑 |
| Open folder | 打开脚本文件夹 |

要求：游戏要用 JDK 启动（和 B4 一样，要编译脚本）。

和 B4 一样的地方：

- 读文件、安全检查、编译方式（B4 的 19 个 import、随机类名 `extends ScriptDefaults`）、报错的格式和行号都照 B4；
- 加密脚本（`decrypt - "..."`）照 B4 的办法解开；
- 事件的时机照 B4：onPreUpdate、getRotations / getMovementYaw（变成 Gale 的静默转头）、onPreMotion（Gale 的 `MotionEvent`，改了就改这一 tick 的移动包）、onPostMotion、onPostPlayerInput、onPacketSent / onPacketReceived（网络线程）、聊天、按键、鼠标、界面、渲染、进世界。

B4 自己的东西从哪来：

- `modules.isEnabled` / `enable` / `disable`：Gale 的模块，或（GaleLink 报来的）B4 的模块；
- KillAura / AimAssist / Backtrack / Lag Range 的目标、`isScaffolding`、`isTowering`、BedAura 的方块和进度：Gale 自己搬来的这些模块，B4 在跑它自己的时用 B4 报来的（见 [从 B4 搬过来的模块](B4-MODULES.md)）；
- onBedWarsAlert、onAntiCheatFlag、onLocRaw：Gale 的 Bed Wars、AntiCheat、Locraw 模块，或 GaleLink 转来的 B4 的；onIRCMessage 只有 GaleLink 转来的（Gale 没有 IRC）；
- 好友：Gale 的（`/gale friend`）；没有 B4 账号（`client.getUser` 是游戏名，UID 是 -1）。

GaleLink 只在 B4 里跑：Gale 加载脚本时跳过它（在 Gale 里跑会把 Gale 自己的状态当成 B4 的报回来）。

## Trajectories（渲染）

抛物线落点，照 B4 的 Trajectories 搬的：

- 手里拿着弓、蛋、雪球、珍珠时，画出扔出去的弧线和落到的方块面；会打中生物时线变红；
- 飞着的珍珠（谁扔的都算）：弧线、落点方块、还要几秒落地；
- 飞着的火焰弹：直线、会打到的方块、还要几秒；
- 别人拿着火焰弹时，画出他扔出去会飞到哪。

每一种都能单独关（Bow、Egg、Snowball、Ender pearl、Ender pearl (thrown)、Fireball (thrown)、Fireball (enemy)），还有 Auto-scale target、Disable uncharged bow、Highlight on entity、Draw block outline、Shorten line。

## 自测

```
gradlew runClient -Pb4scripttest
```

Trajectories 的几种情况（截图在 `run/screenshots/b4script-*.png`），然后用测试脚本把运行时的加载、设置、各种事件都走一遍，再加载 `run/galemod/scripts-all` 里的脚本看能不能都过。

```
gradlew runClient -Pexpotest
```

用 `run/galemod/scripts-expo` 里的 exposcaffold 在 Gale 里搭路（不装 B4）。
