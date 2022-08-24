package net.christianbeier.droidvnc_ng;

public class HotkeyBinding implements Comparable<HotkeyBinding> {
    private final VNCKey[] keys = new VNCKey[3];
    // placeholder for command to execute by this hotkey
    private final ICommand command;
    // user-friendly name. Uses in GUI
    public final String friendlyName;
    // uses for settings serialization
    public final String PREFS_NAME;

    public HotkeyBinding(String friendlyName, String prefsName, VNCKey key1, VNCKey key2, VNCKey key3, ICommand command) {
        keys[0] = key1;
        keys[1] = key2;
        keys[2] = key3;
        this.command = command;
        this.friendlyName = friendlyName;
        this.PREFS_NAME = prefsName;
    }

    public boolean isTriggered() {
        for (VNCKey key : keys) {
            if (key != null && key != VNCKey.Empty && key.isNotDown())
                return false;
        }
        return true;
    }

    public boolean tryExecute() {
        if (isTriggered()) {
            command.execute();
            return true;
        }
        return false;
    }

    public VNCKey getKey(int index) {
        if (index > 3) return VNCKey.Empty;
        return keys[index];
    }

    public void setKey(int index, VNCKey key) {
        if (index > 3) return;
        keys[index] = key;
    }

    @Override
    public int compareTo(HotkeyBinding hotkeyBinding) {
        return hotkeyBinding.keys.length - keys.length;
    }

    public interface ICommand {
        void execute();
    }
}
