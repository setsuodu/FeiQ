package com.lanchatkotlin.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import coil.compose.AsyncImage
import com.lanchatkotlin.model.*
import com.lanchatkotlin.viewmodel.ChatViewModel
import java.text.SimpleDateFormat
import java.util.*

// ── 颜色 ──
private val PrimaryBlue    = Color(0xFF3F51B5)
private val SentBubble     = Color(0xFFBBDEFB)
private val ReceivedBubble = Color(0xFFFFFFFF)
private val BgGrey         = Color(0xFFF0F2F5)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(vm: ChatViewModel) {
    val peers       by vm.peers.collectAsState()
    val selectedPeer by vm.selectedPeer.collectAsState()

    if (selectedPeer == null) {
        PeerListScreen(peers = peers.values.toList(), onSelect = { vm.selectPeer(it) })
    } else {
        ChatScreen(vm = vm, peer = selectedPeer!!, onBack = { vm.selectPeer(selectedPeer!!) })
    }
}

// ────────────────────────────────────────────────────────────────
//  用户列表页
// ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PeerListScreen(peers: List<Peer>, onSelect: (Peer) -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("LanChat · 局域网飞聊", fontWeight = FontWeight.Bold) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White
                )
            )
        },
        containerColor = BgGrey
    ) { pad ->
        if (peers.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(pad), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("💬", fontSize = 48.sp)
                    Spacer(Modifier.height(12.dp))
                    Text("等待局域网用户上线…",
                         color = Color(0xFF9E9E9E), fontSize = 16.sp)
                }
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(pad)) {
                items(peers) { peer ->
                    PeerItem(peer = peer, onClick = { onSelect(peer) })
                    HorizontalDivider(color = Color(0xFFE0E0E0))
                }
            }
        }
    }
}

@Composable
fun PeerItem(peer: Peer, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 头像
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(PrimaryBlue),
            contentAlignment = Alignment.Center
        ) {
            Text(peer.name.take(1).uppercase(),
                 color = Color.White, fontWeight = FontWeight.Bold, fontSize = 20.sp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Text(peer.ip, color = Color(0xFF757575), fontSize = 12.sp)
        }
        // 在线状态点
        Box(
            modifier = Modifier.size(10.dp).clip(CircleShape)
                .background(if (peer.isOnline) Color(0xFF4CAF50) else Color(0xFFBDBDBD))
        )
    }
}

// ────────────────────────────────────────────────────────────────
//  聊天页
// ────────────────────────────────────────────────────────────────
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: ChatViewModel, peer: Peer, onBack: () -> Unit) {
    val messages  by vm.currentMessages.collectAsState()
    val status    by vm.statusText.collectAsState()
    var inputText by remember { mutableStateOf("") }
    val listState  = rememberLazyListState()

    // 自动滚底
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty())
            listState.animateScrollToItem(messages.size - 1)
    }

    // 图片选择器
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.sendImage(it) } }

    // 文件选择器
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? -> uri?.let { vm.sendFile(it) } }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peer.name, fontWeight = FontWeight.Bold)
                        Text(status, fontSize = 11.sp, color = Color(0xFFE3F2FD))
                    }
                },
                navigationIcon = {
                    IconButton(onClick = {
                        // 返回列表（重置 selected）
                        vm.selectPeer(Peer("", "", ""))
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回",
                             tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = PrimaryBlue,
                    titleContentColor = Color.White
                )
            )
        },
        bottomBar = {
            InputBar(
                text         = inputText,
                onTextChange = { inputText = it },
                onSend       = { if (inputText.isNotBlank()) { vm.sendText(inputText.trim()); inputText = "" } },
                onPickImage  = { imagePicker.launch("image/*") },
                onPickFile   = { filePicker.launch("*/*") }
            )
        },
        containerColor = BgGrey
    ) { pad ->
        LazyColumn(
            state         = listState,
            modifier      = Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(messages) { msg ->
                MessageBubble(msg)
            }
        }
    }
}

