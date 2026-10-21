# Katana 剑模型

Minecraft 1.8.9 / Forge / Java 8。此版本保留 JettModel、本地陀螺 LocalSpin、Champions 2021/2023/2024 击杀特效。

把 `GaleMod-0.2.0-katana.jar` 放进游戏的 `mods` 文件夹，替换旧的 Gale JAR；同一实例只保留一个 Gale 版本。桌面工程产物为 `build/libs/GaleMod-1.0.jar`。

## 效果和开关

- 新模块 **SwordModel 默认开启**。输入 `/gale SwordModel` 开关，或在右 Ctrl 的渲染分类中调整。
- 木剑、石剑、铁剑、金剑、钻石剑共用提供的 Katana 刀体模型；兼容 Jett 与原版人物的第一/第三人称持剑，掉落物也使用刀体。
- 快捷栏、背包、箱子等 GUI 保留原来的剑图标、附魔光效、耐久条和名称。没有覆盖任何 Minecraft 图标 PNG。
- 剑本身的物品类型、伤害、附魔、耐久、攻击距离和服务器数据不变。这是本地外观替换，其他人的客户端不会自动获得此模型。
- 模组中继承 ItemSword 且提供常规 `物品注册名#inventory` 模型的剑也会尝试接入；非常规模组渲染器组合未验证。

## 模型处理

源 GLB 还包含一个独立摆放的刀鞘，持剑模型只取刀体。刀体约 79,868 个三角形、59,011 个顶点；统一握持原点，调整方向和比例后使用。

源文件没有图片纹理，使用 GLB 的材质颜色、简单高光和眼睛发光呈现外观。附魔剑增加流动紫色光泽。没有实现 Sketchfab 的完整 PBR 环境反射或原游戏专用武器动画。

网格首次使用时上传到显卡并缓存；之后每把可见的剑使用一次模型绘制，不在每帧重新解析 GLB 或逐三角形填充 Minecraft 的 CPU 顶点缓存。模型细节较多，屏幕中同时出现大量剑时仍会增加显卡负担。

## 验证

```powershell
.\gradlew.bat --offline build runClient -Pkatanatest --no-daemon
```

测试包含五种剑、开启前后 GUI 图标像素对比、Jett/原版第一与第三人称、附魔、掉落物、关闭恢复原版和 OpenGL 错误检查。报告在 `run/galemod-devtest.txt`，实际游戏截图在 `run/screenshots/katana-*.png`。开发测试类不在成品 JAR 中。

重新转换资源（需要 Python 与 NumPy）：

```powershell
python tools/convert_katana.py D:\Download\katana_valorant_draft.glb src/main/resources/assets/galemod
```

源文件 SHA-256：`623d3281bac1e7aa6c7008da40b86067c7cf0b3e34895d929b70826b95d44563`。源 GLB 仅读取，未修改。

## 模型署名

源文件内嵌的署名与许可证信息：

**Katana Valorant Draft**，作者 [gkari](https://sketchfab.com/gkari)，[原模型页面](https://sketchfab.com/3d-models/katana-valorant-draft-73ae18bf62f249f9ba6a3824201ce8b0)，[CC BY 4.0](https://creativecommons.org/licenses/by/4.0/)。

此移植去除了独立刀鞘，旋转、缩放并转换为 Minecraft 客户端的静态显卡网格；根据材质颜色渲染并增加了附魔光泽。原作者与原始来源信息也保存在模型资源的 `model-info.json` 中。
