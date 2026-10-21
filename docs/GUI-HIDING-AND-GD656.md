# GUI 隐藏与 GD656 击杀预设

模块需要在 GUI 中隐藏时，重写 `gale.module.Module.isHide()`：

```java
@Override
public boolean isHide() {
    return true;
}
```

默认返回 `false`。Gale 的分类、数量与搜索，以及 Raven 的分类均使用同一过滤规则；返回值变化后也会刷新。隐藏不会停用模块，不影响配置、命令、快捷键、事件监听或 HUD 的 `isHidden()` 设置。

只隐藏模块中的某一个子选项时，在该设置后添加 `.isHide(true)`：

```java
private final BoolSetting sound = bool("Sound", true).isHide(true);
private final NumberSetting volume = number("Volume", .7, 0, 1, .05).isHide(true);
```

该标记只隐藏这一项，不会隐藏整个模块；支持所有设置类型，Gale 和 Raven 的 GUI 均生效。设置值仍然生效，也照常保存到配置。`sound.isHide(false)` 可取消隐藏，`sound.isHide()` 可读取标记。它与 `visibleWhen(...)` 同时使用时，只有未隐藏且条件成立才显示。根据条件显示也可直接使用已有的 `.visibleWhen(() -> 条件)`。

在“渲染 → KillEffects”中启用模块，将 `Theme` 切到 `GD656`，再通过 `GD preset` 选择原项目的 37 套预设。`Sound`、`Volume`、`Size`、`X`、`Y` 控制音效、音量和整个预设的大小与位置。此前的 Champions 与 end 仍保留。

移植保留 GD656 原版客户端图标、滚动/卡牌/连杀/Valorant 动画、击杀字幕、内置预设和音效，并使用 Gale 现有的击杀判定与重复消息去重。原项目的服务端协议、载具联动、伤害统计、成就和排行榜没有移植；分数文字仅是本地击杀显示，不会更改服务器分数。

启用 KillEffects 后，`/gale killfx preview 1` 可本地预览，`/gale killfx preview all` 可依次预览连杀等级。

验证：`gradlew.bat runClient -Pgdtest --offline`；原 KillEffects 回归：`gradlew.bat runClient -Pkillfxtest --offline`。
原作者与许可保存在 JAR 内的 `META-INF/GD656-NOTICE.txt`。
