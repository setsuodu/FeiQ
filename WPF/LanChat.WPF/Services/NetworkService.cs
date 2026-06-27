using System.IO;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Sockets;
using System.Text;
using LanChat.WPF.Models;

namespace LanChat.WPF.Services;

public class NetworkService : IDisposable
{
    // ── 事件 ──
    public event Action<Peer>?               PeerDiscovered;
    public event Action<string>?             PeerLost;
    public event Action<TcpMessage>?         MessageReceived;
    public event Action<string, long, long>? TransferProgress;

    public string LocalId   { get; }
    public string LocalName { get; set; }
    public string LocalIP   { get; }

    private Socket?      _recvSocket;   // 专职接收 UDP
    private TcpListener? _tcpListener;
    private CancellationTokenSource _cts = new();
    private bool _disposed;

    // 子网广播地址，从本机 IP 推算（例：192.168.1.85 → 192.168.1.255）
    private readonly IPAddress _broadcastIP;

    public NetworkService(string name)
    {
        LocalId      = Guid.NewGuid().ToString("N")[..8];
        LocalName    = name;
        LocalIP      = GetLocalIP();
        _broadcastIP = GetBroadcastAddress(LocalIP);
    }

    // ────────────────────────────────────────────────────────────────
    //  启动
    // ────────────────────────────────────────────────────────────────
    public void Start()
    {
        StartUdpReceiver();
        StartTcpListener();
        _ = BroadcastLoopAsync();
        _ = BroadcastOnlineAsync();
    }

