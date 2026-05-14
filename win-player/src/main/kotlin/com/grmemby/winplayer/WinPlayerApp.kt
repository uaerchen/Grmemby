package com.grmemby.winplayer

import com.grmemby.data.model.AuthenticationResult
import com.grmemby.data.model.BaseItemDto
import com.grmemby.data.model.PlaybackProgressRequest
import com.grmemby.data.model.PlaybackStartRequest
import com.grmemby.data.model.PlaybackStoppedRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.swing.Swing
import java.awt.BorderLayout
import java.awt.Component
import java.awt.GridBagConstraints
import java.awt.GridBagLayout
import java.awt.Insets
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.BorderFactory
import javax.swing.BoxLayout
import javax.swing.DefaultListCellRenderer
import javax.swing.DefaultListModel
import javax.swing.JButton
import javax.swing.JFrame
import javax.swing.JLabel
import javax.swing.JList
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JScrollPane
import javax.swing.JTabbedPane
import javax.swing.JTextArea
import javax.swing.JTextField
import javax.swing.ListSelectionModel
import javax.swing.SwingUtilities
import javax.swing.Timer
import javax.swing.UIManager
import javax.swing.WindowConstants
import kotlin.math.roundToLong

fun main() {
    SwingUtilities.invokeLater {
        runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
        WinPlayerFrame().isVisible = true
    }
}

private data class MediaListItem(val item: BaseItemDto, val group: String = "") {
    override fun toString(): String = buildString {
        if (group.isNotBlank()) append("[$group] ")
        append(item.displayName())
        item.productionYear?.let { append(" ($it)") }
        item.type?.let { append(" · $it") }
        val progress = item.userData?.playedPercentage
        if (progress != null) append(" · ${progress.roundToLong()}%")
    }
}

