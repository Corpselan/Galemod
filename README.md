# Gale

Minecraft 1.8.9 Forge 客户端模组。可以单独用，也可以和 B4 等别的端一起装，补它们没有的功能。

- **不用 mixin。** 只用 Forge 事件、网络管道里插的 handler，以外加一层包住移动输入，不改原版类，所以不会和别的端的 mixin 冲突。
- **和别的端配合。** 模块可以声明“我和别的端的某某功能是同一件事”。B4 里开着 [GaleLink 脚本](companion/GaleLink.java) 时，B4 那边的同类模块一开，Gale 的就先停下，不会两边抢着改同一样东西；停下时 Gale 这边用的是 B4 报过来的目标、搭路状态和事件。Gale 也能请 B4 开关某个模块。
- **给写模块的人准备了一套 API。** 事件、设置、静默转头、目标查找、按真实规则预测移动、方块和放置、背包、发包和收包、2D/3D 绘制、服务器信息等。写法见 [docs/API.md](docs/API.md)。

## 安装

1. `gradlew build`，得到 `build/libs/GaleMod-2.0.jar`
2. 放进游戏的 `mods` 文件夹（和 B4 放一起没问题）
3. 想和 B4 配合：把 [companion/GaleLink.java](companion/GaleLink.java) 放进 B4 的脚本文件夹并开启

## 用法

- **Katana 剑模型**：SwordModel 默认开启，五种剑使用 Katana 刀体，快捷栏与背包图标保持原样；`/gale SwordModel` 开关。见 [docs/KATANA-SWORDS.md](docs/KATANA-SWORDS.md)。

- **本地大陀螺**：`/gale LocalSpin` 开关，F5 看自己的模型转圈，其他人看到的朝向不变；兼容 Jett 和原版人物。

- **Jett 人物模型**：`/gale JettModel` 开关自己的第三人称模型与第一人称双臂，手持当前 Minecraft 物品。设置和限制见 [docs/JETT-MODEL.md](docs/JETT-MODEL.md)。

- **调 HUD**：打开聊天框（T），HUD 上的每样东西都会出现一个框：
  - 拖动移动：会吸附到屏幕中线、边缘和别的元素的边，出现一条蓝色参考线；按住 Shift 不吸附
  - 拖框角上的小方块，或在它上面滚滚轮：放大缩小；右键：还原这一个；`/gale hud reset` 全部还原
  - 能调的：水印、模块列表、左下角信息、别的端的模块、通知、Profiler、Spotify 卡片、网易云卡片、灵动岛、击杀图标的位置、Jappylay 面板。现在没东西显示的会画一个虚框，告诉你它会出现在哪
  - 放在哪一边就贴着哪一边：拖到右边的会右对齐、变宽时往左长；拖到下面的往上叠。窗口大小、GUI 缩放变了也待在原处
  - 聊天框开着时，贴着底边的东西会平滑地升到输入框上面，关掉再落回去
  - 位置存在 `.minecraft/galemod/hud.json`（灵动岛、击杀图标、Jappylay 还是存在它们自己的设置里）
- **动画**：所有动画按真实时间算（不按帧数），帧数高低一个速度。模块列表里的模块开关时滑进滑出，其他的平滑让位；通知滑进、互相让位、滑出；Spotify 卡片淡入淡出；设置界面的展开、开关也改成了按时间的缓动
- **右 Ctrl**：打开设置界面（布局照 FPSMaster：左边分类，右边模块；样子是方块：橡木板打底，玻璃面板嵌在上面，贴图跟着你的材质包）。
  - 点模块那一行：展开它的设置（两列）；右键那一行：开关模块；点右边的开关也行
  - 点按键框：再按一个键或鼠标侧键绑定，Esc / Delete / Backspace 清除，左键取消；右键按键框直接清除
  - 展开后的设置：开关、滑块（范围可以拖两头）、选项、颜色（点色块打开取色器，有透明度和彩虹）、文字；右键任何一个设置恢复默认；最后两项是按键方式（切换 / 按住）和“不在列表里显示”
  - 直接打字就是搜索全部模块（名字、说明、设置名），Esc 先清空搜索，再按一次关闭
  - ClickGUI 模块里有 Scale（界面大小，Auto 按窗口高度选）和 Glass（Framed 一整块带边框 / Tiled 一格格的玻璃方块）
  - ClickGUI 的 **Style** 选 **Raven B4**：换成 B4 的界面（照 OpenB4 搬）。每个分类一个可以拖动的框，左键点模块开关，右键展开设置，滚轮滚动；中间是 galedev 标题（B4 的 "raven b4" 换成 "gale" 加小字 "dev"，动画一样），右下角是你的人物，左下角写着 `welcome, Dev #1` / `galemod premium-1` / `dev. Corpselan`（和 B4 一样的颜色和动画）。B4 的 Gui 设置也在 ClickGUI 里：Raven scale、Background blur、Scroll speed、Allow movement、Allow rotating、Dark background、Hide player model、Hide watermark、Rainbow outlines、Reset positions。Gale 有、B4 没有的设置类型照 B4 的样子画：选项是一条滑块，范围拖两头，颜色是一条色相（右键彩虹），文字点一下输入
