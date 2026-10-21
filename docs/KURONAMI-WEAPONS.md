# 怒麟武器模型

Forge 1.8.9 / Java 8。替换 Gale 的旧 JAR，避免同时安装多个 Gale 版本。

## 使用

- `SwordModel` 默认开启，`Skin` 默认 `Kuronami`，全部五种剑使用怒麟双刀与链条。可切换回 `Katana`。
- `BowModel` 默认开启，`Model` 默认 `Vandal`。其他选项是 `Sheriff`、`Marshal`、`Spectre`。
- 在 ClickGUI 的 Render 分类右键模块即可调整。旧配置如果关闭了 SwordModel，可手动打开。
- `BowModel → Scope` 默认开启：第一人称按住弓右键显示圆形瞄准镜，默认 3 倍，隐藏手臂和枪身，松开后平滑恢复并照常发射箭。
- `Scope zoom` 可调整为 1.5～8 倍。切物品、进入菜单、切换第三人称、死亡或关闭模块会退出开镜。开镜不改游戏保存的 FOV 和鼠标灵敏度。
- `Drop compensation` 默认开启：开镜蓄力后松开右键，按中心准星当前指向的方块或实体位置，自动调整这一箭的发射角度，补偿下坠。镜头和中心红点保持不动，不显示距离或下坠刻度。
- 背包、快捷栏和创造物品栏仍显示原版剑、弓图标。手持、第三人称、掉落物与展示框使用所选模型。
- 支持 JettModel 第一人称手臂和第三人称人物，也支持原版人物；LocalSpin 保留。

聊天命令：

```text
/gale set SwordModel Skin Kuronami
/gale set SwordModel Skin Katana
/gale set BowModel Model Vandal
/gale set BowModel Model Sheriff
/gale set BowModel Model Marshal
/gale set BowModel Model Spectre
/gale set BowModel Scope true
/gale set BowModel Scope zoom 4
/gale set BowModel Drop compensation true
```

`/gale SwordModel`、`/gale BowModel` 分别开关；关闭后恢复原版外观。

## 行为与性能

武器皮肤不改变物品 ID、伤害、耐久和附魔。剑仍按原版攻击、格挡，弓仍蓄力并发射箭。其他玩家需要相同模组才会看到这些皮肤。

开镜补偿会在发箭前临时向服务器发送计算出的角度，发送一次原版松开弓操作，然后恢复服务器之前的朝向。本地镜头不赋值、不跳动。计算采用 Minecraft 1.8.9 的非线性蓄力、发射位置偏移、每 tick 的 0.99 空气阻力与 0.05 重力；使用服务器最近收到的位置作为发射原点。

这不把箭改成直线子弹，也不取消原版随机散布。只处理中心准星射线命中的位置，不锁定附近目标、不预判移动。蓄力不足、超出可达射程、弹道碰到障碍、水中阻力、网络延迟或服务器自定义箭物理仍可能造成偏差。测距上限 128 格；无法求出可达低弧线时、未瞄准具体位置时、切出第一人称时或发包被 Gale 扣留时，保留普通发箭。关闭 `Drop compensation` 可恢复纯开镜外观。

双刀包含两个刀刃与连接链条，当前作为一个静态整体握持。源文件没有动画；不包含双手独立舞刀、链条物理、Valorant 检视动作、枪械射击机制或水流模拟。水材质使用蓝色近似，保留基础彩色贴图，未移植 PBR 材质。

每个怒麟模型一次绘制，静态网格和纹理按需缓存；贴图限制到 1024 像素后合图，切换已加载皮肤不会重新读取源文件。Vandal 13,793、Sheriff 4,049、Marshal 8,661、Spectre 11,475、双刀 11,508 个三角面。具体帧率取决于机器、分辨率和其他模组。

瞄准镜使用三个简单绘制批次生成遮罩、镜框和准星，不重复渲染世界，也不加载额外的大贴图。生命值、快捷栏和聊天仍可见。补偿测距在蓄力期间每 tick 更新一次，发射角度只在松开这一箭时求解，不逐帧扫描世界。

## 资源来源与许可

提供的 `kuronami_bundle.glb`：KiLLSHOT，KURONAMI BUNDLE，CC BY 4.0。

- 作者：https://sketchfab.com/killshotttttt
- 原模型：https://sketchfab.com/3d-models/kuronami-bundle-30e131c08e484ec1896900bde1b5b5ad
- 许可：http://creativecommons.org/licenses/by/4.0/
- 源 SHA-256：`bf58eda078a38468f4d5496a79302462921f814bd12b2e78fad38870860ed887`

转换改动：按武器拆分、烘焙节点变换、握持轴与尺寸适配、基础贴图缩小合图、水面材质替换、去除未使用法线和金属贴图。源 GLB 只读，未修改。

旧 Katana 的资源说明和署名见 `KATANA-SWORDS.md`，Jett 与击杀特效说明继续保留。

开发测试：`gradlew.bat --offline build runClient -Pkuronamitest --no-daemon`。报告在 `run/galemod-devtest.txt`，原生游戏截图在 `run/screenshots/kuronami-*.png`。

开镜及武器回归测试：`gradlew.bat --offline build runClient -Pscopetest --no-daemon`。开镜截图为 `run/screenshots/scope-*.png`，测试使用原版右键使用和松开接口，并检查服务器实际生成的箭、渲染投影、Jett 手臂隐藏、物品与菜单切换后的恢复。

补偿及完整回归测试：`gradlew.bat --offline build runClient -Pballisticstest --no-daemon`。先让原生 EntityArrow 执行多组不同距离、蓄力、方向和高差的确定性飞行，再通过真实发箭链路瞄准约 40 格外墙面，检查服务器箭轨迹、单次发箭、命中误差和本地/服务器朝向恢复，并与关闭补偿的水平箭作对照。
