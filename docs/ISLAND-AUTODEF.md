# 灵动岛（Island）和自动守床（AutoDef）

两个模块都是从 B4 脚本搬过来的：Island 对应 `BetterIsland.java`，AutoDef 对应 `BedDefender.java`。它们共用 Gale 的起床战争追踪（`gale.bedwars.BedTracker`）。

## 起床战争追踪（BedTracker）

它一直在后台跑，不是模块，也没有开关。不在起床战争里时，它每秒只读四次侧边栏。

- **游戏状态**：从侧边栏读起床战争的标题和你那一行（`B Blue: ✔ YOU`），得出游戏开没开、你在哪队。
- **找你的床**：
  - 开局（"Protect your bed..."）或复活后还不知道床在哪时，记下出生点；
  - 每 tick 搜出生点周围 25 格、上下 6 格，每次最多 1.5 ms；
  - 正好找到一张床才算你的。找到两张就都不认，不靠颜色猜。
- **床没了**，下面三种情况任一成立就算：
  - 侧边栏你那行变成 ✗ 或数字；
  - 服务器发 `BED DESTRUCTION > Your Bed ... by 名字`；
  - 在床附近连续三次没看到床，而且床下面的方块还在。

AutoDef 的 "Bedwars only" 用的就是这张床。

## Island

画面顶部是一个胶囊：图标、标题（默认 `Ravenb4`）、你的名字、帧数。有事时它会变成卡片。几件事同时发生时，只显示排在最前的那张：

1. **BED UNDER ATTACK**：有敌人在挖你的床，或床周围的防御。
   - 显示：挖的人的头像、名字、在挖哪个方块（BED、End stone、Wool…）、百分比，还有一条从橙到红的进度条。
   - 数据来源：服务器发给你的挖方块裂纹（S25）。服务器只发给离方块 32 格内的人，所以离床 30 格内才有准确数据。
   - 平滑：裂纹每 10% 才更新一次。进度条按前几段花的时间在两段之间平滑往前走。
   - 停手 0.4 秒后卡片消失。
2. **Bed Alert! / 床的事件**：
   - 敌人进入你床周围 Detect range 内时，显示最近那个人的头像、距离、手里拿的和裤子。
   - 床被拆时显示 `Your bed destroyed / By 名字`；防御被拆或被补时显示 `Your bed defense changed`。
3. **Scaffold Toggled**：搭路时显示剩下多少方块和速度。"Scaffold modules" 里的模块开着就算在搭路。
4. **Breaking Bed**：拆床的进度，来自 B4 的 BedAura，或者你自己手挖床。
5. **Module Toggled**：有模块开关时显示。Gale 自己的模块马上就显示；B4 的模块要等 GaleLink 报过来。

和脚本版的区别：

- 头像直接用游戏里这个玩家的皮肤画，不再去 mc-heads.net 下载。
- 不需要再 `gale link` 去调 `mining.list`：Gale 内部直接读挖方块数据。
- 原来 "B4 BedWars Alerts" 那一项去掉了，因为 B4 的这个回调 Gale 拿不到。
- "Raven Logo Theme" 那几项在脚本里没有被用到，也去掉了。

### 要 GaleLink 的部分

B4 的模块开关、Scaffold 是否开着、BedAura 的拆床进度，Gale 只能通过 GaleLink 脚本知道：GaleLink 每几 tick 报一次开着的模块，BedAura 的进度每 tick 报一次。没开 GaleLink 时，这几张卡片只对 Gale 自己的模块和你手挖床有效。

Island 有 "Defer" 设置：GaleLink 报告 B4 的 BetterIsland 开着时，Gale 的 Island 先停下，不会出现两个岛。

## AutoDef

AutoDef 按选好的图案把床围起来，图案有 EndGlassNoCorners、EndWoolCorners 等 8 种。每 tick 的流程：

1. 找出图案里还空着、你现在能放的位置。条件是：快捷栏有这种方块、旁边有能点的面、里面没人、离眼睛 6 格内。会挡住别的空位的位置留到最后放。
2. 用静默转头朝目标转过去，每 tick 最多转 "Aim speed" 度。
3. 服务器已经拿到的朝向对准那个面时，右键放下去。要贴着床放时，会先蹲下，免得变成去睡觉。
4. 图案围满、没方块了、或者一直放不了超过 "Stuck timeout" 时，自己关掉。

它只会点从你站的位置看得见的面，这一点和 B4 脚本一样。所以站在床边时：

- 床另一边贴着地的那几格，常常要等你走到另一边才放得上；
- 最上面一层的面比眼睛高，要站高一格才看得到。

"Move fix" 选项：

- Silent（默认）：按键跟着转，你照常往自己看的方向走。
- Strict：按服务器的朝向走。
- Exact：按键精确转换。

B4 原来的 "Off" 在 Gale 里做不到，因为 Gale 是在你移动之前就把朝向放上去的。

## 自测

```
gradlew runClient -Pislandtest
```

这个命令会在开发版里搭一局假的起床战争：带侧边栏和队伍、一张床、一个带 Tab 栏条目的敌人，再用裂纹包模拟挖床。然后依次检查：

- 每种卡片；
- 能不能找到床；
- 床警报和挖床卡片，包括进度条只往前走；
- 防御变化提示和床上方的防御信息；
- AutoDef 分别站在东边、西边、高一格各围一遍，最后 26 格全满，而且没有放到图案外面；
- 床被拆的提示。

截图存在 `run/screenshots/island-*.png`。
