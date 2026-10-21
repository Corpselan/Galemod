# Spotify

在游戏里看 Spotify 正在放什么、暂停、切歌、调音量、拖进度、放歌单、搜索、换设备。模块在「客户端」分类里，叫 **Spotify**；命令是 `/spotify`（也可以 `/sp`）。

**声音还是 Spotify 自己放**（电脑上的 Spotify、手机、音箱都行）。Spotify 只允许别的程序看和控制它的播放，不允许别的程序自己播它的歌，所以 Gale 是一个遥控器加显示屏。

- **看**正在放什么：任何账号都可以；
- **控制**（暂停、切歌、音量、进度、放歌单……）：Spotify 只对 **Premium** 开放。免费账号按控制会提示「需要 Premium」。

## 第一次用：建一个 Spotify 应用（免费，两分钟）

Spotify 要求每个连它的程序有自己的「应用」。Gale 不带共用的，用你自己的：

1. 打开 <https://developer.spotify.com/dashboard>，用你的 Spotify 账号登录，点 **Create app**；
2. 名字和描述随便填；**Redirect URI** 填 `http://127.0.0.1:25616/callback`（要一字不差，是 `127.0.0.1` 不是 `localhost`）；API 勾 **Web API**；保存；
3. 在应用的 **Settings** 里复制 **Client ID**；
4. 游戏里打开 ClickGUI → 客户端 → Spotify，把 Client ID 填进 **Client ID**；
5. 打开 Spotify 模块，输入 `/spotify login`：浏览器打开 Spotify 的登录页，登录并同意，回到游戏就好了。

登录一次就行：登录信息存在 `.minecraft/galemod/spotify.json`（只在你电脑上，只发给 Spotify）。它相当于你账号播放控制的钥匙，不要发给别人；`/spotify logout` 会删掉它。

25616 端口被占用的话，把模块的 **Login port** 改成别的，开发者后台的 Redirect URI 也改成同一个端口。

## 用法

**屏幕上的卡片**：封面、歌名（太长会滚动）、歌手、进度条，在放时右上角有跳动的小条。位置、大小、暂停时隐藏都在模块设置里。换歌时会弹通知（Song notifications）。

**播放器**（`/spotify gui`，或设置一个 Player key）：

- 左边：正在播放、喜欢的歌曲、歌单、搜索、设备，下面是你的歌单（滚轮滚动）；
- 中间：歌的列表。**左键**放这首（歌单里的歌会接着放这个歌单），**右键**加到队列；
- 下面：随机、上一首、播放/暂停、下一首、循环（关 → 列表 → 单曲），拖进度条跳到哪，拖音量条调音量；
- 键盘：空格播放/暂停，← → 前后 10 秒，↑ ↓ 音量；
- 「设备」里点一个，就换到它上面放。

播放器有自己的大小（和 GUI 缩放 2 差不多），在什么 GUI 缩放下都一样大。

**按键**：模块设置里的 Play/Pause key、Next key、Previous key、Volume up/down key、Player key，填键名（`K`、`F8`、`MOUSE4`……），`NONE` 不绑。

**命令**：

| 命令 | 作用 |
|---|---|
| `/spotify login` / `logout` | 登录 / 退出（删掉保存的登录） |
| `/spotify gui` | 打开播放器 |
| `/spotify status` | 现在放的是什么 |
| `/spotify p` / `play` / `pause` | 播放暂停 |
| `/spotify n` / `prev` | 下一首 / 上一首 |
| `/spotify vol 30`、`vol +10`、`vol -10` | 音量 |
| `/spotify seek 1:30` | 跳到 1 分 30 秒 |
| `/spotify shuffle` / `repeat` | 随机 / 循环 |
| `/spotify search 晴天` | 打开播放器搜索 |
| `/spotify devices`、`devices 2` | 设备列表、换到第 2 个设备上放 |

## 刷新和限制

- 每 2 秒问一次 Spotify 在放什么（Refresh 可以改 1～10 秒），打开播放器时每秒一次；中间进度条在本地推算，看着是连续走的。Spotify 嫌太快时（429）按它说的时间等。
- 所有请求都在后台线程，不卡游戏。
- 只有你登录以后才会连 Spotify；没登录、模块关着时什么都不发。
- 歌单的歌只读前 100 首，喜欢的歌曲读最近 50 首，搜索每次 20 条。

## 代码

`gale.spotify`：

- `SpotifyAuth`：登录（PKCE，不需要密钥，127.0.0.1 上的回调页）；
- `TokenStore`：保存登录；
- `SpotifyApi`：Web API（自动续期，401 重试一次，429 等待，错误说成中文）；
- `SpotifyPlayer`：后台线程轮询、控制、先改界面再等 Spotify 确认；
- `CoverCache`：下载封面；
- `SpotifyModule` / `SpotifyCommand`：Gale 模块和 `/spotify`；
- `ui`：卡片、播放器、图标、封面贴图。

## 自测（不需要 Spotify 账号）

`gale/devtest/FakeSpotify` 在 127.0.0.1 上扮演 Spotify（登录、Web API、六首假歌、两个歌单、两个设备；能切换成免费账号、没有设备、令牌过期、请求太快）：

```
gradlew spotifyCoreTest
gradlew runClient -Pspotifytest
```

第一个不开游戏，测登录、保存的登录、每个控制、列表、搜索、设备、令牌过期、免费账号、没有设备、429、后台线程、封面、退出；第二个在游戏里测卡片、`/spotify`、播放器的每一页、拖动、队列、设备、按键，截图在 `run/screenshots/spotify-*.png`。