@Composable
fun InputBar(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onPickImage: () -> Unit,
    onPickFile: () -> Unit
) {
    Surface(shadowElevation = 8.dp, color = Color.White) {
        Column {
            // 工具栏
            Row(Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                IconButton(onClick = onPickImage) {
                    Icon(Icons.Default.Image, contentDescription = "发图片",
                         tint = PrimaryBlue)
                }
                IconButton(onClick = onPickFile) {
                    Icon(Icons.Default.AttachFile, contentDescription = "发文件",
                         tint = PrimaryBlue)
                }
            }
            // 输入行
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextField(
                    value = text, onValueChange = onTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("输入消息…", color = Color(0xFFBDBDBD)) },
                    maxLines = 4,
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor   = Color(0xFFF5F5F5),
                        unfocusedContainerColor = Color(0xFFF5F5F5),
                        focusedIndicatorColor   = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    ),
                    shape = RoundedCornerShape(24.dp)
                )
                Spacer(Modifier.width(8.dp))
                FloatingActionButton(
                    onClick        = onSend,
                    containerColor = PrimaryBlue,
                    modifier       = Modifier.size(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "发送",
                         tint = Color.White, modifier = Modifier.size(20.dp))
                }
            }
        }
    }
}

// ────────────────────────────────────────────────────────────────
//  消息气泡
// ────────────────────────────────────────────────────────────────
@Composable
fun MessageBubble(msg: ChatMsg) {
    val isSent   = msg.direction == MsgDirection.Sent
    val bgColor  = if (isSent) SentBubble else ReceivedBubble
    val align    = if (isSent) Arrangement.End else Arrangement.Start
    val timeFmt  = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }

    Row(
        modifier  = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = align
    ) {
        Column(horizontalAlignment = if (isSent) Alignment.End else Alignment.Start) {
            if (!isSent) {
                Text(msg.fromName, fontSize = 11.sp,
                     color = Color(0xFF9E9E9E), modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
            }

            Surface(
                shape  = RoundedCornerShape(
                    topStart     = if (isSent) 16.dp else 4.dp,
                    topEnd       = if (isSent) 4.dp  else 16.dp,
                    bottomStart  = 16.dp,
                    bottomEnd    = 16.dp
                ),
                color  = bgColor,
                shadowElevation = 2.dp
            ) {
                when (msg.type) {
                    MsgType.Text -> {
                        Column(Modifier.padding(12.dp, 8.dp)) {
                            Text(msg.content, fontSize = 15.sp, color = Color(0xFF212121))
                            Text(timeFmt.format(Date(msg.time)),
                                 fontSize = 10.sp, color = Color(0xFFBDBDBD),
                                 modifier  = Modifier.align(Alignment.End).padding(top = 4.dp))
                        }
                    }
                    MsgType.Image -> {
                        Column(Modifier.padding(4.dp)) {
                            AsyncImage(
                                model     = msg.content,
                                contentDescription = "图片",
                                modifier  = Modifier.sizeIn(maxWidth = 240.dp, maxHeight = 200.dp)
                                    .clip(RoundedCornerShape(12.dp)),
                                contentScale = ContentScale.Fit
                            )
                            Text(timeFmt.format(Date(msg.time)),
                                 fontSize = 10.sp, color = Color(0xFFBDBDBD),
                                 modifier  = Modifier.align(Alignment.End).padding(4.dp))
                        }
                    }
                    MsgType.FileSent, MsgType.FileReceived -> {
                        Row(
                            Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📄", fontSize = 32.sp, modifier = Modifier.padding(end = 10.dp))
                            Column {
                                Text(msg.fileName ?: "文件",
                                     fontWeight = FontWeight.SemiBold, fontSize = 14.sp,
                                     maxLines = 1, overflow = TextOverflow.Ellipsis,
                                     modifier = Modifier.widthIn(max = 180.dp))
                                Text(formatSize(msg.fileSize),
                                     fontSize = 11.sp, color = Color(0xFF757575))
                                Text(
                                    if (msg.type == MsgType.FileReceived) "保存至 下载/LanChat" else "已发送",
                                    fontSize = 10.sp,
                                    color    = if (msg.type == MsgType.FileReceived) Color(0xFF42A5F5)
                                               else Color(0xFF66BB6A)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatSize(size: Long): String = when {
    size < 1024            -> "$size B"
    size < 1024 * 1024     -> "${"%.1f".format(size / 1024.0)} KB"
    size < 1024L * 1024 * 1024 -> "${"%.1f".format(size / (1024.0 * 1024))} MB"
    else                   -> "${"%.2f".format(size / (1024.0 * 1024 * 1024))} GB"
}
