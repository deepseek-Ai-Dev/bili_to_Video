package com.bili.converter;

import com.sun.jna.Library;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.WString;
import com.sun.jna.platform.win32.Ole32;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef;

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

    public static String chooseFolder(String title) {
        Ole32.INSTANCE.CoInitializeEx(Pointer.NULL, Ole32.COINIT_APARTMENTTHREADED | Ole32.COINIT_DISABLE_OLE1DDE);

        BROWSEINFO bi = new BROWSEINFO();
        // 自动获取当前 JavaFX 窗口句柄，保证弹窗置顶
        bi.hwndOwner = User32.INSTANCE.GetForegroundWindow();
        bi.lpszTitle = new WString(title);
        bi.ulFlags = BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE;
        bi.write();

        Pointer pidl = Shell32Ex.INSTANCE.SHBrowseForFolder(bi);
        if (pidl == null) return null;

        char[] path = new char[WinDef.MAX_PATH];
        Shell32Ex.INSTANCE.SHGetPathFromIDListW(pidl, path);
        Ole32.INSTANCE.CoTaskMemFree(pidl);

        return Native.toString(path);
    }
}