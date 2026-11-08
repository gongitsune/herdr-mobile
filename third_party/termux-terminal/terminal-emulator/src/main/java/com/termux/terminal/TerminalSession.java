package com.termux.terminal;

import java.util.UUID;

/** Remote-only session. All emulator operations must run on Android's main thread. */
public class TerminalSession extends TerminalOutput {
    public interface RemoteIO {
        void write(byte[] bytes);
        void resize(int columns, int rows);
        void close();
    }
    public final String mHandle = UUID.randomUUID().toString();
    public String mSessionName;
    private final RemoteIO remote;
    private TerminalSessionClient mClient;
    private TerminalEmulator mEmulator;
    private final byte[] mUtf8InputBuffer = new byte[5];
    private boolean running = true;
    private int exitStatus;
    public TerminalSession(RemoteIO remote, TerminalSessionClient client) {
        this.remote = remote;
        this.mClient = client;
    }
    public void updateTerminalSessionClient(TerminalSessionClient client) {
        mClient = client;
        if (mEmulator != null) mEmulator.updateTerminalSessionClient(client);
    }
    public void updateSize(int columns, int rows, int cellWidthPixels, int cellHeightPixels) {
        if (mEmulator == null) mEmulator = new TerminalEmulator(this, columns, rows, cellWidthPixels, cellHeightPixels, 2000, mClient);
        else mEmulator.resize(columns, rows, cellWidthPixels, cellHeightPixels);
        remote.resize(columns, rows);
    }
    public void appendRemote(byte[] bytes) {
        if (mEmulator == null) throw new IllegalStateException("Initialize emulator before collecting output");
        mEmulator.append(bytes, bytes.length);
        mClient.onTextChanged(this);
    }
    @Override public void write(byte[] bytes, int offset, int count) {
        if (running) remote.write(java.util.Arrays.copyOfRange(bytes, offset, offset + count));
    }
    /** Write the Unicode code point to the terminal encoded in UTF-8. */
    public void writeCodePoint(boolean prependEscape, int codePoint) {
        if (codePoint > 1114111 || (codePoint >= 0xD800 && codePoint <= 0xDFFF)) {
            // 1114111 (= 2**16 + 1024**2 - 1) is the highest code point, [0xD800,0xDFFF] is the surrogate range.
            throw new IllegalArgumentException("Invalid code point: " + codePoint);
        }

        int bufferPosition = 0;
        if (prependEscape) mUtf8InputBuffer[bufferPosition++] = 27;

        if (codePoint <= /* 7 bits */0b1111111) {
            mUtf8InputBuffer[bufferPosition++] = (byte) codePoint;
        } else if (codePoint <= /* 11 bits */0b11111111111) {
            /* 110xxxxx leading byte with leading 5 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11000000 | (codePoint >> 6));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else if (codePoint <= /* 16 bits */0b1111111111111111) {
            /* 1110xxxx leading byte with leading 4 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11100000 | (codePoint >> 12));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        } else { /* We have checked codePoint <= 1114111 above, so we have max 21 bits = 0b111111111111111111111 */
            /* 11110xxx leading byte with leading 3 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b11110000 | (codePoint >> 18));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 12) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | ((codePoint >> 6) & 0b111111));
            /* 10xxxxxx continuation byte with following 6 bits */
            mUtf8InputBuffer[bufferPosition++] = (byte) (0b10000000 | (codePoint & 0b111111));
        }
        write(mUtf8InputBuffer, 0, bufferPosition);
    }


    public TerminalEmulator getEmulator() { return mEmulator; }
    public String getTitle() { return mEmulator == null ? null : mEmulator.getTitle(); }
    public void reset() { if (mEmulator != null) { mEmulator.reset(); mClient.onTextChanged(this); } }
    public boolean isRunning() { return running; }
    public int getExitStatus() { return exitStatus; }
    public int getPid() { return -1; }
    public String getCwd() { return null; }
    public void finishIfRunning() { remote.close(); }
    public void markExited(int code) { running = false; exitStatus = code; mClient.onSessionFinished(this); }
    @Override public void titleChanged(String oldTitle, String newTitle) { mClient.onTitleChanged(this); }
    @Override public void onCopyTextToClipboard(String text) { mClient.onCopyTextToClipboard(this, text); }
    @Override public void onPasteTextFromClipboard() { mClient.onPasteTextFromClipboard(this); }
    @Override public void onBell() { mClient.onBell(this); }
    @Override public void onColorsChanged() { mClient.onColorsChanged(this); }
}