- **Spotify**：游戏里看正在放的歌（屏幕上一张卡片），暂停、切歌、调音量、拖进度、放歌单、搜索、换设备；有自己的播放器界面（`/spotify gui`）和按键。声音还是 Spotify 自己放，控制要 Premium。第一次用要建一个免费的 Spotify 应用填 Client ID，步骤见 [docs/SPOTIFY.md](docs/SPOTIFY.md)
- **网易云音乐**（NetEase Music 模块，`/netease`、`/ne`、`/wyy`）：搜歌、推荐歌单、排行榜、扫码登录后听自己的歌单和 VIP，**声音从游戏里出来**；屏幕上一张红色卡片（小条跟着鼓点跳），播放器和 Spotify 的长得一样，左上角的按钮在两个播放器之间切换。见 [docs/NETEASE.md](docs/NETEASE.md)
- **3D 歌词**（Lyrics 模块，渲染分类，照 DangoMusic 做的）：网易云或 Spotify 正在放的歌，歌词一句句浮在面前，唱到哪个字哪个字弹出来，手绘感的轻微抖动，Deep Glow 泛光，清晰的中文字体；也可以换成 Text flash（大字砸到屏幕中间）。网易云的歌按 id 拿歌词（有逐字时间就用逐字的），Spotify 的歌按歌名去网易云或 LRCLIB 找。见 [docs/NETEASE.md](docs/NETEASE.md)
- **Meowtils 扩展**（`.meowtils` 文件，比如 Furiah's Visuals）：不装 Meowtils 也能用。
  - 放进 `.minecraft/galemod/extensions/`（没装 Meowtils 时 `.minecraft/meowtils/extensions/` 里的也会读），启动游戏时加载；`/gale extensions` 列出加载了什么，`/gale extensions reload` 重新加载，`/gale extensions folder` 打开文件夹
  - 扩展的模块出现在新的「扩展」分类里（没有扩展时这个分类不显示），开关、按键、设置都跟 Gale 的模块一样，保存在 Gale 的配置里；扩展的命令（比如 `/furiah`）直接在聊天里用
  - Gale 带了一份 Meowtils 的扩展 API（`gale.meowtils.api`，照 Meowtils 2.0.1）。加载时把扩展里对 Meowtils 的引用改指向它，所以和真的 Meowtils 同时装也不冲突（那时 Meowtils 自己的文件夹由 Meowtils 读）
  - 扩展能收到的事件：tick、渲染 tick、世界渲染、HUD、攻击、聊天、按键、鼠标、实体进世界、打开界面、点方块、渲染玩家、世界加载/卸载、收发包。Meowtils 用 mixin 做的两个（字体改字、背包点击前）Gale 不发，监听它们的扩展照样能加载
  - 扩展是别人写的代码，加载后和 Gale 一样能做任何事。只放你信得过的
  - Furiah's Visuals 1.3.0 测过：加载、开关、自己的菜单（右 Shift 或 `/furiah`）、动态天空、练习假人都正常。它第一次启动时只开 WaterMark，其余在它的菜单里开；武器要在它的 Weapons 里设规则（比如剑换成哪把）。它在 Forge 上不替换原版 HUD 对象（Forge 的 HUD 不是原版的那个类，它自己跳过了这一个钩子；WaterMark 照样能显示），在 Meowtils + Forge 上也一样
- **B4 脚本**：装了 Gale，B4 脚本就能用 `bridge.get("gale")` 做 B4 做不到的事：
  - 读写、造、扣任何数据包；
  - 战斗钩子；
  - 服务器位置等数据；
  - 渲染和输入钩子；
  - 自己的界面；
  - 文件和网络；
  - 改准星。

  说明在 [docs/SCRIPTS.md](docs/SCRIPTS.md)，例子在 [companion/GaleApiDemo.java](companion/GaleApiDemo.java)。ClickGUI 的「辅助」里每一块都能单独关掉，还能看到哪个脚本在用。
