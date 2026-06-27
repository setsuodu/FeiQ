using System.Text.Json;
using System.Text.Json.Serialization;

namespace LanChat.WPF.Models;

// ───────────── 协议常量 ─────────────
public static class Protocol
{
    public const int UDP_PORT = 52000;   // 广播/发现
    public const int TCP_PORT = 52001;   // 消息 & 文件传输

    // 消息类型
    public const string MSG_ONLINE    = "ONLINE";
    public const string MSG_OFFLINE   = "OFFLINE";
    public const string MSG_TEXT      = "TEXT";
    public const string MSG_IMAGE     = "IMAGE";
    public const string MSG_FILE_REQ  = "FILE_REQ";   // 发送方通知「我要发文件」
    public const string MSG_FILE_ACK  = "FILE_ACK";   // 接收方回复「可以」
    public const string MSG_HEARTBEAT = "HEARTBEAT";
}

// ───────────── UDP 广播包 ─────────────
public class UdpPacket
{
    [JsonPropertyName("type")]   public string Type     { get; set; } = "";
    [JsonPropertyName("fromId")] public string FromId   { get; set; } = "";
    [JsonPropertyName("name")]   public string Name     { get; set; } = "";
    [JsonPropertyName("avatar")] public string? Avatar  { get; set; }  // base64 小头像
    [JsonPropertyName("data")]   public string? Data    { get; set; }  // 附加数据

    public string Serialize() => JsonSerializer.Serialize(this);
    public static UdpPacket? Deserialize(string json) =>
        JsonSerializer.Deserialize<UdpPacket>(json);
}

// ───────────── TCP 消息帧 ─────────────
public class TcpMessage
{
    [JsonPropertyName("type")]     public string Type      { get; set; } = "";
    [JsonPropertyName("fromId")]   public string FromId    { get; set; } = "";
    [JsonPropertyName("fromName")] public string FromName  { get; set; } = "";
    [JsonPropertyName("toId")]     public string ToId      { get; set; } = "";
    [JsonPropertyName("content")]  public string Content   { get; set; } = "";   // 文字 or base64图片
    [JsonPropertyName("fileName")] public string? FileName { get; set; }
    [JsonPropertyName("fileSize")] public long    FileSize { get; set; }
    [JsonPropertyName("msgId")]    public string MsgId     { get; set; } = Guid.NewGuid().ToString("N");
    [JsonPropertyName("time")]     public long   Time      { get; set; } = DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();

    public string Serialize() => JsonSerializer.Serialize(this);
    public static TcpMessage? Deserialize(string json) =>
        JsonSerializer.Deserialize<TcpMessage>(json);
}

// ───────────── 本地用户 ─────────────
public class Peer
{
    public string Id       { get; set; } = "";
    public string Name     { get; set; } = "";
    public string IP       { get; set; } = "";
    public string? Avatar  { get; set; }   // base64
    public DateTime LastSeen { get; set; } = DateTime.UtcNow;
    public bool IsOnline   => (DateTime.UtcNow - LastSeen).TotalSeconds < 15;
}

// ───────────── 聊天消息（UI层） ─────────────
public enum MessageType { Text, Image, FileRequest, FileReceived }
public enum MessageDirection { Sent, Received }

public class ChatMessage
{
    public string       Id        { get; set; } = Guid.NewGuid().ToString("N");
    public string       FromId    { get; set; } = "";
    public string       FromName  { get; set; } = "";
    public MessageType  Type      { get; set; }
    public MessageDirection Direction { get; set; }
    public string       Content   { get; set; } = "";   // 文字 or 本地文件路径 or base64
    public string?      FileName  { get; set; }
    public long         FileSize  { get; set; }
    public DateTime     Time      { get; set; } = DateTime.Now;
    public string       TimeStr   => Time.ToString("HH:mm");
}