    // ────────────────────────────────────────────────────────────────
    //  UDP 接收（专用 Socket，SO_REUSEADDR，绑 0.0.0.0:52000）
    // ────────────────────────────────────────────────────────────────
    private void StartUdpReceiver()
    {
        _recvSocket = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
        _recvSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.ReuseAddress, true);
        _recvSocket.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.Broadcast, true);
        _recvSocket.Bind(new IPEndPoint(IPAddress.Any, Protocol.UDP_PORT));
        _ = UdpReceiveLoopAsync();
    }

    private async Task UdpReceiveLoopAsync()
    {
        var buf = new byte[8192];
        EndPoint remote = new IPEndPoint(IPAddress.Any, 0);
        while (!_cts.Token.IsCancellationRequested)
        {
            try
            {
                // 用同步阻塞放到线程池，避免旧 API 不支持 CancellationToken
                var n = await Task.Run(() => _recvSocket!.ReceiveFrom(buf, ref remote), _cts.Token);
                var json   = Encoding.UTF8.GetString(buf, 0, n);
                var packet = UdpPacket.Deserialize(json);
                if (packet == null || packet.FromId == LocalId) continue;

                var ip = ((IPEndPoint)remote).Address.ToString();
                var peer = new Peer
                {
                    Id       = packet.FromId,
                    Name     = packet.Name,
                    IP       = ip,
                    Avatar   = packet.Avatar,
                    LastSeen = DateTime.UtcNow
                };

                if (packet.Type == Protocol.MSG_OFFLINE)
                    PeerLost?.Invoke(packet.FromId);
                else
                    PeerDiscovered?.Invoke(peer);
            }
            catch (OperationCanceledException) { break; }
            catch { /* 忽略单包错误 */ }
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  UDP 发送（每次新建 Socket，绑本机 IP，发子网广播）
    // ────────────────────────────────────────────────────────────────
    private async Task BroadcastLoopAsync()
    {
        while (!_cts.Token.IsCancellationRequested)
        {
            SendBroadcast(Protocol.MSG_HEARTBEAT);
            try { await Task.Delay(5000, _cts.Token); } catch { break; }
        }
    }

    public Task BroadcastOnlineAsync()  { SendBroadcast(Protocol.MSG_ONLINE);  return Task.CompletedTask; }
    public Task BroadcastOfflineAsync() { SendBroadcast(Protocol.MSG_OFFLINE); return Task.CompletedTask; }

    private void SendBroadcast(string type)
    {
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.SetSocketOption(SocketOptionLevel.Socket, SocketOptionName.Broadcast, true);
            // 绑定本机 IP 出去，让接收方能拿到正确来源 IP
            s.Bind(new IPEndPoint(IPAddress.Parse(LocalIP), 0));

            var packet = new UdpPacket { Type = type, FromId = LocalId, Name = LocalName };
            var data   = Encoding.UTF8.GetBytes(packet.Serialize());
            s.SendTo(data, new IPEndPoint(_broadcastIP, Protocol.UDP_PORT));
        }
        catch { /* 忽略 */ }
    }

    // ────────────────────────────────────────────────────────────────
    //  TCP 监听
    // ────────────────────────────────────────────────────────────────
    private void StartTcpListener()
    {
        _tcpListener = new TcpListener(IPAddress.Any, Protocol.TCP_PORT);
        _tcpListener.Start();
        _ = TcpAcceptLoopAsync();
    }

    private async Task TcpAcceptLoopAsync()
    {
        while (!_cts.Token.IsCancellationRequested)
        {
            try
            {
                var client = await _tcpListener!.AcceptTcpClientAsync(_cts.Token);
                _ = HandleTcpClientAsync(client);
            }
            catch (OperationCanceledException) { break; }
            catch { }
        }
    }

    private async Task HandleTcpClientAsync(TcpClient client)
    {
        using (client)
        using (var stream = client.GetStream())
        {
            var header = new byte[4];
            await ReadExactAsync(stream, header, 4);
            var msgLen = BitConverter.ToInt32(header, 0);

            var msgBuf = new byte[msgLen];
            await ReadExactAsync(stream, msgBuf, msgLen);
            var msg = TcpMessage.Deserialize(Encoding.UTF8.GetString(msgBuf));
            if (msg == null) return;

            if (msg.Type == Protocol.MSG_FILE_REQ && msg.FileSize > 0)
            {
                var savePath = GetSavePath(msg.FileName ?? "file");
                await ReceiveFileAsync(stream, savePath, msg.FileSize, msg.MsgId);
                msg.Content = savePath;
            }

            MessageReceived?.Invoke(msg);
        }
    }

    private async Task ReceiveFileAsync(NetworkStream stream, string path, long size, string msgId)
    {
        using var fs = File.Create(path);
        var buf = new byte[65536];
        long received = 0;
        while (received < size)
        {
            int toRead = (int)Math.Min(buf.Length, size - received);
            int n      = await stream.ReadAsync(buf.AsMemory(0, toRead));
            if (n == 0) break;
            await fs.WriteAsync(buf.AsMemory(0, n));
            received += n;
            TransferProgress?.Invoke(msgId, received, size);
        }
    }

    // ────────────────────────────────────────────────────────────────
    //  TCP 发送
    // ────────────────────────────────────────────────────────────────
    public async Task SendTextAsync(string peerIp, string toId, string toName, string text)
    {
        var msg = new TcpMessage
        {
            Type = Protocol.MSG_TEXT, FromId = LocalId, FromName = LocalName,
            ToId = toId, Content = text
        };
        await SendFrameAsync(peerIp, msg);
    }

    public async Task SendImageAsync(string peerIp, string toId, string imagePath)
    {
        var base64 = Convert.ToBase64String(await File.ReadAllBytesAsync(imagePath));
        var msg = new TcpMessage
        {
            Type = Protocol.MSG_IMAGE, FromId = LocalId, FromName = LocalName,
            ToId = toId, Content = base64, FileName = Path.GetFileName(imagePath)
        };
        await SendFrameAsync(peerIp, msg);
    }

    public async Task SendFileAsync(string peerIp, string toId, string filePath,
                                    IProgress<(long sent, long total)>? progress = null)
    {
        var info = new FileInfo(filePath);
        var msg  = new TcpMessage
        {
            Type = Protocol.MSG_FILE_REQ, FromId = LocalId, FromName = LocalName,
            ToId = toId, FileName = info.Name, FileSize = info.Length
        };

        using var tcp    = new TcpClient();
        await tcp.ConnectAsync(peerIp, Protocol.TCP_PORT);
        using var stream = tcp.GetStream();

        var jsonBytes = Encoding.UTF8.GetBytes(msg.Serialize());
        await stream.WriteAsync(BitConverter.GetBytes(jsonBytes.Length));
        await stream.WriteAsync(jsonBytes);

        using var fs = File.OpenRead(filePath);
        var buf = new byte[65536];
        long sent = 0; int n;
        while ((n = await fs.ReadAsync(buf)) > 0)
        {
            await stream.WriteAsync(buf.AsMemory(0, n));
            sent += n;
            progress?.Report((sent, info.Length));
        }
    }

    private async Task SendFrameAsync(string peerIp, TcpMessage msg)
    {
        using var tcp    = new TcpClient();
        await tcp.ConnectAsync(peerIp, Protocol.TCP_PORT);
        using var stream = tcp.GetStream();
        var jsonBytes = Encoding.UTF8.GetBytes(msg.Serialize());
        await stream.WriteAsync(BitConverter.GetBytes(jsonBytes.Length));
        await stream.WriteAsync(jsonBytes);
    }

    // ────────────────────────────────────────────────────────────────
    //  工具
    // ────────────────────────────────────────────────────────────────
    private static async Task ReadExactAsync(NetworkStream stream, byte[] buf, int count)
    {
        int offset = 0;
        while (offset < count)
        {
            int n = await stream.ReadAsync(buf.AsMemory(offset, count - offset));
            if (n == 0) throw new IOException("Connection closed");
            offset += n;
        }
    }

    private static string GetLocalIP()
    {
        // 优先找能路由到外网的网卡 IP
        try
        {
            using var s = new Socket(AddressFamily.InterNetwork, SocketType.Dgram, ProtocolType.Udp);
            s.Connect("8.8.8.8", 80);
            return ((IPEndPoint)s.LocalEndPoint!).Address.ToString();
        }
        catch { }

        foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
        {
            if (ni.OperationalStatus != OperationalStatus.Up) continue;
            if (ni.NetworkInterfaceType is NetworkInterfaceType.Loopback) continue;
            foreach (var addr in ni.GetIPProperties().UnicastAddresses)
            {
                if (addr.Address.AddressFamily == AddressFamily.InterNetwork)
                    return addr.Address.ToString();
            }
        }
        return "127.0.0.1";
    }

    // 192.168.1.85 + 255.255.255.0 → 192.168.1.255
    private static IPAddress GetBroadcastAddress(string localIp)
    {
        try
        {
            foreach (var ni in NetworkInterface.GetAllNetworkInterfaces())
            {
                if (ni.OperationalStatus != OperationalStatus.Up) continue;
                foreach (var ua in ni.GetIPProperties().UnicastAddresses)
                {
                    if (ua.Address.ToString() != localIp) continue;
                    var ip   = ua.Address.GetAddressBytes();
                    var mask = ua.IPv4Mask?.GetAddressBytes();
                    if (mask == null) continue;
                    var bcast = new byte[4];
                    for (int i = 0; i < 4; i++) bcast[i] = (byte)(ip[i] | ~mask[i]);
                    return new IPAddress(bcast);
                }
            }
        }
        catch { }
        return IPAddress.Broadcast; // fallback 255.255.255.255
    }

    private static string GetSavePath(string fileName)
    {
        var dir = Path.Combine(
            Environment.GetFolderPath(Environment.SpecialFolder.UserProfile),
            "Downloads", "LanChat");
        Directory.CreateDirectory(dir);
        return Path.Combine(dir, fileName);
    }

    public void Dispose()
    {
        if (_disposed) return;
        _disposed = true;
        _cts.Cancel();
        _recvSocket?.Close();
        _tcpListener?.Stop();
    }
}
