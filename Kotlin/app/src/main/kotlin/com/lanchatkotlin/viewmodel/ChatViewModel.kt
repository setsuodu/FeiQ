package com.lanchatkotlin.viewmodel

import android.app.Application
import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.lanchatkotlin.model.*
import com.lanchatkotlin.service.NetworkService
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.io.File

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    val net = NetworkService(app, android.os.Build.MODEL)

    // ── UI 状态 ──
    private val _selectedPeer = MutableStateFlow<Peer?>(null)
    val selectedPeer: StateFlow<Peer?> = _selectedPeer.asStateFlow()

    // 所有历史：peerId → messages
    private val _allHistory = MutableStateFlow<Map<String, List<ChatMsg>>>(emptyMap())

    val currentMessages: StateFlow<List<ChatMsg>> = combine(_selectedPeer, _allHistory) { peer, hist ->
        peer?.let { hist[it.id] } ?: emptyList()
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val peers = net.peers

    private val _statusText = MutableStateFlow("已连接")
    val statusText: StateFlow<String> = _statusText.asStateFlow()

    init {
        net.start()

        // 监听收到的消息
        viewModelScope.launch {
            net.messages.collect { tcpMsg ->
                val cm = ChatMsg(
                    fromId    = tcpMsg.fromId,
                    fromName  = tcpMsg.fromName,
                    direction = MsgDirection.Received,
                    type      = when (tcpMsg.type) {
                        Protocol.MSG_IMAGE    -> MsgType.Image
                        Protocol.MSG_FILE_REQ -> MsgType.FileReceived
                        else                  -> MsgType.Text
                    },
                    content   = tcpMsg.content,
                    fileName  = tcpMsg.fileName,
                    fileSize  = tcpMsg.fileSize
                )
                appendHistory(tcpMsg.fromId, cm)
            }
        }

        // 传输进度
        viewModelScope.launch {
            net.progress.collect { (msgId, recv, total) ->
                _statusText.value = "接收中 ${recv * 100 / total}%"
                if (recv == total) _statusText.value = "接收完成"
            }
        }
    }

    fun selectPeer(peer: Peer) { _selectedPeer.value = peer }

    fun sendText(text: String) {
        val peer = _selectedPeer.value ?: return
        viewModelScope.launch {
            val cm = ChatMsg(fromId = net.localId, fromName = net.localName,
                             direction = MsgDirection.Sent, type = MsgType.Text, content = text)
            appendHistory(peer.id, cm)
            runCatching { net.sendText(peer, text) }
                .onFailure { _statusText.value = "发送失败: ${it.message}" }
        }
    }

    fun sendImage(uri: Uri) {
        val peer = _selectedPeer.value ?: return
        viewModelScope.launch {
            val cr    = getApplication<Application>().contentResolver
            val bytes = cr.openInputStream(uri)!!.readBytes()
            val name  = getFileName(cr, uri) ?: "image.jpg"

            val cm = ChatMsg(fromId = net.localId, fromName = net.localName,
                             direction = MsgDirection.Sent, type = MsgType.Image,
                             content = uri.toString(), fileName = name)
            appendHistory(peer.id, cm)
            runCatching { net.sendImage(peer, bytes, name) }
                .onFailure { _statusText.value = "图片发送失败: ${it.message}" }
        }
    }

    fun sendFile(uri: Uri) {
        val peer = _selectedPeer.value ?: return
        viewModelScope.launch {
            val cr   = getApplication<Application>().contentResolver
            val name = getFileName(cr, uri) ?: "file"
            // 先复制到缓存目录再发送
            val tmp  = File(getApplication<Application>().cacheDir, name)
            cr.openInputStream(uri)!!.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }

            val cm = ChatMsg(fromId = net.localId, fromName = net.localName,
                             direction = MsgDirection.Sent, type = MsgType.FileSent,
                             content = uri.toString(), fileName = name,
                             fileSize = tmp.length())
            appendHistory(peer.id, cm)

            runCatching {
                net.sendFile(peer, tmp) { sent, total ->
                    _statusText.value = "发送 $name: ${sent * 100 / total}%"
                }
                _statusText.value = "$name 发送完成"
            }.onFailure { _statusText.value = "文件发送失败: ${it.message}" }
        }
    }

    private fun appendHistory(peerId: String, msg: ChatMsg) {
        val cur = _allHistory.value.toMutableMap()
        cur[peerId] = (cur[peerId] ?: emptyList()) + msg
        _allHistory.value = cur
    }

    private fun getFileName(cr: ContentResolver, uri: Uri): String? {
        cr.query(uri, null, null, null, null)?.use { cursor ->
            val idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && idx >= 0) return cursor.getString(idx)
        }
        return uri.lastPathSegment
    }

    override fun onCleared() {
        super.onCleared()
        net.destroy()
    }
}
