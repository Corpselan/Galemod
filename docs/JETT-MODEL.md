# Jett 人物与第一人称持物

适用 Minecraft 1.8.9、Forge、Java 8。这个版本同时保留 Champions 2021、2023、2024 击杀特效。

把成品 `GaleMod-0.2.0-jett-v2.jar` 放入游戏的 `mods` 文件夹，替换旧的 Gale JAR；同一个游戏实例只保留一个 Gale 版本。桌面工程中的构建产物叫 `build/libs/GaleMod-1.0.jar`。

## 开启

聊天输入 `/gale JettModel`，或右 Ctrl 打开设置，在渲染分类中开启 JettModel。默认关闭；再输入一次即可恢复原版模型。

- 第一人称使用自然单手握持，工具、方块与弓分别调整位置；空手保留放松手势。手中显示当前选中的 Minecraft 物品，切换快捷栏后平滑过渡。
- 使用模型原始臂长计算手肘，避免上臂、前臂和手腕断开。已降低物品比例，重新调整手指弯曲和握持点。
- F5 使用 Jett 全身人物，仅替换自己的本地显示。其他玩家、碰撞箱、攻击距离、物品功能不变。
- 走路、转头、下蹲、挥手、格挡和使用物品跟随 Minecraft 状态。弓仍由原版物品渲染器选择拉弓贴图。
- 地图使用原版双手和实时地图显示。

## 设置

`/gale get JettModel` 查看设置，右 Ctrl 的界面中也可以调整。

| 设置 | 默认 | 用途 |
|---|---|---|
| First person | true | 第一人称双臂 |
| Third person | true | 自己的第三人称模型 |
| Hand X / Hand Y / Hand Z | 0 | 左右、上下、前后微调双臂和物品，范围 -0.3～0.3 |
| Hand FOV | 70 | 第一人称模型视角，范围 50～100；数值越大，模型看起来越小 |

例如 `/gale set JettModel Hand FOV 80`；`/gale reset JettModel` 恢复默认位置。设置名有空格时直接写，不加引号。

## 模型和动作的范围

提供的 ZIP 中是带 272 根骨骼的 Jett GLB，没有动画片段。当前动作是为 Minecraft 状态编写的程序动画，手指持握为预先计算的形变，不是 Valorant 原始持枪动画。不同形状的物品共用持握点，手指不会逐个贴合所有模组物品。

Jett 使用模型自带服装；原版盔甲、皮肤、披风层不会叠加在第三人称 Jett 上。没有面部表情、物理头发和布料模拟。复杂光影及其他替换玩家/第一人称渲染的模组组合尚未验证。

GLB 身体网格的 UNORM8 权重中约一半顶点权重合计不符合规范。转换工具利用保留的主骨骼索引、焊接后的网格邻接关系重建和平滑身体权重，保留原始模型和贴图文件不变。因此这不是原始骨骼权重的逐位还原。

## 渲染开销

模型与贴图首次显示时上传并缓存，之后切换模块不重复加载。每帧仅更新 15 个骨骼矩阵，由显卡完成蒙皮；不在 CPU 上逐顶点变形。

- 第三人称：42,884 个三角形，3 次模型绘制。
- 第一人称：16,644 个三角形，只画双臂，1 次模型绘制。
- 当前物品使用 Minecraft 自己的渲染器，其绘制次数视物品、附魔和材质而定。
- 只替换一个本地玩家，其他玩家数量不会增加 Jett 模型开销。

需要支持 GLSL 1.20 与 ARB_vertex_array_object 的显卡驱动。初始化不支持时保留原版显示，并在游戏日志记录原因。性能仍随分辨率、显卡和其他模组而变，不保证固定 FPS。

## 构建与验证

Windows 文件名是 `gradlew.bat`，在项目目录执行：

```powershell
.\gradlew.bat --offline build
.\gradlew.bat --offline build runClient -Pjetttest --no-daemon
```

第二条命令会在独立测试世界检查模型、第一/第三人称、物品、地图回退、关闭模块及 OpenGL 错误，截图保存在 `run/screenshots/jett-*.png`，报告在 `run/galemod-devtest.txt`。测试类不会打入发布 JAR。

联合检查三个年份的击杀特效时使用 `.\gradlew.bat --offline build runClient -Pjetttest -Pkillfxtest --no-daemon`。

本次验证同时检查本地陀螺的 Jett 与原版玩家渲染、第一人称相机不旋转、渲染前后真实朝向字段不变、发送的移动包朝向不变，以及测试服务器看到的朝向不变。GPU 模型、手腕/手肘连续性、物品切换和 OpenGL 错误也包含在测试中。实测记录见交付测试报告；CPU 提交时间不包含资源首次加载和 GPU 执行，不能换算为其他设备的帧数保证。

## 本地大陀螺 LocalSpin

- `/gale LocalSpin` 开关，默认关闭。
- 开启后按 F5，可以看到自己的模型高速旋转；背包预览也会旋转。
- 默认每秒 4 圈；`/gale set LocalSpin Speed 2160` 改为每秒 6 圈，范围 90～3600 度/秒。
- 它只对本地渲染矩阵施加旋转，不修改玩家的 yaw/pitch、身体/头部朝向、移动或网络包。因此别人看到的朝向仍然正常，第一人称相机和手持物也不跟着转。
- 支持 JettModel 和原版人物。只想玩陀螺时可以关闭 JettModel，独立开启 LocalSpin。
- 再次输入 `/gale LocalSpin` 关闭。两者都是渲染分类中的独立模块。


重新转换模型需 Python、NumPy，并执行：

```powershell
python tools/convert_jett.py D:\Download\jett-valorant.zip src/main/resources/assets/galemod
```

转换工具仅读取 ZIP 内指定的 GLB，不执行压缩包内文件。源码 GLB SHA-256：`00f4ef9b7713cd582e6af3a94e7a190c420f12c061f49fd6042e70a93143b49e`。
