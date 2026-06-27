using System.Globalization;
using System.IO;
using System.Windows;
using System.Windows.Data;
using System.Windows.Media.Imaging;
using LanChat.WPF.Models;

namespace LanChat.WPF.Converters;

// base64 或 文件路径 → BitmapImage
public class ImageSourceConverter : IValueConverter
{
    public object? Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is not string s || string.IsNullOrEmpty(s)) return null;
        try
        {
            var bmp = new BitmapImage();
            bmp.BeginInit();
            bmp.CacheOption = BitmapCacheOption.OnLoad;
            if (File.Exists(s))
                bmp.UriSource = new Uri(s);
            else
            {
                bmp.StreamSource = new MemoryStream(System.Convert.FromBase64String(s));
            }
            bmp.EndInit();
            bmp.Freeze();
            return bmp;
        }
        catch { return null; }
    }
    public object ConvertBack(object v, Type t, object p, CultureInfo c) => Binding.DoNothing;
}

// MessageDirection → HorizontalAlignment
public class DirectionToAlignConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        => value is MessageDirection d && d == MessageDirection.Sent
            ? HorizontalAlignment.Right
            : HorizontalAlignment.Left;
    public object ConvertBack(object v, Type t, object p, CultureInfo c) => Binding.DoNothing;
}

// MessageType == X → Visibility
public class TypeToVisibilityConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is MessageType t && parameter is string p)
            return t.ToString() == p ? Visibility.Visible : Visibility.Collapsed;
        return Visibility.Collapsed;
    }
    public object ConvertBack(object v, Type t, object p, CultureInfo c) => Binding.DoNothing;
}

// 文件大小格式化
public class FileSizeConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
    {
        if (value is long size)
        {
            if (size < 1024) return $"{size} B";
            if (size < 1024 * 1024) return $"{size / 1024.0:F1} KB";
            if (size < 1024L * 1024 * 1024) return $"{size / (1024.0 * 1024):F1} MB";
            return $"{size / (1024.0 * 1024 * 1024):F2} GB";
        }
        return "";
    }
    public object ConvertBack(object v, Type t, object p, CultureInfo c) => Binding.DoNothing;
}

// 方向 → 气泡背景色
public class DirectionToBubbleColorConverter : IValueConverter
{
    public object Convert(object value, Type targetType, object parameter, CultureInfo culture)
        => value is MessageDirection d && d == MessageDirection.Sent
            ? System.Windows.Media.Brushes.LightSkyBlue
            : System.Windows.Media.Brushes.White;
    public object ConvertBack(object v, Type t, object p, CultureInfo c) => Binding.DoNothing;
}
