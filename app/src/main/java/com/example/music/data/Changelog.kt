package com.example.music.data

data class ChangelogEntry(val version: String, val notes: List<String>)

/**
 * Manually maintained, newest first — bump [ChangelogEntry.version] to match
 * app/build.gradle.kts versionName every time a build goes out, and add one
 * entry summarizing what changed. Keep each note to a few words; this is
 * meant to be skimmed in a small dialog, not read as a commit log.
 */
object Changelog {
    val entries = listOf(
        ChangelogEntry(
            version = "0.15",
            notes = listOf(
                "修复：网络不稳定时歌曲卡住暂停、点播放没反应——现在播放出错会自动重试，播放键也会先重新准备再播放",
                "加强系统音频焦点处理，尝试修复被别的 App 抢占音频后耳机/通知栏控制失灵的问题",
                "新增：Home 顶部可以关闭\"激情/平静优先播放收藏\"，不想总听那几首可以关掉",
                "新增：播放队列长按一首歌可以直接置顶，不用从队列底部慢慢拖上来",
                "队列页面加高，一次能看到更多歌",
                "取消点击 MiniPlayer 弹出大卡片——顺序/单曲循环/随机的切换按钮直接挪到了 MiniPlayer 最左边，一键切换",
                "重新设计了搜索/定位后的高亮效果，更明显、颜色更好看"
            )
        ),
        ChangelogEntry(
            version = "0.14",
            notes = listOf(
                "修复：激情/平静标记的匹配 key 里混进了一个肉眼不可见的隐藏字符，导致导入的标记文件永远匹配不上——这是从 0.11 就存在的老 bug",
                "修复：切换顺序/随机播放模式，之前对已经在队列里的歌完全不起作用，现在会立刻重排剩余队列",
                "新增：断点续播——下次打开 App 会在原来暂停的位置续播，不是从头开始",
                "新增：Home/Library 顶部可以直接打开播放队列，极简样式，可以拖动排序、收藏、移除",
                "新增：手动点「播放下一首」按点击顺序排队，不再是后点的先播",
                "新增：双击 Library 图标定位到当前播放歌曲；搜索/定位后目标行会闪烁提示",
                "激情/平静播放优先播放已收藏的歌"
            )
        ),
        ChangelogEntry(
            version = "0.13",
            notes = listOf(
                "激情/平静彻底改用手机上的独立标记文件，不再看歌曲文件本身的 genre 标签——长按标记的、以及 tools/export_mood_labels.py 批量导出的，现在是同一份数据",
                "标记按「歌名+艺人」匹配，不依赖文件路径或服务器 ID：以后歌曲真的换了内容/名字，会安全地变回「未分类」，不会张冠李戴"
            )
        ),
        ChangelogEntry(
            version = "0.12",
            notes = listOf(
                "修复：没打艺人标签的歌，Library 显示 [Unknown Artist] 太难看——现在统一显示成空白（跟原来一样）",
                "新增：Library 每首歌前面加了个小圆点，红色=激情、蓝色=平静、空心=没分类，一眼看出每首歌当前状态，不用长按一个个查",
                "重新整理了全部歌曲的歌名/艺人标签（按文件名拆分歌名-歌手），换掉之前干脆不写标题导致显示乱的问题"
            )
        ),
        ChangelogEntry(
            version = "0.11",
            notes = listOf(
                "新增：Library 长按一首歌可以手动标记「激情」「平静」或移除分类，不用再去电脑上改文件标签",
                "新增：Settings 里可以导出/导入这些手动标记，换手机也不用重新标一遍",
                "网络模式下重进 App，Library 秒开显示上次的列表，同时后台悄悄刷新，不用每次都等加载完",
                "Home 的激情/平静/收藏/随机改成读本地已加载的库来筛，而不是每次现查服务器——手动标记能立刻生效，随机也更快"
            )
        ),
        ChangelogEntry(
            version = "0.10",
            notes = listOf(
                "修复：0.9 那个修复没修干净——插上 U 盘那一刻扫描到的还是空的（U 盘系统层面刚挂载完，文件系统还没稳下来），现在扫到空会自动隔 1 秒重试几次，不用再手动刷新或点随机才出歌"
            )
        ),
        ChangelogEntry(
            version = "0.9",
            notes = listOf(
                "修复：插上 U 盘后 Library 显示\"库是空的\"，要手动刷新才出歌（U 盘变为可用时，原来是等界面自己发现变化再去加载，这个时机能错过；现在 U 盘一变可用就直接主动加载，不再等界面）"
            )
        ),
        ChangelogEntry(
            version = "0.8",
            notes = listOf(
                "修复：Library 彻底不显示歌曲（网络加载在被中途取消时，没有重置\"加载中\"标记，导致这个标记卡死为true，Library 以后永远不会再去加载——网络、U盘用的是同一个标记，所以两边都遭殃；Home 能放歌是因为它走的是另一条不经过这个标记的路径）"
            )
        ),
        ChangelogEntry(
            version = "0.7",
            notes = listOf(
                "修复：Library 卡死不显示歌曲（搜索定位的闪烁动画在每一行都起了一个动画循环，歌多了直接卡死）",
                "移除悬浮胶囊功能（不稳定，先去掉）",
                "保留：播放中高亮边框浅色模式下的暖黄色"
            )
        ),
        ChangelogEntry(
            version = "0.5",
            notes = listOf(
                "新增：最小化播放时顶部悬浮小胶囊（点击回到播放页），需在 Settings 手动开启一次权限",
                "新增：播放页封面换成会转的唱片，加了顺序/单曲循环/随机切换（循环到底不再停止）",
                "修复：部分多音字拼音排序错误（如“重生”）",
                "搜索选中歌曲改为定位，不再直接播放",
                "移除 Library 加载中提示",
                "Home 播放中高亮浅色模式下更醒目"
            )
        ),
        ChangelogEntry(
            version = "0.4",
            notes = listOf(
                "修复：U 盘授权崩溃（真实找到的 bug，已用日志验证）",
                "修复：切屏时偶发的\"加载失败\"误报",
                "修复：耳机/蓝牙的下一首按键无反应",
                "新增：点击迷你播放条弹出完整播放页（封面/进度/收藏/上下首）",
                "Settings 更精简：说明文字收进 (i) 图标，版本号移到右上角",
                "Theme 顺序改为 系统/浅色/深色",
                "Library 加载态换成更轻的文字提示"
            )
        ),
        ChangelogEntry(
            version = "0.3",
            notes = listOf(
                "修复：授权 U 盘后闪退",
                "耳机/蓝牙媒体键联动（播放/暂停/上下首）",
                "下一首播放显示队列位置",
                "提前预加载接下来几首歌，不再临播放才加载",
                "缓存新增“清理随机歌曲”按钮",
                "Genre 标签改用 Energetic/Calm",
                "Settings 进入即为收起状态，不再有多余的展开动画",
                "版本号改为每次构建递增，新增更新日志"
            )
        ),
        ChangelogEntry(
            version = "0.2",
            notes = listOf(
                "修复 Library/Home 切换卡顿（拼音排序性能问题）",
                "修复离线缓存不生效的 bug",
                "播放进度条可拖动快进快退",
                "进入 Library 自动定位到当前播放歌曲（居中显示）",
                "Home 播放来源（激情/平静/收藏/随机）高亮 + 律动动画",
                "新增 U 盘本地音乐模式（自动识别/切换）",
                "Settings 新增缓存大小滑块、音乐来源选择"
            )
        ),
        ChangelogEntry(
            version = "0.1",
            notes = listOf("初始版本")
        )
    )
}