class WinPlayerFrame(
    private val configStore: WinPlayerConfigStore = WinPlayerConfigStore(),
    private val client: JellyfinDesktopClient = JellyfinDesktopClient(),
    private val roomClient: WatchPartyClient = WatchPartyClient(),
    private val mpvSession: MpvSession = MpvSession()
) : JFrame("Grmemby Win64") {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var auth: AuthenticationResult? = null
    private var serverName: String? = null
    private var config = configStore.load()
    private var currentItem: BaseItemDto? = null
    private var currentPlayback: ResolvedPlayback? = null
    private var activeRoom: RoomDto? = null
    private var memberId: String = config.deviceId
    private var lastAppliedRoomUpdateAt = 0L

    private val serverUrlField = JTextField(config.serverUrl, 32)
    private val usernameField = JTextField(config.username, 18)
    private val passwordField = JPasswordField(16)
    private val itemIdField = JTextField(config.itemId, 24)
    private val mpvPathField = JTextField(config.mpvPath, 24)
    private val bitrateField = JTextField(config.maxStreamingBitrate.toString(), 10)
    private val displayNameField = JTextField(config.displayName, 16)
    private val roomServerField = JTextField(config.roomServerUrl, 30)
    private val roomIdField = JTextField(8)
    private val roomChatField = JTextField(28)
    private val seekMsField = JTextField("0", 8)

    private val statusArea = JTextArea(8, 84).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val detailArea = JTextArea(12, 46).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }
    private val homeModel = DefaultListModel<MediaListItem>()
    private val searchModel = DefaultListModel<MediaListItem>()
    private val episodeModel = DefaultListModel<MediaListItem>()
    private val homeList = mediaList(homeModel)
    private val searchList = mediaList(searchModel)
    private val episodeList = mediaList(episodeModel)
    private val searchField = JTextField(28)
    private val roomStatusArea = JTextArea(9, 42).apply { isEditable = false; lineWrap = true; wrapStyleWord = true }

    private val pollTimer = Timer(4000) { pollRoomAndHeartbeat() }
    private val progressTimer = Timer(15_000) { reportProgress(false) }

    init {
        defaultCloseOperation = WindowConstants.DISPOSE_ON_CLOSE
        contentPane.layout = BorderLayout(8, 8)
        contentPane.add(buildTopPanel(), BorderLayout.NORTH)
        contentPane.add(buildTabs(), BorderLayout.CENTER)
        contentPane.add(JScrollPane(statusArea), BorderLayout.SOUTH)
        setSize(1080, 760)
        setLocationRelativeTo(null)
        log("Ready. Win64 客户端已包含登录、媒体库/继续观看/搜索/详情/剧集、mpv播放、播放上报、一起看房间/聊天/同步控制。DeviceId=${config.deviceId}")
    }

    private fun buildTopPanel(): JPanel = JPanel(GridBagLayout()).apply {
        border = BorderFactory.createTitledBorder("账号与播放设置")
        var row = 0
        addRow(row++, "Server URL", serverUrlField, 1)
        addRow(row++, "Username", usernameField, 1)
        addRow(row++, "Password", passwordField, 1)
        addRow(row++, "Item ID", itemIdField, 1)
        addRow(row++, "mpv.exe", mpvPathField, 1)
        addRow(row++, "Bitrate", bitrateField, 1)
        addRow(row++, "显示名", displayNameField, 1)
        addRow(row++, "Room Server", roomServerField, 1)
        val buttons = JPanel().apply {
            add(JButton("保存配置").also { it.addActionListener { saveConfigFromUi(); log("配置已保存。") } })
            add(JButton("登录").also { it.addActionListener { login() } })
            add(JButton("刷新首页").also { it.addActionListener { refreshHome() } })
            add(JButton("播放ItemId").also { it.addActionListener { playFromItemId(false, 0L) } })
            add(JButton("停止").also { it.addActionListener { stopPlayback(true) } })
        }
        add(buttons, GridBagConstraints().apply { gridx = 1; gridy = row; anchor = GridBagConstraints.WEST; insets = Insets(3,3,3,3) })
    }

    private fun JPanel.addRow(row: Int, label: String, field: JTextField, width: Int) {
        add(JLabel(label), GridBagConstraints().apply { gridx = 0; gridy = row; anchor = GridBagConstraints.EAST; insets = Insets(3,3,3,3) })
        add(field, GridBagConstraints().apply { gridx = 1; gridy = row; gridwidth = width; fill = GridBagConstraints.HORIZONTAL; weightx = 1.0; insets = Insets(3,3,3,3) })
    }

    private fun buildTabs(): JTabbedPane = JTabbedPane().apply {
        addTab("首页/媒体库", JPanel(BorderLayout(6, 6)).apply {
            add(JPanel().apply {
                add(JButton("最新/继续/下一集").also { it.addActionListener { refreshHome() } })
                add(JButton("全部电影剧集").also { it.addActionListener { loadAllMedia() } })
                add(JButton("用户媒体库").also { it.addActionListener { loadViews() } })
            }, BorderLayout.NORTH)
            add(JScrollPane(homeList), BorderLayout.CENTER)
        })
        addTab("搜索", JPanel(BorderLayout(6, 6)).apply {
            add(JPanel().apply {
                add(JLabel("关键词")); add(searchField)
                add(JButton("搜索").also { it.addActionListener { searchMedia() } })
            }, BorderLayout.NORTH)
            add(JScrollPane(searchList), BorderLayout.CENTER)
        })
        addTab("详情/剧集", JPanel(BorderLayout(6, 6)).apply {
            add(JScrollPane(detailArea), BorderLayout.WEST)
            add(JScrollPane(episodeList), BorderLayout.CENTER)
            add(JPanel().apply {
                layout = BoxLayout(this, BoxLayout.Y_AXIS)
                add(JButton("播放当前").also { it.addActionListener { playCurrent(false, 0L) } })
                add(JButton("加入房间并选择当前").also { it.addActionListener { selectCurrentForRoom() } })
                add(JButton("收藏/取消收藏").also { it.addActionListener { toggleFavorite() } })
                add(JButton("已看/未看").also { it.addActionListener { togglePlayed() } })
                add(JLabel("跳转ms")); add(seekMsField)
                add(JButton("本地跳转").also { it.addActionListener { localSeek() } })
            }, BorderLayout.EAST)
        })
        addTab("一起看", JPanel(BorderLayout(6, 6)).apply {
            add(JPanel().apply {
                add(JLabel("房间号")); add(roomIdField)
                add(JButton("创建房间").also { it.addActionListener { createRoom() } })
                add(JButton("加入房间").also { it.addActionListener { joinRoom() } })
                add(JButton("离开").also { it.addActionListener { leaveRoom(false) } })
                add(JButton("解散").also { it.addActionListener { leaveRoom(true) } })
            }, BorderLayout.NORTH)
            add(JPanel(BorderLayout()).apply {
                add(JScrollPane(roomStatusArea), BorderLayout.CENTER)
                add(JPanel().apply {
                    add(JButton("同步播放").also { it.addActionListener { roomPlayback(PlaybackEvent.PLAY, true) } })
                    add(JButton("同步暂停").also { it.addActionListener { roomPlayback(PlaybackEvent.PAUSE, false) } })
                    add(JButton("同步跳转").also { it.addActionListener { roomPlayback(PlaybackEvent.SEEK, false) } })
                    add(roomChatField)
                    add(JButton("发送聊天").also { it.addActionListener { sendChat() } })
                }, BorderLayout.SOUTH)
            }, BorderLayout.CENTER)
        })
    }

    private fun mediaList(model: DefaultListModel<MediaListItem>): JList<MediaListItem> = JList(model).apply {
        selectionMode = ListSelectionModel.SINGLE_SELECTION
        cellRenderer = object : DefaultListCellRenderer() {
            override fun getListCellRendererComponent(list: JList<*>?, value: Any?, index: Int, isSelected: Boolean, cellHasFocus: Boolean): Component {
                return super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus).also {
                    text = value?.toString().orEmpty()
                }
            }
        }
        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount >= 2) selectedValue?.item?.let { loadDetail(it) }
            }
        })
    }

    private fun login() {
        saveConfigFromUi()
        val password = String(passwordField.password)
        if (config.serverUrl.isBlank() || config.username.isBlank()) { log("Server URL / Username 不能为空。"); return }
        scope.launch {
            runCatching {
                serverName = runCatching { client.publicInfo(config.serverUrl).serverName }.getOrNull()
                client.authenticate(config.serverUrl, config.username, password, config.deviceId)
            }.onSuccess { result ->
                auth = result
                uiLog("登录成功：${result.user.name} (${result.user.id})；服务器=${serverName ?: result.serverId}")
                refreshHome()
            }.onFailure { error -> uiLog("登录失败：${error.message}") }
        }
    }

    private fun ensureAuth(block: (AuthenticationResult) -> Unit) {
        auth?.let(block) ?: run {
            log("未登录，先自动登录。")
            login()
        }
    }

    private fun refreshHome() = ensureAuth { result ->
        scope.launch {
            runCatching {
                val latest = client.latestItems(config.serverUrl, result.user.id, result.accessToken, config.deviceId).map { MediaListItem(it, "最新") }
                val resume = client.resumeItems(config.serverUrl, result.user.id, result.accessToken, config.deviceId).items.orEmpty().map { MediaListItem(it, "继续") }
                val next = client.nextUp(config.serverUrl, result.user.id, result.accessToken, config.deviceId).items.orEmpty().map { MediaListItem(it, "下一集") }
                latest + resume + next
            }.onSuccess { items ->
                uiList(homeModel, items)
                uiLog("首页已刷新：${items.size} 条。")
            }.onFailure { uiLog("刷新首页失败：${it.message}") }
        }
    }

    private fun loadAllMedia() = ensureAuth { result ->
        scope.launch {
            runCatching { client.userItems(config.serverUrl, result.user.id, result.accessToken, config.deviceId).items.orEmpty().map { MediaListItem(it, "全部") } }
                .onSuccess { uiList(homeModel, it); uiLog("媒体库加载完成：${it.size} 条。") }
                .onFailure { uiLog("媒体库加载失败：${it.message}") }
        }
    }

    private fun loadViews() = ensureAuth { result ->
        scope.launch {
            runCatching { client.userViews(config.serverUrl, result.user.id, result.accessToken, config.deviceId).items.orEmpty().map { MediaListItem(it, "媒体库") } }
                .onSuccess { uiList(homeModel, it); uiLog("用户媒体库加载完成。双击媒体库项可查看。") }
                .onFailure { uiLog("用户媒体库失败：${it.message}") }
        }
    }

    private fun searchMedia() = ensureAuth { result ->
        val term = searchField.text.trim()
        if (term.isBlank()) { log("搜索词不能为空。"); return@ensureAuth }
        scope.launch {
            runCatching { client.search(config.serverUrl, result.user.id, result.accessToken, config.deviceId, term).items.orEmpty().map { MediaListItem(it, "搜索") } }
                .onSuccess { uiList(searchModel, it); uiLog("搜索完成：${it.size} 条。") }
                .onFailure { uiLog("搜索失败：${it.message}") }
        }
    }

    private fun loadDetail(seed: BaseItemDto) = ensureAuth { result ->
        val id = seed.id ?: return@ensureAuth
        scope.launch {
            runCatching {
                val item = client.item(config.serverUrl, result.user.id, result.accessToken, config.deviceId, id)
                val children = when (item.type) {
                    "Series" -> client.episodes(config.serverUrl, result.user.id, result.accessToken, config.deviceId, item.id ?: id).items.orEmpty()
                    "Season" -> client.episodes(config.serverUrl, result.user.id, result.accessToken, config.deviceId, item.seriesId ?: item.parentId ?: id, item.id).items.orEmpty()
                    else -> emptyList()
                }
                item to children
            }.onSuccess { (item, children) ->
                currentItem = item
                itemIdField.text = item.id.orEmpty()
                uiDetail(item)
                uiList(episodeModel, children.map { MediaListItem(it, "剧集") })
                uiLog("详情已加载：${item.displayName()}；剧集=${children.size}")
            }.onFailure { uiLog("详情加载失败：${it.message}") }
        }
    }

    private fun playFromItemId(startPaused: Boolean, positionMs: Long) = ensureAuth { result ->
        val id = itemIdField.text.trim()
        if (id.isBlank()) { log("Item ID 不能为空。"); return@ensureAuth }
        scope.launch {
            runCatching { client.item(config.serverUrl, result.user.id, result.accessToken, config.deviceId, id) }
                .onSuccess { item -> currentItem = item; uiDetail(item); playCurrent(startPaused, positionMs) }
                .onFailure { uiLog("按ItemId加载失败：${it.message}") }
        }
    }

    private fun playCurrent(startPaused: Boolean, positionMs: Long) = ensureAuth { result ->
        val item = selectedMediaItem() ?: currentItem
        val itemId = item?.id ?: itemIdField.text.trim()
        if (itemId.isBlank()) { log("没有选中可播放项目。"); return@ensureAuth }
        scope.launch {
            runCatching {
                val info = client.playbackInfo(config.serverUrl, itemId, result.user.id, result.accessToken, config.deviceId, config.maxStreamingBitrate)
                val playback = PlaybackUrlResolver.resolve(config.serverUrl, itemId, result.accessToken, info)
                val spec = mpvSession.start(config.mpvPath, playback, startPaused, positionMs)
                currentPlayback = playback
                client.reportPlaybackStart(config.serverUrl, result.accessToken, config.deviceId, PlaybackStartRequest(itemId = itemId, playSessionId = playback.playSessionId, mediaSourceId = playback.mediaSourceId, isPaused = startPaused, positionTicks = positionMs.msToTicks()))
                spec
            }.onSuccess { spec ->
                progressTimer.start()
                uiLog("已启动 mpv：${currentItem?.displayName() ?: itemId}；IPC=${spec.ipcServer ?: "off"}" + if (spec.skippedHeaders.isEmpty()) "" else "；跳过header：${spec.skippedHeaders.joinToString()}")
            }.onFailure { uiLog("播放失败：${it.message}") }
        }
    }

    private fun stopPlayback(report: Boolean) {
        if (report) reportStopped()
        mpvSession.stop()
        progressTimer.stop()
        log("已停止播放。")
    }

    private fun reportProgress(paused: Boolean) = ensureAuth { result ->
        val itemId = currentItem?.id ?: itemIdField.text.trim()
        val playback = currentPlayback ?: return@ensureAuth
        if (itemId.isBlank()) return@ensureAuth
        scope.launch {
            runCatching {
                client.reportPlaybackProgress(config.serverUrl, result.accessToken, config.deviceId, PlaybackProgressRequest(itemId = itemId, playSessionId = playback.playSessionId, mediaSourceId = playback.mediaSourceId, isPaused = paused, positionTicks = mpvSession.currentPositionMs().msToTicks()))
            }
        }
    }

    private fun reportStopped() = ensureAuth { result ->
        val itemId = currentItem?.id ?: itemIdField.text.trim()
        val playback = currentPlayback ?: return@ensureAuth
        if (itemId.isBlank()) return@ensureAuth
        scope.launch {
            runCatching { client.reportPlaybackStopped(config.serverUrl, result.accessToken, config.deviceId, PlaybackStoppedRequest(itemId = itemId, playSessionId = playback.playSessionId, mediaSourceId = playback.mediaSourceId, positionTicks = mpvSession.currentPositionMs().msToTicks())) }
        }
    }

    private fun createRoom() = ensureAuth { result ->
        scope.launch {
            runCatching {
                roomClient.createRoom(config.roomServerUrl, CreateRoomRequest(name = "Grmemby ${config.displayName}", hostName = config.displayName, memberId = config.deviceId, serverUrl = config.serverUrl, serverName = serverName))
            }.onSuccess { created ->
                memberId = created.memberId
                activeRoom = created.room
                roomIdField.text = created.room.id
                pollTimer.start()
                uiRoom(created.room)
                uiLog("房间已创建：${created.room.id}；成员标识=${memberId}")
            }.onFailure { uiLog("创建房间失败：${it.message}") }
        }
    }

    private fun joinRoom() = ensureAuth {
        val rid = roomIdField.text.trim()
        if (rid.isBlank()) { log("房间号不能为空。"); return@ensureAuth }
        scope.launch {
            runCatching { roomClient.joinRoom(config.roomServerUrl, rid, JoinRoomRequest(config.displayName, config.deviceId, config.serverUrl, serverName)) }
                .onSuccess { joined ->
                    memberId = joined.memberId
                    activeRoom = joined.room
                    pollTimer.start()
                    uiRoom(joined.room)
                    uiLog("已加入房间：${joined.room.id}；稳定成员标识=${memberId}")
                    joined.room.media?.itemId?.let { itemIdField.text = it }
                }.onFailure { uiLog("加入房间失败：${it.message}") }
        }
    }

    private fun selectCurrentForRoom() {
        val room = activeRoom ?: run { log("先创建或加入房间。"); return }
        val item = selectedMediaItem() ?: currentItem ?: run { log("先选择媒体。"); return }
        val id = item.id ?: return
        scope.launch {
            runCatching { roomClient.selectMedia(config.roomServerUrl, room.id, SelectMediaRequest(memberId, id, item.displayName())) }
                .onSuccess { response -> response.room?.let { activeRoom = it; uiRoom(it) }; uiLog("已选择房间媒体：${item.displayName()}") }
                .onFailure { uiLog("选择房间媒体失败：${it.message}") }
        }
    }

    private fun roomPlayback(event: PlaybackEvent, isPlaying: Boolean) {
        val room = activeRoom ?: run { log("先创建或加入房间。"); return }
        val pos = if (event == PlaybackEvent.SEEK) seekMsField.text.trim().toLongOrNull() ?: mpvSession.currentPositionMs() else mpvSession.currentPositionMs()
        when (event) {
            PlaybackEvent.PLAY -> mpvSession.play()
            PlaybackEvent.PAUSE -> mpvSession.pause()
            PlaybackEvent.SEEK -> mpvSession.seek(pos)
            else -> Unit
        }
        scope.launch {
            runCatching { roomClient.updatePlayback(config.roomServerUrl, room.id, PlaybackUpdateRequest(memberId, currentItem?.id ?: itemIdField.text.trim(), event, pos, isPlaying)) }
                .onSuccess { activeRoom = it; uiRoom(it); uiLog("已发送同步：$event @ ${pos}ms") }
                .onFailure { uiLog("发送同步失败：${it.message}") }
        }
    }

    private fun pollRoomAndHeartbeat() {
        val room = activeRoom ?: return
        scope.launch {
            runCatching {
                roomClient.heartbeat(config.roomServerUrl, room.id, memberId)
                roomClient.getRoom(config.roomServerUrl, room.id)
            }.onSuccess { latest ->
                activeRoom = latest
                uiRoom(latest)
                applyRemotePlayback(latest)
            }.onFailure { uiLog("房间保活/轮询失败：${it.message}") }
        }
    }

    private fun applyRemotePlayback(room: RoomDto) {
        val playback = room.playback
        if (playback.updatedBy == null || playback.updatedBy == memberId || playback.updatedAt <= lastAppliedRoomUpdateAt) return
        lastAppliedRoomUpdateAt = playback.updatedAt
        val mediaId = room.media?.itemId ?: playback.updatedBy?.let { currentItem?.id }
        if (!mediaId.isNullOrBlank() && mediaId != (currentItem?.id ?: itemIdField.text.trim())) {
            uiLog("房间媒体变更，准备播放：$mediaId")
            itemIdField.text = mediaId
            playFromItemId(startPaused = !playback.isPlaying, positionMs = playback.positionMs)
            return
        }
        when (playback.event) {
            PlaybackEvent.PLAY -> { mpvSession.seek(playback.positionMs); mpvSession.play(); uiLog("已应用远端播放 @ ${playback.positionMs}ms") }
            PlaybackEvent.PAUSE -> { mpvSession.seek(playback.positionMs); mpvSession.pause(); uiLog("已应用远端暂停 @ ${playback.positionMs}ms") }
            PlaybackEvent.SEEK -> { mpvSession.seek(playback.positionMs); if (playback.isPlaying) mpvSession.play() else mpvSession.pause(); uiLog("已应用远端跳转 @ ${playback.positionMs}ms") }
            else -> Unit
        }
    }

    private fun sendChat() {
        val room = activeRoom ?: run { log("先创建或加入房间。"); return }
        val content = roomChatField.text.trim()
        if (content.isBlank()) return
        scope.launch {
            runCatching { roomClient.sendChat(config.roomServerUrl, room.id, SendChatMessageRequest(memberId, content)) }
                .onSuccess { activeRoom = it; roomChatField.text = ""; uiRoom(it) }
                .onFailure { uiLog("发送聊天失败：${it.message}") }
        }
    }

    private fun leaveRoom(disband: Boolean) {
        val room = activeRoom ?: return
        scope.launch {
            runCatching { if (disband) roomClient.disbandRoom(config.roomServerUrl, room.id, memberId) else roomClient.leaveRoom(config.roomServerUrl, room.id, memberId) }
                .onSuccess { pollTimer.stop(); activeRoom = null; uiLog(if (disband) "已请求解散房间。" else "已离开房间。") }
                .onFailure { uiLog("离开/解散失败：${it.message}") }
        }
    }

    private fun toggleFavorite() = ensureAuth { result ->
        val item = currentItem ?: return@ensureAuth
        val id = item.id ?: return@ensureAuth
        val target = item.userData?.isFavorite != true
        scope.launch { runCatching { client.markFavorite(config.serverUrl, result.user.id, result.accessToken, config.deviceId, id, target) }.onSuccess { uiLog(if (target) "已收藏。" else "已取消收藏。") }.onFailure { uiLog("收藏操作失败：${it.message}") } }
    }

    private fun togglePlayed() = ensureAuth { result ->
        val item = currentItem ?: return@ensureAuth
        val id = item.id ?: return@ensureAuth
        val target = item.userData?.played != true
        scope.launch { runCatching { client.markPlayed(config.serverUrl, result.user.id, result.accessToken, config.deviceId, id, target) }.onSuccess { uiLog(if (target) "已标记已看。" else "已标记未看。") }.onFailure { uiLog("已看操作失败：${it.message}") } }
    }

    private fun localSeek() {
        val pos = seekMsField.text.trim().toLongOrNull() ?: 0L
        mpvSession.seek(pos)
        log("本地跳转到 ${pos}ms。")
    }

    private fun selectedMediaItem(): BaseItemDto? = homeList.selectedValue?.item ?: searchList.selectedValue?.item ?: episodeList.selectedValue?.item

    private fun saveConfigFromUi() {
        config = WinPlayerConfig(
            serverUrl = serverUrlField.text.trim().trimEnd('/'),
            username = usernameField.text.trim(),
            itemId = itemIdField.text.trim(),
            mpvPath = mpvPathField.text.trim().ifBlank { "mpv.exe" },
            roomServerUrl = roomServerField.text.trim().trimEnd('/'),
            displayName = displayNameField.text.trim().ifBlank { System.getProperty("user.name", "Windows用户") },
            maxStreamingBitrate = bitrateField.text.trim().toIntOrNull() ?: 120_000_000,
            deviceId = config.deviceId
        )
        configStore.save(config)
        memberId = config.deviceId
    }

    private fun uiList(model: DefaultListModel<MediaListItem>, items: List<MediaListItem>) {
        CoroutineScope(Dispatchers.Swing).launch {
            model.clear(); items.forEach(model::addElement)
        }
    }

    private fun uiDetail(item: BaseItemDto) = CoroutineScope(Dispatchers.Swing).launch {
        detailArea.text = buildString {
            appendLine(item.displayName())
            appendLine("ID: ${item.id.orEmpty()}")
            appendLine("类型: ${item.type.orEmpty()}  年份: ${item.productionYear ?: ""}  评分: ${item.communityRating ?: ""}")
            item.seriesName?.let { appendLine("剧集: $it S${item.parentIndexNumber ?: ""}E${item.indexNumber ?: ""}") }
            appendLine("时长: ${item.runTimeTicks?.ticksToMinutes() ?: "?"}分钟")
            appendLine("收藏: ${item.userData?.isFavorite == true}  已看: ${item.userData?.played == true}  进度: ${item.userData?.playedPercentage ?: 0.0}%")
            appendLine("类型标签: ${item.genres.orEmpty().joinToString()}")
            appendLine()
            appendLine(item.overview.orEmpty())
        }
    }

    private fun uiRoom(room: RoomDto) = CoroutineScope(Dispatchers.Swing).launch {
        roomStatusArea.text = buildString {
            appendLine("房间 ${room.id} · ${room.name}")
            appendLine("在线/成员：${room.members.size}  房主：${room.hostName} (${room.hostMemberId})")
            appendLine("服务器：${room.serverName ?: room.serverUrl.orEmpty()}")
            appendLine("媒体：${room.media?.title ?: room.media?.itemId ?: "未选择"}")
            appendLine("播放：${room.playback.event} ${room.playback.positionMs}ms playing=${room.playback.isPlaying} by=${room.playback.updatedBy}")
            appendLine("成员：")
            room.members.forEach { appendLine(" - ${it.name} ${if (it.isHost) "[房主]" else ""} id=${it.id}") }
            appendLine("聊天：")
            room.chatMessages.takeLast(8).forEach { appendLine("${it.senderName}: ${it.content}") }
        }
    }

    private fun log(message: String) {
        statusArea.append(message + "\n")
        statusArea.caretPosition = statusArea.document.length
    }

    private fun uiLog(message: String) = CoroutineScope(Dispatchers.Swing).launch { log(message) }

    override fun dispose() {
        runCatching { if (activeRoom != null) leaveRoom(false) }
        pollTimer.stop()
        progressTimer.stop()
        scope.cancel()
        client.close()
        roomClient.close()
        mpvSession.stop()
        super.dispose()
    }
}

private fun BaseItemDto.displayName(): String = name ?: originalTitle ?: seriesName ?: id ?: "Untitled"
private fun Long.msToTicks(): Long = this * 10_000L
private fun Long.ticksToMinutes(): Long = this / 10_000L / 1000L / 60L
