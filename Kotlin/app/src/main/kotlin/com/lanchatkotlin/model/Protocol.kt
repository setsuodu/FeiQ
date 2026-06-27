package com.lanchatkotlin.model

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ───────────── 协议常量 ─────────────
object Protocol {
    const val UDP_PORT    = 52000
    const val TCP_PORT    = 52001

    const val MSG_ONLINE    = "ONLINE"
    const val MSG_OFFLINE   = "OFFLINE"
    const val MSG_TEXT      = "TEXT"
    const val MSG_IMAGE     = "IMAGE"
    const val MSG_FILE_REQ  = "FILE_REQ"
    const val MSG_HEARTBEAT = "HEARTBEAT"
}

val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

// ───────────── UDP 广播包 ─────────────
@Serializable
data class UdpPacket(
    val type: String,
    val fromId: String,
    val name: String,
    val avatar: String? = null,
    val data: String?   = null
) {
    fun serialize() = json.encodeToString(this)
    companion object {
        fun deserialize(s: String) = runCatching { json.decodeFromString<UdpPacket>(s) }.getOrNull()
    }
}

// ───────────── TCP 消息帧 ─────────────
@Serializable
data class TcpMessage(
    val type: String,
    val fromId: String,
    val fromName: String,
    val toId: String,
    val content: String = "",
    val fileName: String? = null,
    val fileSize: Long   = 0L,
    val msgId: String    = java.util.UUID.randomUUID().toString().replace("-",""),
    val time: Long       = System.currentTimeMillis()
) {
    fun serialize() = json.encodeToString(this)
    companion object {
        fun deserialize(s: String) = runCatching { json.decodeFromString<TcpMessage>(s) }.getOrNull()
    }
}

// ───────────── 对端用户 ─────────────
data class Peer(
    val id: String,
    val name: String,
    val ip: String,
    val avatar: String? = null,
    var lastSeen: Long  = System.currentTimeMillis()
) {
    val isOnline get() = System.currentTimeMillis() - lastSeen < 15_000
}

// ───────────── UI 聊天消息 ─────────────
enum class MsgType    { Text, Image, FileSent, FileReceived }
enum class MsgDirection { Sent, Received }

data class ChatMsg(
    val id: String         = java.util.UUID.randomUUID().toString(),
    val fromId: String,
    val fromName: String,
    val type: MsgType,
    val direction: MsgDirection,
    val content: String,           // 文字 / base64图片 / 本地URI
    val fileName: String?  = null,
    val fileSize: Long     = 0L,
    val time: Long         = System.currentTimeMillis()
)
