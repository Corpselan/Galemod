# 按 #1 / #2 / #3 控制 GUI 显示

两个 GUI 使用同一套规则；普通内容始终保留，只有写过隐藏规则的内容受版本号控制。`visibleWhen(...)` 的模式依赖条件仍正常生效。

## 修改版本号

修改 `src/main/java/gale/util/GuiVisibility.java` 的这一行，然后重新编译：

```java
public static final int LEVEL = 2;
```

左下角显示的 `#数字` 也读取这个值，不需要再手动修改 `RavenWatermark.USER_ID`。名字 Beta、premium-beta 和开发者名继续保留。

| 规则 | #1 | #2 | #3 |
| --- | --- | --- | --- |
| 没写隐藏规则 | 显示 | 显示 | 显示 |
| `hideFrom(2)` | 显示 | 隐藏 | 隐藏 |
| `hideFrom(3)` | 显示 | 显示 | 隐藏 |

`hideFrom(0)` 移除规则。只接受 0、2、3，写错数字会报错，避免悄悄失效。

## 隐藏整个模块

在模块构造函数中，`super(...)` 后面写：

```java
public Example() {
    super("Example", Category.RENDER, "示例模块");
    hideFrom(2); // #1 显示；#2 和 #3 隐藏整个模块
}
```

如果只想 #3 隐藏，就换成 `hideFrom(3);`。

也支持 `isHide(true);`，等价于 `hideFrom(2);`；`isHide(false);` 移除规则。以前重写 `isHide()` 并返回 true 的模块也兼容：#1 会显示，#2/#3 会隐藏。

`isHidden()/setHidden(...)` 是原来的 HUD 列表开关，与这里的 GUI 隐藏规则不同。

## 隐藏一个子选项

在 bool、number、range、mode、color、text 设置声明后面接规则：

```java
private final BoolSetting internal = bool("Internal", true).hideFrom(2);
private final NumberSetting strength = number("Strength", 1, 0, 5, 0.1).hideFrom(3);
```

第一个在 #2/#3 隐藏；第二个只在 #3 隐藏。

旧写法继续支持：

```java
private final BoolSetting internal = bool("Internal", true).isHide(true);
```

现在它等价于 `hideFrom(2)`，所以 #1 会显示。

## 隐藏子选项里面的单个模式值

`hideModes(版本号, "值1", "值2", ...)` 只给列出的模式值加规则，不隐藏整条设置。

```java
private final ModeSetting theme = mode("Theme", "2021",
        "2021", "2023", "2024", "end", "GD656")
    .hideModes(2, "2021", "2023", "2024")
    .hideFrom(3);
```

上面就是 KillEffects 里已经应用的写法：

| 版本号 | Theme 在 GUI 里的效果 |
| --- | --- |
| #1 | 显示 2021、2023、2024、end、GD656 |
| #2 | 显示 end、GD656 |
| #3 | 整条 Theme 隐藏 |

如果 #3 也要保留 end 和 GD656，去掉最后的 `.hideFrom(3)` 即可。

也可以分批设置不同的门槛：

```java
private final ModeSetting style = mode("Style", "A", "A", "B", "C")
    .hideModes(2, "A")
    .hideModes(3, "B");
```

#1 显示 A/B/C，#2 显示 B/C，#3 显示 C。

省略版本号的 `hideModes("A", "B")` 等价于 `hideModes(2, "A", "B")`；`hideModes(0, "A")` 移除 A 的规则。名字忽略大小写，并检查该值是否存在。所有模式值都被隐藏时，整条设置会自动消失。

## 配置兼容

隐藏只影响 GUI 展示与鼠标选择；模块功能、旧配置、代码访问仍保留。`getModes()` 和 `index()` 始终保留原始模式及原始序号，避免击杀贴图、脚本索引错位。两个 GUI 使用单独的 `getVisibleModes()`、`visibleIndex()`、`setVisibleIndex(...)`、`cycleVisible(...)` 来显示和选择。

旧配置如果仍然选中了被隐藏的模式，该值会继续生效，不会被自动替换；在显示当前值的 GUI 控件中会显示「请选择」，直到手动选一个可见的值。隐藏值仍可由配置或代码恢复。

## 验证

未启动游戏。离线编译打包通过，三种版本分别通过 52 项独立 Java 检查，共 156 项；覆盖实际模块与设置代码、配置读写、Raven 模式滑条及水印版本号。图形渲染和游戏内点击体验没有启动客户端实测。
