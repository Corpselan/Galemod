# KE 图标接入 GD656 与盖亚优化

## 使用

打开任意 GUI 的 KillEffects，选择 `Theme = GD656`，再在 `GD preset` 中选择新增的四套：

- `Valorant CYRAX`
- `Valorant Reaver`
- `Valorant Forsaken`
- `Valorant Mystbloom`

原有 37 项保留原顺序，新项追加在后面，共 41 项。位置、大小、声音、音量沿用 KillEffects 现有设置；各套均接入了提供的 1～6 杀音频，6 杀以上沿用第 6 档。

Ayakashi 按要求不做。用户已经修改的 Theme 分级隐藏规则，以及 KillAura、Scaffold 的 `hideFrom(3)` 均保留。

## 空 PNG 的补齐

素材页：[Kingdom Archives / Killbanners](https://kingdomarchives.com/killbanners)。

从同一页面的原始 frame、emblem、pip、ring 图层取得真实素材，按原布局恢复了：

```text
cyrax_3_kills.png
cyrax_4_kills.png
cyrax_5_kills.png
reaver_3_kills.png
```

下载目录的原 ZIP 没有改写。运行时使用拆分的框架、中心图标、连杀标记，以便独立动画；原导出 PNG 带有的红色中心标记也予以保留。素材由 Riot Games 创建，图层经 Kingdom Archives 获取；具体资源地址记录在 JAR 内 `assets/gd656killicon/ke-sources.json`。

## 动画参考与实现

逐帧查看了以下 Riot CDN 升级演示中的 HUD，再用 GD656 的分层变换分别调整四套的节奏：

- [CYRAX Vandal Level 4](https://valorant.dyn.riotcdn.net/x/videos/release-13.08/d28e5474-415e-6cd6-5fb7-ef8c68869f6e_default_universal.mp4)：短促中心缩放、尖刺弹出与旋转。
- [Reaver Vandal Level 4](https://valorant.dyn.riotcdn.net/x/videos/release-13.08/d262fccc-465a-2da6-74ac-049ef3b3f759_default_universal.mp4)：中心先出现，外框略延后，回弹与转动较缓。
- [Forsaken Vandal Level 4](https://valorant.dyn.riotcdn.net/x/videos/release-13.08/ed8f7031-42d2-3eac-75c4-4fac5a8096b5_default_universal.mp4)：中心收束、外圈展开、翼状标记回弹。
- [Mystbloom Phantom Level 4](https://valorant.dyn.riotcdn.net/x/videos/release-13.08/7334920e-46b7-967e-b8b9-f29fd22e40e2_default_universal.mp4)：较柔和的中心缩放与花瓣标记展开。

演示地址经 [Valorant API](https://valorant-api.com/v1/weapons/skins?language=en-US) 的武器皮肤记录取得。

这是使用原始图层和 GD 动画系统的近似还原，没有取得原游戏的完整 VFX 时间线和粒子工程。旧 37 项的默认入场、回弹、旋转、淡出时序不变。

## 盖亚卡顿优化

- 盖亚的额外亮度/对比度处理改在后台执行；选择预设时提前准备。处理未完成时先绘制原贴图，完成后在渲染线程上传结果。
- 去掉原先生成动态贴图时无用途的 PNG 压缩。
- 使用批量像素处理，减少每像素调用和颜色转换中的临时对象。
- 缓存跨击杀清理和预设切换复用，最多 48 项，常规 RGBA 像素预算 64 MiB；准备队列最多 8 项。单张超大图超过预算时保留一个结果，避免每帧重算。
- 查询动画图集尺寸只读图片头，不解码整张图集；路径对象也缓存复用。
- 资源包重载时释放处理后的贴图、清理尺寸缓存并取消旧准备任务。

## 验证范围

没有启动游戏。离线编译打包通过，91 项独立 Java 检查通过；新 24 个 OGG 均完成解码验证。检查覆盖旧默认时序、四套独立时序、16,000 个像素转换结果、后台线程与上传线程分工、缓存和队列上限、资源重载清理、旧五档及新六档音效兼容。

同一台机器的独立 CPU 测试中，盖亚 256×12544 图集旧同步调用约 1571 ms，改后任务提交约 0.042 ms；后台计算与之后的 GPU 上传不包含在这个提交耗时内。10,000 次缓存命中调用耗时约由 109 ms 降至 1.28 ms。它们是具体代码路径的测试数据，不代表游戏帧率，游戏内 FPS 与画面体验尚未实测。