- **帧数**：ClickGUI 的「优化」里有两个模块，默认关着。
  - **Profiler**（帧时间分析）：左上角显示每帧的时间花在哪：
    - B4、每个 B4 脚本（连了 Gale 的显示脚本名）、FPSMaster、Gale；
    - 游戏自己的各部分：画地形、画实体、粒子、文字、等显卡、限帧等待……；
    - GC 停顿。

    `/gale perf` 在聊天栏列出最近 10 秒，详细的（每部分里是哪些方法）存到 `galemod/perf-report.txt`。开着大约多花 1-2% 的帧时间。
  - **Performance**（不用 mixin 的优化）：
    - 被整块不透明方块完全挡住的实体不画，默认不剔除玩家；
    - 按类型调渲染距离；
    - 粒子上限，视野外的粒子不加；
    - 远处告示牌不画字；
    - 窗口在后台时限帧。

    FPSMaster 的性能模块开着同样的功能时，Gale 的那一项自动让路，模块名后面会写让了哪些。
- **命令**：输入 `/gale` 查看全部。这些命令在客户端本地执行，不会发到服务器。

| 命令 | 作用 |
|---|---|
| `/gale <模块>` | 开关 |
| `/gale list` | 列出模块：绿=开，红=关，黄=开着但在给别的端让路 |
| `/gale bind <模块> <键> [hold]` | 绑定按键，例如 `R`、`MOUSE4`、`none`；加 `hold` 就是按住时才开 |
| `/gale set <模块> <设置> <值>` | 改设置 |
| `/gale get <模块>` | 看设置 |
| `/gale config save/load/list [名字]` | 配置文件 |
| `/gale friend add/remove/list` | 好友，默认不会被当成目标 |
| `/gale c17 <频道> [十六进制数据]` | 立刻发一个 C17 插件消息包。给 B4 脚本用（`client.runForgeCommand("gale c17 send")`），B4 脚本自己造不出 C17 |
| `/gale link` | B4 脚本调用：把 Gale 的脚本 API 放进 B4 的 `bridge`，脚本里 `bridge.get("gale")` 拿到它 |
| `/gale api [off <脚本名>]` | 脚本 API 的状态：连上没有、八块辅助开没开、哪些脚本在用、谁在监听什么；`off` 取消某个脚本的全部监听 |
| `/gale perf` | 帧时间分析的结果（要先开 Profiler） |
| `/gale info` | 版本、一起加载的 mod、哪些端在报告状态、桥接状态 |

配置保存在 `.minecraft/galemod/config.json`，任何改动大约 1 秒后自动保存。

## 现在有什么

- **KillEffects（渲染）**：Champions 2021 / 2023 / 2024 的 1～6 杀图标、对应音效、非线性缩放和升级过渡。打开 `/gale KillEffects`，用 Theme 选择年份，本地预览 `/gale killfx preview all`。设置、识别范围和性能说明见 [击杀特效说明](docs/KILL-EFFECTS.md)。
- **Island（渲染）**：灵动岛，从 B4 的 BetterIsland 搬过来的。顶部是一个胶囊，有事时变成卡片，按优先级依次是：床被挖（谁在挖、挖哪块、进度）、床警报、搭路、拆床、模块开关。
- **AutoDef（世界）**：自动守床，从 BedDefender 搬过来的。按图案把床围起来，围完自己关。

  这两个模块共用 Gale 的起床战争追踪，说明见 [灵动岛和自动守床](docs/ISLAND-AUTODEF.md)。
- **Script Manager（脚本）**：不装 B4 也能跑 B4 脚本，同样的 API、事件和设置；**Trajectories（渲染）**：弓、蛋、雪球、珍珠的落点，飞着的珍珠和火焰弹的路线。说明见 [在 Gale 里直接跑 B4 脚本](docs/B4-SCRIPTS-IN-GALE.md)。
- **Jappylay（小游戏）**：Pug 的起床战争数据面板（B4 脚本），大厅和对局里每个人的星级、FKDR、连胜、Prism 标记、在线时长、碰到几次、出局没有；`/ov` 命令。说明见 [Jappylay](docs/JAPPYLAY.md)。
- **从 B4 搬来的模块**：KillAura、AimAssist、Backtrack、Lag Range、BedAura、Scaffold、Tower、Bed Wars（提醒）、AntiCheat、Nametags、PlayerESP、Chams，加上 AntiBot、B4 Settings、Locraw。代码照 OpenB4 搬，设置和默认值和 B4 一样。B4 开着同名模块时 Gale 的让路，没有 B4 时 Gale 的顶上。说明见 [从 B4 搬过来的模块](docs/B4-MODULES.md)。

