package net.christianbeier.droidvnc_ng;

import java.util.HashMap;

public enum VNCKey {
    /*
        List of registered key codes and their corresponding keys.
        Easy to edit
    */
    Empty(0x0L),
    Ctrl(0xFFE3L),
    Alt(0xFFE9L),
    MacOSAlt(0xFF7EL),
    Shift(0xFFE1L),
    Del(0xFFFFL),
    Esc(0xFF1BL),
    Home(0xFF50L),
    Tab(0xFF09L),
    PageUp(0xFF55L),
    PageDown(0xFF56L),
    End(0xFF57L),
    Backspace(0xFF08L),
    F1(0xFFBEL),
    F2(0xFFBFL),
    F3(0xFFC0L),
    F4(0xFFC1L),
    F5(0xFFC2L),
    F6(0xFFC3L),
    F7(0xFFC4L),
    F8(0xFFC5L),
    F9(0xFFC6L),
    F10(0xFFC7L),
    F11(0xFFC8L),
    F12(0xFFC9L);

    /*
        Used for easy access to key states. Helps to get rid of endless if/else constructs
    */
    private static final HashMap<Long, VNCKey> KeyRegistrar = new HashMap<Long, VNCKey>() {{
        for (VNCKey k : VNCKey.values())
            put(k.keysym, k);
    }};

    public final long keysym;
    private boolean isDown = false;

    VNCKey(long keysym) {
        this.keysym = keysym;
    }

    public boolean isNotDown() {
        return !isDown;
    }

    public void setKeyState(int state) {
        isDown = state != 0;
    }

    public static boolean tryChangeKeyState(long keysym, int keystate) {
        if (KeyRegistrar.containsKey(keysym)) {
            KeyRegistrar.get(keysym).setKeyState(keystate);

            return true;
        }
        return false;
    }

    public static String[] toStringsArray() {
        String[] result = new String[KeyRegistrar.size()-1];
        VNCKey[] values = VNCKey.values();
        for (int i = 0; i < result.length; i++) {
            result[i] = values[i].name();
        }
        return result;
    }

    public static VNCKey getKeyByName(String keyName) {
        try {
            return VNCKey.valueOf(keyName);
        } catch (Exception ignored) {
            return null;
        }
    }
}
