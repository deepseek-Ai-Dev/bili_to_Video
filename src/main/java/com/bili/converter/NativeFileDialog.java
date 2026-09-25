package com.bili.converter;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.WinDef;

import java.awt.Window; // 修改为导入 Window

public class NativeFileDialog {

    public interface Shell32Ex extends Library {
        Shell32Ex INSTANCE = Native.load("shell32", Shell32Ex.class);
        Pointer SHBrowseForFolder(BROWSEINFO lpbi);
        boolean SHGetPathFromIDListW(Pointer pidl, char[] pszPath);
    }

    @Structure.FieldOrder({"hwndOwner", "pidlRoot", "pszDisplayName", "lpszTitle", "ulFlags", "lpfn", "lParam", "iImage"})
    public static class BROWSEINFO extends Structure {
        public WinDef.HWND hwndOwner;
        public Pointer pidlRoot;
        public Pointer pszDisplayName;
        public WString lpszTitle;
        public int ulFlags;
        public Pointer lpfn;
        public Pointer lParam;
        public int iImage;

        public BROWSEINFO() {
            super();
            this.pszDisplayName = new com.sun.jna.Memory(260 * Native.WCHAR_SIZE);
        }
    }

    public static final int BIF_RETURNONLYFSDIRS = 0x00000001;
    public static final int BIF_NEWDIALOGSTYLE = 0x00000040;

    /**
     * 调用 Windows 原生 API 选择文件夹（资源管理器风格）
     * @param parent 父级 Swing 窗口（JFrame 或 JDialog），用于将弹窗置于主窗口前面
     * @param title 弹窗标题
     */
    public static String chooseFolder(Window parent, String title) { // 【修改点】参数改为 Window
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED | Ole32.COINIT_DISABLE_OLE1DDE);

        BROWSEINFO bi = new BROWSEINFO();

        if (parent != null && parent.isShowing()) {
            // 现在 parent 是 Window 类型，Native.getWindowPointer 就能正确识别了
            bi.hwndOwner = new WinDef.HWND(Native.getWindowPointer(parent));
        }

        bi.lpszTitle = new WString(title);
        bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
        bi.write();

        Pointer pidl = Shell32Ex.INSTANCE.SHBrowseForFolder(bi);
        if (pidl == null) {
            return null;
        }

        char[] path = new char[WinDef.MAX_PATH];
        Shell32Ex.INSTANCE.SHGetPathFromIDListW(pidl, path);
        Ole32.INSTANCE.CoTaskMemFree(pidl);

        return Native.toString(path);
    }
}