框架包括：

| 部分 | 内容 |
|---|---|
| 事件 | tick 前后、更新前后、移动输入、收发包（可改、可取消）、按键和鼠标、聊天、攻击、世界、服务器、界面、2D/3D 绘制 |
| 静默转头 | 按优先级请求、限制转速、GCD、移动修正（仍往镜头方向走）、慢慢转回镜头、服务器拉回时镜头不被转 |
| 静默槽位 | 每 tick 拿着请求的槽位，快捷栏显示自己的，不会每 tick 都发包 |
| 预测和跟踪 | PlayerSim 按真实规则预测你的移动；EntityTracker 记录周围生物的位置、速度、受伤和被你打 |
| 工具 | 找目标、队伍和机器人判断、射线、方块和放置、背包、点击和 CPS、发包和扣包、服务器信息、2D/3D 绘制和投影 |
| 模块系统 | 设置（开关、数字、范围、模式、颜色、文字）、按住/切换两种按键、配置和多套配置、`/gale` 命令、设置界面、HUD、提示 |
| 和别的端配合 | 同类功能让路、读取 B4 开着的模块、请 B4 开关模块、HTTP 桥接 |

自带 HUD、ClickGUI、Targets（目标规则）、Rotations（转头规则）、Bridge（桥接）、KillEffects（2021 / 2023 / 2024 击杀特效）、JettModel、LocalSpin、SwordModel（怒麟双刀 / Katana）、BowModel（怒麟 Vandal / Sheriff / Marshal / Spectre）、Island（灵动岛）、AutoDef（自动守床），以及「辅助」里给 B4 脚本用的八块 API（Script Packets、Combat、Data、Render、Input、Screens、System、Crosshair）。武器模型的开关、皮肤选择与资源署名见 [怒麟武器说明](docs/KURONAMI-WEAPONS.md)。

[src/main/java/gale/example](src/main/java/gale/example) 里有四个示例模块，各演示一种 API：

- Sprint：输入事件
- SafeWalk：移动预测
- AimAssist（模块名 Aim Example）：目标查找和转镜头
- Velocity：改收到的包

它们只在开发时用，不打进 jar。

## 自测

```
gradlew runClient -Pdevtest
```

这条命令会启动开发版游戏、新建超平坦世界，自动检查上面的框架：

- 事件
- 预测和游戏实际位置是否一致
- 静默转头：服务器拿到的朝向、镜头不动、移动修正、转速限制、慢慢转回、服务器拉回时的处理
- 静默槽位，包括发了几个包
- 攻击和实体跟踪
- 按住绑定
- 让路和桥接、命令、设置界面、配置保存

结果写到 `run/galemod-devtest.txt`，然后游戏自己关闭。测试代码不会打进 jar。

`gradlew runClient -Pscripttest` 只跑脚本 API 那部分（用一个假的 B4 脚本把每块都调一遍，还会把函数和事件的参考写到 `run/SCRIPTS-reference.md`）。

`gradlew runClient -Poptimizetest` 只跑帧时间分析和 Performance 那部分，最后在墙后放 180 个穿全套钻石甲的盔甲架，比较 Performance 关和开的帧数。

`gradlew runClient -Pislandtest` 只跑 Island 和 AutoDef：在一局假的起床战争里把每种卡片、找床、挖床、AutoDef 围床都试一遍，截图存在 `run/screenshots/island-*.png`。

`gradlew runClient -Pb4porttest` 只跑从 B4 搬来的模块：默认设置、pre-motion 事件、安全走路、KillAura 打怪和给 B4 让路、AimAssist、Scaffold 搭路、BedAura 拆床、GaleLink 转来的事件。

`gradlew spotifyCoreTest` 和 `gradlew runClient -Phudtest` 测调 HUD：拖动和吸附、拖角和滚轮缩放、右键还原、聊天框开着时底部上升、灵动岛和击杀图标跟着改设置、模块列表滑动、`/gale hud reset`，截图在 `run/screenshots/hud-*.png`。

