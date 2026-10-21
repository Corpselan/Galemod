# Jappylay（小游戏）

Pug 写的起床战争数据面板（B4 脚本 `jappylay.java`），搬成了 Gale 的模块。在起床战争的等待大厅和对局里，左上角一张表列出每个人：

| 列 | 内容 |
|---|---|
| E | 最近一段时间（Encounters Timeout，默认 30 分钟）里在几个服务器碰到过他 |
| H | 头像（用 Tab 列表里的皮肤画；`/ov sc` 加的、不在 Tab 列表里的人才去 mc-heads.net 下载） |
| Player | 名字和 rank；对局里换成队伍颜色，按队伍排 |
| Star | 起床战争星级（按 Hypixel 的颜色） |
| FK/D | 最终击杀 / 最终死亡，FKDR Mode 选总的或某个模式 |
| WS | 连胜，Winstreak Mode 选模式；关了 API 的人显示空 |
| Tags | Prism 的标记：C 开挂、S 狙人，橙色中等、红色严重 |
| Online | 这次上线多久了，OFFLINE 是 API 显示离线 |
| Dead | 被最终击杀出局（看聊天里的 FINAL KILL!，重连了就去掉） |

数据来自 Prism 的 Flashlight（`flashlight.prismoverlay.com`，不要 key），和脚本一样：

- 每个 UUID 查一次 `playerdata`，结果按最终死亡数缓存 5 分钟到 1 天（打得越多缓存越久），连胜超过 50 的只缓存 10 分钟；
- 每分钟最多约 100 次请求，被限流时显示 `API` 和倒计时，出错显示 `E` 和倒计时，自己重试；
- 请求带 `X-User-Id`（你的 UUID），和脚本一样；
- 「Urchin API key」可以不填，填了查标记时带上；
- `/who` 里的名字先问 Flashlight，再问 Mojang，最后问 playerdb.co。

等待大厅里：有人进来（"has joined"）时自动发一次 `/who`，它的回答不显示在聊天里，只用来知道进来的先后（Sort By 选 Join Time 时按这个排）。等待大厅里离开 Tab 列表的人会从表里拿掉（`/ov sc` 加的除外）。

Nick（不是正版 UUID 的人）整行显示 `N`。

## 命令

| 命令 | 作用 |
|---|---|
| `/ov` | 列出命令 |
| `/ov sc <名字>` | 手动把一个人加到表里 |
| `/ov hide <名字>` | 不显示这个人 |
| `/ov clearhidden` | 清空不显示的名单 |
| `/ov reload` | 重新查表里所有人 |

## 设置

和脚本同名：各列的开关、Show Ranks、FKDR Mode、Winstreak Mode、Teams、Team Prefix、Show Yourself、Only Show On Tab（按住 Tab 才显示）、Use Bloom、Encounters Timeout、Overlay X / Y、Sort By、Sort Mode、Background Opacity、Header Color（0 黑色，360 彩虹）、Corner Radius、Font Size、Debug Mode。

## 和脚本的区别

- 头像直接用游戏里的皮肤画，不再每个人都去下载。
- rank 按 Hypixel 的样子显示：`[MVP+] 名字`。脚本里少了前面的 `[`，显示成 `MVP+] 名字`；VIP 的颜色也改回绿色。
- 脚本里 Background Color、Border Color 两个滑块和查延迟（ping）的代码没有用上，这里没搬。
- B4 里也在跑这个脚本时（脚本名 `jappylay`），Gale 的这个模块让路，`/ov` 也交给脚本。

## 自测

```
gradlew runClient -Pjappylaytest
```

在一局假的起床战争里（假的侧边栏和 Tab 列表，Flashlight 由本地的一个小服务器扮演，不会连外网）：读大厅和服务器号、PlayerA 的星级 / FKDR / 连胜 / rank / 标记 / 在线时长、nick、自动 `/who` 和隐藏回答、加入顺序、`/ov hide` 和 `/ov sc`、对局里的队伍颜色和 FINAL KILL。截图在 `run/screenshots/jappylay-*.png`。
