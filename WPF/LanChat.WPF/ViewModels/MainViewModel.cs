using System.Collections.ObjectModel;
using System.IO;
using System.Windows;
using CommunityToolkit.Mvvm.ComponentModel;
using CommunityToolkit.Mvvm.Input;
using LanChat.WPF.Models;
using LanChat.WPF.Services;

namespace LanChat.WPF.ViewModels;

public partial class MainViewModel : ObservableObject, IDisposable
{
    private readonly NetworkService _net;

    // ── 属性 ──
    [ObservableProperty] private string _myName = Environment.UserName;
    [ObservableProperty] private Peer?  _selectedPeer;
    [ObservableProperty] private string _inputText = "";
    [ObservableProperty] private string _statusText = "已连接";

    public ObservableCollection<Peer>        Peers    { get; } = [];
    public ObservableCollection<ChatMessage> Messages { get; } = [];

    // 每个 Peer 的消息记录
    private readonly Dictionary<string, List<ChatMessage>> _history = [];

    public MainViewModel()
    {
        _net = new NetworkService(MyName);
        _net.PeerDiscovered  += OnPeerDiscovered;
        _net.PeerLost        += OnPeerLost;
        _net.MessageReceived += OnMessageReceived;
        _net.Start();
        _ = _net.BroadcastOnlineAsync();
    }

    // ────────────────────────────────────────────────────────────────
    //  网络事件
    // ────────────────────────────────────────────────────────────────
    private void OnPeerDiscovered(Peer peer)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var existing = Peers.FirstOrDefault(p => p.Id == peer.Id);
            if (existing == null)
            {
                Peers.Add(peer);
                if (!_history.ContainsKey(peer.Id))
                    _history[peer.Id] = [];
            }
            else
            {
                existing.LastSeen = peer.LastSeen;
                existing.IP       = peer.IP;
                existing.Name     = peer.Name;
            }
        });
    }

    private void OnPeerLost(string peerId)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var peer = Peers.FirstOrDefault(p => p.Id == peerId);
            if (peer != null) Peers.Remove(peer);
        });
    }

    private void OnMessageReceived(TcpMessage msg)
    {
        Application.Current.Dispatcher.Invoke(() =>
        {
            var cm = new ChatMessage
            {
                FromId    = msg.FromId,
                FromName  = msg.FromName,
                Direction = MessageDirection.Received,
                Content   = msg.Content,
                FileName  = msg.FileName,
                FileSize  = msg.FileSize,
                Time      = DateTimeOffset.FromUnixTimeMilliseconds(msg.Time).LocalDateTime,
                Type      = msg.Type switch
                {
                    Protocol.MSG_IMAGE    => MessageType.Image,
                    Protocol.MSG_FILE_REQ => MessageType.FileReceived,
                    _                     => MessageType.Text
                }
            };

            if (!_history.ContainsKey(msg.FromId))
                _history[msg.FromId] = [];
            _history[msg.FromId].Add(cm);

            // 若当前正在和该用户聊天，直接追加到消息列表
            if (SelectedPeer?.Id == msg.FromId)
                Messages.Add(cm);
        });
    }

    // ────────────────────────────────────────────────────────────────
    //  命令
    // ────────────────────────────────────────────────────────────────
    partial void OnSelectedPeerChanged(Peer? value)
    {
        Messages.Clear();
        if (value == null) return;
        if (_history.TryGetValue(value.Id, out var hist))
            foreach (var m in hist) Messages.Add(m);
    }

    [RelayCommand]
    private async Task SendTextAsync()
    {
        if (SelectedPeer == null || string.IsNullOrWhiteSpace(InputText)) return;
        var text = InputText.Trim();
        InputText = "";

        var cm = new ChatMessage
        {
            FromId    = _net.LocalId,
            FromName  = MyName,
            Direction = MessageDirection.Sent,
            Type      = MessageType.Text,
            Content   = text
        };
        AddToHistory(SelectedPeer.Id, cm);

        try { await _net.SendTextAsync(SelectedPeer.IP, SelectedPeer.Id, SelectedPeer.Name, text); }
        catch (Exception ex) { StatusText = $"发送失败: {ex.Message}"; }
    }

    [RelayCommand]
    private async Task SendImageAsync()
    {
        if (SelectedPeer == null) return;
        var dlg = new Microsoft.Win32.OpenFileDialog
        {
            Filter = "图片|*.png;*.jpg;*.jpeg;*.gif;*.bmp;*.webp",
            Title  = "选择图片"
        };
        if (dlg.ShowDialog() != true) return;

        var path = dlg.FileName;
        var cm = new ChatMessage
        {
            FromId    = _net.LocalId,
            FromName  = MyName,
            Direction = MessageDirection.Sent,
            Type      = MessageType.Image,
            Content   = path,          // 本地路径，UI直接展示
            FileName  = Path.GetFileName(path)
        };
        AddToHistory(SelectedPeer.Id, cm);

        try { await _net.SendImageAsync(SelectedPeer.IP, SelectedPeer.Id, path); }
        catch (Exception ex) { StatusText = $"图片发送失败: {ex.Message}"; }
    }

    [RelayCommand]
    private async Task SendFileAsync()
    {
        if (SelectedPeer == null) return;
        var dlg = new Microsoft.Win32.OpenFileDialog { Title = "选择文件" };
        if (dlg.ShowDialog() != true) return;

        var path = dlg.FileName;
        var info = new FileInfo(path);
        var cm = new ChatMessage
        {
            FromId    = _net.LocalId,
            FromName  = MyName,
            Direction = MessageDirection.Sent,
            Type      = MessageType.FileRequest,
            FileName  = info.Name,
            FileSize  = info.Length,
            Content   = path
        };
        AddToHistory(SelectedPeer.Id, cm);

        var progress = new Progress<(long sent, long total)>(p =>
            StatusText = $"发送 {info.Name}: {p.sent * 100 / p.total}%");
        try
        {
            await _net.SendFileAsync(SelectedPeer.IP, SelectedPeer.Id, path, progress);
            StatusText = $"{info.Name} 发送完成";
        }
        catch (Exception ex) { StatusText = $"文件发送失败: {ex.Message}"; }
    }

    [RelayCommand]
    private void OpenFile(ChatMessage? msg)
    {
        if (msg?.Content == null) return;
        if (File.Exists(msg.Content))
            System.Diagnostics.Process.Start("explorer.exe", $"/select,\"{msg.Content}\"");
        else
            StatusText = $"文件不存在: {msg.Content}";
    }

    private void AddToHistory(string peerId, ChatMessage cm)
    {
        if (!_history.ContainsKey(peerId)) _history[peerId] = [];
        _history[peerId].Add(cm);
        if (SelectedPeer?.Id == peerId) Messages.Add(cm);
    }

    public void Dispose()
    {
        _ = _net.BroadcastOfflineAsync();
        _net.Dispose();
    }
}
