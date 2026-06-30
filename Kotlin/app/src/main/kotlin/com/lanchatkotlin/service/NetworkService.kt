package com.lanchatkotlin.service

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.lanchatkotlin.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.*
import java.net.*
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "NetworkService"

class NetworkService(
    context: Context,
    val localName: String,
    val localId: String = java.util.UUID.randomUUID().toString().take(8)
) {
    // ── 对外流 ──
    private val _peers    = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _messages = MutableSharedFlow<TcpMessage>(replay = 0, extraBufferCapacity = 64)
    val messages: SharedFlow<TcpMessage> = _messages.asSharedFlow()

    private val _progress = MutableSharedFlow<Triple<String,Long,Long>>(extraBufferCapacity = 32)
    val progress: SharedFlow<Triple<String,Long,Long>> = _progress.asSharedFlow()

    val localIP: String

    // ── 内部 ──
    private val scope  = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var udpSocket: DatagramSocket? = null
    private var tcpServer: ServerSocket?   = null
    private val wifiLock: WifiManager.MulticastLock

    init {
        val wm = context.applicationContext
            .getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifiLock = wm.createMulticastLock("LanChat").also { it.acquire() }
        localIP  = getLocalIP(context)
    }

    // ────────────────────────────────────────────────────────────────
    //  启动
    // ────────────────────────────────────────────────────────────────
    fun start() {
        startUdpListener()
        startTcpServer()
        scope.launch { broadcastLoop() }
        scope.launch { broadcastOnline() }
    }

    // ────────────────────────────────────────────────────────────────
    //  UDP
    // ────────────────────────────────────────────────────────────────
    private fun startUdpListener() {
        scope.launch {
            udpSocket = DatagramSocket(null).apply {
                reuseAddress = true
                bind(InetSocketAddress(Protocol.UDP_PORT))
                broadcast = true
            }
            val buf = ByteArray(4096)
            while (isActive) {
                try {
                    val pkt = DatagramPacket(buf, buf.size)
                    udpSocket!!.receive(pkt)
                    val json   = String(pkt.data, 0, pkt.length)
                    val packet = UdpPacket.deserialize(json) ?: continue
                    if (packet.fromId == localId) continue
                    val ip   = pkt.address.hostAddress ?: continue
                    onUdpPacket(packet, ip)
                } catch (e: Exception) {
                    if (!isActive) break
                    Log.w(TAG, "UDP recv err: ${e.message}")
                }
            }
        }
    }

    private fun onUdpPacket(packet: UdpPacket, ip: String) {
        if (packet.type == Protocol.MSG_OFFLINE) {
            _peers.value = _peers.value - packet.fromId
            return
        }
        val peer = Peer(id = packet.fromId, name = packet.name, ip = ip,
                        avatar = packet.avatar, lastSeen = System.currentTimeMillis())
        _peers.value = _peers.value + (peer.id to peer)
    }

    private suspend fun broadcastLoop() {
        val sender = DatagramSocket().apply { broadcast = true }
        while (currentCoroutineContext().isActive) {
            sendBroadcast(sender, Protocol.MSG_HEARTBEAT)
            delay(5_000)
        }
        sender.close()
    }

    suspend fun broadcastOnline() {
        val sender = DatagramSocket().apply { broadcast = true }
        sendBroadcast(sender, Protocol.MSG_ONLINE)
        sender.close()
    }

    suspend fun broadcastOffline() {
        val sender = DatagramSocket().apply { broadcast = true }
        sendBroadcast(sender, Protocol.MSG_OFFLINE)
        sender.close()
    }

    private fun sendBroadcast(sender: DatagramSocket, type: String) {
        val pkt  = UdpPacket(type = type, fromId = localId, name = localName)
        val data = pkt.serialize().toByteArray()
        val dp   = DatagramPacket(data, data.size,
                                  InetAddress.getByName("255.255.255.255"),
                                  Protocol.UDP_PORT)
        runCatching { sender.send(dp) }
    }

    // ────────────────────────────────────────────────────────────────
    //  TCP 服务端
    // ────────────────────────────────────────────────────────────────
    private fun startTcpServer() {
        scope.launch {
            tcpServer = ServerSocket(Protocol.TCP_PORT)
            while (isActive) {
                try {
                    val client = tcpServer!!.accept()
                    launch { handleClient(client) }
                } catch (e: Exception) {
                    if (!isActive) break
                }
            }
        }
    }

    private suspend fun handleClient(socket: Socket) = withContext(Dispatchers.IO) {
        socket.use {
            val stream = it.getInputStream()
            // 读4字节长度头
            val header = ByteArray(4)
            readExact(stream, header, 4)
            val msgLen = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
            val msgBuf = ByteArray(msgLen)
            readExact(stream, msgBuf, msgLen)
            val msg = TcpMessage.deserialize(String(msgBuf)) ?: return@withContext

            if (msg.type == Protocol.MSG_FILE_REQ && msg.fileSize > 0) {
                val file = receiveFile(stream, msg.fileName ?: "file", msg.fileSize, msg.msgId)
                _messages.emit(msg.copy(content = file.absolutePath))
            } else {
                _messages.emit(msg)
            }
        }
    }

    private suspend fun receiveFile(stream: InputStream, name: String,
                                    size: Long, msgId: String): File {
        val dir  = File("/sdcard/Download/LanChat").also { it.mkdirs() }
        val file = File(dir, name)
        val fos  = FileOutputStream(file)
        val buf  = ByteArray(65536)
        var recv = 0L
        while (recv < size) {
            val toRead = minOf(buf.size.toLong(), size - recv).toInt()
            val n      = stream.read(buf, 0, toRead)
            if (n < 0) break
            fos.write(buf, 0, n)
            recv += n
            _progress.emit(Triple(msgId, recv, size))
        }
        fos.close()
        return file
    }

    // ────────────────────────────────────────────────────────────────
    //  TCP 发送端
    // ────────────────────────────────────────────────────────────────
    suspend fun sendText(peer: Peer, text: String) {
        val msg = TcpMessage(type = Protocol.MSG_TEXT, fromId = localId,
                             fromName = localName, toId = peer.id, content = text)
        sendFrame(peer.ip, msg)
    }

    suspend fun sendImage(peer: Peer, bytes: ByteArray, name: String) {
        val b64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val msg = TcpMessage(type = Protocol.MSG_IMAGE, fromId = localId,
                             fromName = localName, toId = peer.id,
                             content = b64, fileName = name)
        sendFrame(peer.ip, msg)
    }

    suspend fun sendFile(peer: Peer, file: File,
                         onProgress: ((Long, Long) -> Unit)? = null) {
        val msg = TcpMessage(type = Protocol.MSG_FILE_REQ, fromId = localId,
                             fromName = localName, toId = peer.id,
                             fileName = file.name, fileSize = file.length())
        val json  = msg.serialize().toByteArray()
        val hdr   = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.size).array()

        Socket(peer.ip, Protocol.TCP_PORT).use { sock ->
            val out = sock.getOutputStream()
            out.write(hdr)
            out.write(json)
            val fis = FileInputStream(file)
            val buf = ByteArray(65536)
            var sent = 0L
            var n: Int
            while (fis.read(buf).also { n = it } > 0) {
                out.write(buf, 0, n)
                sent += n
                onProgress?.invoke(sent, file.length())
            }
            fis.close()
        }
    }

    private suspend fun sendFrame(ip: String, msg: TcpMessage) = withContext(Dispatchers.IO) {
        val json = msg.serialize().toByteArray()
        val hdr  = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(json.size).array()
        Socket(ip, Protocol.TCP_PORT).use { sock ->
            val out = sock.getOutputStream()
            out.write(hdr); out.write(json)
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  工具
    // ────────────────────────────────────────────────────────────────
    private fun readExact(stream: InputStream, buf: ByteArray, count: Int) {
        var offset = 0
        while (offset < count) {
            val n = stream.read(buf, offset, count - offset)
            if (n < 0) throw IOException("Connection closed")
            offset += n
        }
    }

    private fun getLocalIP(ctx: Context): String {
        val wm = ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val ip = wm.connectionInfo.ipAddress
        return if (ip == 0) "127.0.0.1"
        else String.format("%d.%d.%d.%d",
            ip and 0xff, ip shr 8 and 0xff, ip shr 16 and 0xff, ip shr 24 and 0xff)
    }

    fun destroy() {
        scope.launch { broadcastOffline() }
        scope.cancel()
        udpSocket?.close()
        tcpServer?.close()
        wifiLock.release()
    }
}
