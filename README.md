# Music

一个自用的 Android 音乐播放器，用 Kotlin + Jetpack Compose 写的。连自己的 [Navidrome](https://www.navidrome.org/)（Subsonic 协议）服务器听歌，服务器不在身边时自动退回读 U 盘；首页按"心情"分类播放，心情是本地一个独立的标记文件说了算，不依赖歌曲文件本身的标签。

这个项目从头到尾都是跟 [Claude Code](https://claude.com/claude-code) 结对写出来的——包括这份 README、下面的踩坑记录，都是。仓库开源出来，一是给自己留个存档，二是如果你也想要个"能连自己 NAS、心情分类靠谱、体积小"的播放器，直接照着下面的步骤应该能跑起来。

> 个人小项目，没有上架计划，也不保证长期维护。issue/PR 欢迎，但别指望秒回。

---

## 目录

- [这个 App 能干什么](#这个-app-能干什么)
- [技术栈](#技术栈)
- [快速开始（只想要个 apk）](#快速开始只想要个-apk)
- [后端：搭建并（可选）公网暴露 Navidrome](#后端搭建并可选公网暴露-navidrome)
- [App 里怎么配置](#app-里怎么配置)
- [心情分类是怎么工作的](#心情分类是怎么工作的)
- [从源码构建](#从源码构建)
- [项目结构](#项目结构)
- [已知限制 / 没做的事](#已知限制--没做的事)
- [踩过的坑](#踩过的坑)
- [License](#license)

---

## 这个 App 能干什么

- **双数据源**：网络模式连 Navidrome/Subsonic 服务器；没网或没配置服务器时，自动切换成读 U 盘（Storage Access Framework 授权一次即可）。
- **心情分类播放**：Home 四个色块——激情 / 平静 / 收藏 / 随机。激情/平静**不是**看歌曲文件自带的 genre 标签，而是手机本地一份独立的"标记文件"（title+artist 做 key），可以：
  - 用 PC 端脚本（`tools/`）批量跑一遍音频特征分析，导出后一次性导入；
  - 或者直接在 Library 长按某首歌手动改/移除分类，立刻生效，也能导出备份/换机导入。
  - 激情/平静播放时，已收藏的歌会优先排在前面。
- **播放队列**：Home/Library 顶部一个按钮直接打开，极简列表——每行歌名+收藏心+移除+拖动排序，"正在播放"置顶。手动"播放下一首"按你点击的顺序排队（不会后点的先播）。
- **顺序/随机播放**：正在播放页面的循环按钮在 顺序 / 单曲循环 / 随机 间切换,切换会立刻重排队列里还没播的部分,不用等放完一轮才生效。
- **断点续播**：下次打开 App 会在上次暂停的位置接着放，不是从头开始。
- **离线缓存**：流媒体音频经过一个可设置大小的本地缓存（LRU），放过一次的歌不用重新走网络；即将播放的接下来几首会提前后台预取。
- **耳机/蓝牙/锁屏**媒体键联动（上一首/播放暂停/下一首），走标准 MediaSession。
- **拼音排序 + 字母索引条**，Library 按标题拼音首字母分组，侧边可以直接跳字母。
- 深色/浅色/跟随系统主题切换。

## 技术栈

- Kotlin + Jetpack Compose（Material 3）
- [Media3 / ExoPlayer](https://developer.android.com/media/media3) 做实际播放，配合自建的 disk cache
- DataStore Preferences 做本地持久化（服务器配置、心情标记、播放队列快照）
- 手写的 Subsonic REST API 客户端（没有引入第三方 SDK）
- 没有用 Room/SQLite——心情标记和播放状态都是一份 JSON blob 存在 DataStore 里，量级不大，没必要上数据库

## 快速开始（只想要个 apk）

去 [Releases](../../releases) 页面下载最新的 `Music-vX.X-debug.apk`，手机上直接装（需要允许"安装未知来源应用"）。这是 **debug 签名**的包，不是要上架应用商店的正式签名，能装能跑，仅此而已。

装完打开 App，去 Settings 配置好 Navidrome 服务器信息，或者插上 U 盘（见下面两节）。

## 后端：搭建并（可选）公网暴露 Navidrome

这个 App 本身不提供音乐存储/转码，你需要一个 [Navidrome](https://www.navidrome.org/) 服务器（或任何兼容 Subsonic API 的服务端，比如 Airsonic），指向一个装了实际音乐文件的目录。**如果你只想用 U 盘模式，这一整节可以跳过**。

### 1. 用 Docker Compose 起一个 Navidrome

在你的 NAS/服务器/家里常开的电脑上：

```yaml
# docker-compose.yml
services:
  navidrome:
    image: deluan/navidrome:latest
    restart: unless-stopped
    ports:
      - "4533:4533"
    environment:
      ND_SCANSCHEDULE: 1h        # 每小时自动扫描一次库的变化
      ND_LOGLEVEL: info
      ND_SESSIONTIMEOUT: 24h
    volumes:
      - ./data:/data             # Navidrome 自己的数据库/配置
      - /path/to/your/music:/music:ro   # 换成你实际的音乐文件夹，只读挂载即可
```

```bash
docker compose up -d
```

访问 `http://<你的服务器内网IP>:4533`，第一次打开会让你建管理员账号——**用一个不是随便糊弄的密码**，这个账号将来是能读取（不能写）你全部音乐库的凭证。

### 2. 局域网内先跑通

手机和服务器在同一个 Wi-Fi 下，App 的 Settings 里填 `http://<内网IP>:4533`、账号、密码，能刷出歌单就说明这一步没问题。

### 3.（可选）公网访问——出门在外也能听

**这一步有真实的安全风险，跳过它、只在家用局域网/回家自动连 VPN，是完全合理的选择。** 如果你确实想要出门也能听，按安全性从高到低排几个思路：

- **组网类工具（推荐）**：[Tailscale](https://tailscale.com/) 或 [ZeroTier](https://www.zerotier.com/) 把手机和 NAS 拉进同一个虚拟局域网，手机上装个客户端，之后就跟连家里 Wi-Fi 一样访问 `http://<内网IP>:4533`，**完全不需要在路由器开任何端口**，服务器对公网依然不可见。个人用这个方案最省心。
- **隧道服务**：[Cloudflare Tunnel](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/) / [frp](https://github.com/fatedier/frp) 之类，由隧道服务商代理转发，同样不用在路由器上开洞，还能顺带拿到 HTTPS。
- **传统端口转发 + 反向代理**（风险最高，谨慎选择）：路由器把某个公网端口转发到 Navidrome 的 4533，前面挂一个 Nginx/Caddy 做 HTTPS + 反向代理，比如：

  ```
  # Caddy 示例（自动 HTTPS）
  music.your-domain.com {
      reverse_proxy localhost:4533
  }
  ```

  这样做至少要注意：
  - **必须走 HTTPS**，Subsonic 协议的 token 认证虽然不直接传明文密码，但没有 HTTPS 还是能被中间人拿到会话信息；
  - 换一个非默认端口，装 [fail2ban](https://www.fail2ban.org/) 挡暴力破解；
  - Navidrome 管理员密码单独设、别和其他账号复用；
  - 只开你真正需要暴露的那一个服务，别把整个 NAS 管理面板也顺手开出去。

## App 里怎么配置

- **Settings → 服务器地址/账号/密码**：按上面填好，保存后 Library/Home 会自动去拉库。
- **Settings → 音乐来源**：自动（有 U 盘就用 U 盘，没有就用网络）/ 强制网络 / 强制 U 盘。
- **Settings → 缓存**：开关 + 大小滑块，控制本地流媒体缓存上限。
- **Settings → 激情/平静标记 → 导出/导入**：见下一节。

## 心情分类是怎么工作的

激情/平静的判断，**唯一数据来源**是手机本地 DataStore 里一份 `{"歌名+歌手": "Energetic"/"Calm"}` 的 JSON——不是歌曲文件的 genre 标签，也不是 Navidrome 数据库里的什么字段。这样设计是因为：改标签需要连电脑，手机上临时想改一首歌的分类应该是几秒钟的事；而且一旦某首歌文件被替换/重命名，旧标记应该安全失效，而不是张冠李戴到新内容上。

批量打标签的流程（在你放音乐文件的那台电脑上跑，需要 `pip install librosa mutagen numpy`）：

```bash
# 1. 分析音频特征（较慢，会缓存到 CSV，方便反复调阈值）
python tools/classify_mood.py scan /path/to/music --cache features.csv

# 2. 先 dry-run 看看会打成什么样，觉得不对就调整 --energetic-pct/--calm-pct 再跑一次
python tools/classify_mood.py apply features.csv --dry-run
python tools/classify_mood.py apply features.csv

# 3. 把打好的 genre 标签转成 App 认的 JSON 格式
python tools/export_mood_labels.py /path/to/music --out mood_labels.json
```

然后把 `mood_labels.json` 传到手机上（随便什么方式——邮件、网盘、微信传文件都行），App 里 **Settings → 激情/平静标记 → 从文件导入**，选中它。

单首歌不满意分类结果？不用回电脑重跑——直接在 Library 长按那首歌，选"标记为「激情」/「平静」/移除分类"，立刻生效，也会计入下次导出。

`tools/` 目录下另外几个脚本是这个项目在处理"文件名里歌名和艺人顺序反了"这类历史遗留脏数据时写的一次性工具（`fix_title_artist_tags.py`、`rename_title_first.py`、`fix_from_kugou_reference.py`），如果你的音乐库标签本身就是干净的，用不上，可以忽略。

## 从源码构建

需要 JDK 17 和 Android SDK（`compileSdk 34` / `minSdk 26`）。

```bash
git clone https://github.com/EdgeN8v/music.git
cd music
```

Android Studio 打开项目根目录，等 Gradle 同步完直接 Run，或者命令行：

```bash
# Windows
.\gradlew.bat assembleDebug
# macOS/Linux
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/app-debug.apk`。

**没装过 Android SDK 的话**：最简单是直接装 [Android Studio](https://developer.android.com/studio)，第一次打开会引导你把 SDK 装好；`local.properties` 里的 `sdk.dir` 指向哪个路径都可以，这个文件本来就不进版本控制（见 `.gitignore`），每个人机器上都不一样很正常。

## 项目结构

```
app/src/main/java/com/example/music/
├── MainActivity.kt
├── data/               # SongRepository / SubsonicClient / SettingsRepository / LibraryCache …
├── playback/           # PlayerController（ExoPlayer 封装）/ AudioCache / PlaybackService（MediaSession）
├── ui/screens/         # HomeScreen / LibraryScreen / SettingsScreen
├── ui/components/      # MiniPlayerBar / NowPlayingContent / QueueSheet / SongSearchOverlay …
├── ui/navigation/      # AppNavigation（NavHost + 底部导航）
└── util/               # PinyinUtil（拼音排序/索引）

tools/                  # PC 端的心情分类 + 标签清理脚本（Python）
```

## 已知限制 / 没做的事

- 没有专辑封面（占位是一个按分类上色的圆盘）
- Subsonic 客户端只手写了这个 App 用到的那几个接口，不是完整实现
- 没有自动化测试
- debug 签名，不适合直接拿去应用商店

## 踩过的坑

调这个项目的过程中踩过几个不算小的坑，单独写在 [`docs/LESSONS_LEARNED.md`](docs/LESSONS_LEARNED.md) 里——包括一个隐藏控制字符让心情标记全库失效、排查一半才发现是自己的诊断工具本身有 bug、以及一次因为 CoroutineScope 生命周期没管好导致的静默数据丢失。如果你也在写类似的东西，也许能帮你少走点路。

## License

[MIT](LICENSE)
