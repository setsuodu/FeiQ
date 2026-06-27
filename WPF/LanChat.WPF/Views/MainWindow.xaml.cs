using System.Windows;
using System.Windows.Controls;
using System.Windows.Input;
using LanChat.WPF.ViewModels;

namespace LanChat.WPF.Views;

public partial class MainWindow : Window
{
    private MainViewModel VM => (MainViewModel)DataContext;

    public MainWindow()
    {
        InitializeComponent();
        // 消息新增时自动滚到底部
        VM.Messages.CollectionChanged += (_, _) =>
        {
            Dispatcher.BeginInvoke(() =>
            {
                MsgScroller.ScrollToEnd();
            }, System.Windows.Threading.DispatcherPriority.Background);
        };
    }

    // Enter 发送，Shift+Enter 换行
    private void Input_KeyDown(object sender, KeyEventArgs e)
    {
        if (e.Key == Key.Enter && Keyboard.Modifiers != ModifierKeys.Shift)
        {
            e.Handled = true;
            VM.SendTextCommand.Execute(null);
        }
    }

    private void SendImage_Click(object sender, RoutedEventArgs e)
        => VM.SendImageCommand.Execute(null);

    private void SendFile_Click(object sender, RoutedEventArgs e)
        => VM.SendFileCommand.Execute(null);

    private void Window_Closed(object sender, EventArgs e)
        => VM.Dispose();
}