`gradlew runClient -Pspotifytest` 测 Spotify：本地有一个假的 Spotify，不需要账号，也不连外网（见 [docs/SPOTIFY.md](docs/SPOTIFY.md)）。

`gradlew neteaseCoreTest`（不开游戏）和 `gradlew runClient -Plyricstest`（游戏里，截图在 `run/screenshots/lyrics-*.png`）测网易云和 3D 歌词；联网的部分要能上网，不用登录，放歌时没有声音（见 [docs/NETEASE.md](docs/NETEASE.md)）。

`gradlew runClient -Pmeowtilstest` 只跑 Meowtils 扩展（要先把 Furiah's Visuals 放进 `run/galemod/extensions`）：加载、开关（它的钩子装上又拆掉）、它的命令、菜单、天空等效果、重新加载，还有一个自己写的小扩展把每种设置都试一遍，截图在 `run/screenshots/meowtils-*.png`。

`gradlew runClient -Pfvtest` 看 Furiah's Visuals 的武器模型和击杀特效在 Gale 下的样子（用 `run/config/furiahsvisuals.json` 里的 FV 设置）：剑在 Gale 的 SwordModel 开和关时、斧、弓、木棍，再用 FV 自己的测试假人放一次击杀，截图在 `run/screenshots/fv-*.png`。

`gradlew runClient -Pravengui` 只跑 Raven B4 样式的设置界面：打开、展开分类框和模块、左键开关、拖滑块、拖框、关掉后界面大小恢复，截图在 `run/screenshots/raven-*.png`。

`gradlew runClient -Pb4rendertest` 只跑从 B4 搬来的 Nametags、PlayerESP、Chams：两个假玩家，读回画面看每种样式画没画、原版名牌藏没藏、隔墙看不看得见，以及给 B4 让路。

`gradlew runClient -Pjappylaytest` 只跑 Jappylay：假的起床战争大厅和对局，Prism 的接口由本地服务器扮演。

`gradlew runClient -Pguishots` 只跑设置界面那部分（1920x1080 窗口）：用点击把开关、滑块、按键、取色器、搜索都试一遍，截图存在 `run/screenshots/gale-gui-*.png`。

## 目录

```
src/main/java/gale/
  Gale.java            入口：注册模块、读配置、开桥接
  core/                Hooks（Forge 事件 → Gale 事件）、NetHook（网络管道）、InputHook（移动输入）、
                       MotionHook（发移动包前的 MotionEvent、安全走路）、
                       RotationManager（静默转头）、SlotManager（静默槽位）、EntityTracker、
                       Scheduler、ServerState、Notifications
  event/               事件总线和所有事件
  module/              Module、设置类型、ModuleManager、自带的设置模块
  util/                给模块用的工具（见 docs/API.md）
  bridge/              Bridge（和脚本互通）、External（别的端开着的模块）、B4State（GaleLink 报来的 B4 状态）
  b4port/              从 OpenB4 搬来的模块和它们用的 B4 工具类
  b4script/            Gale 自己跑 B4 脚本（OpenB4 的脚本运行时）
  spotify/             Spotify（Web API、播放器界面、卡片）
  netease/             网易云音乐（接口、扫码登录、MP3 播放、播放器界面、卡片）
  lyrics/              3D 歌词（歌词来源、LRC/YRC、拆行、Text flash）
  render/              CjkFont（清晰的中文字体）、DeepGlow（泛光）、GlState
  command/ config/ ui/ 命令、配置、设置界面
  example/             示例模块（只在开发时用）
  devtest/             游戏内自测（只在开发时运行）
companion/GaleLink.java  B4 里用的配套脚本
```


## 新击杀特效测试

运行 `test-new-killeffects.bat`，或执行：

```powershell
.\gradlew.bat runClient -Pketest --offline --console=plain
```

此模式轮播 CYRAX、Reaver、Forsaken、Mystbloom、Ayakashi 的 1～6 杀效果与音效，
使用独立的 `run-ke-preview/` 目录。F6 换特效，F7 换等级，F8 重播，F9 开关自动轮播。
Ayakashi 六杀为左右各三条狐尾，顶部留空。缺失尾部素材参照画面补绘，具体来源见
`src/main/resources/assets/gd656killicon/ayakashi-sources.json`。

`--offline` 适用于依赖和游戏资源已经缓存的电脑。首次在新电脑构建或测试时，去掉
`--offline` 以下载所需依赖和游戏资源。
