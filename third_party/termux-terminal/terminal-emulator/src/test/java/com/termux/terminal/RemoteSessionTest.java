package com.termux.terminal;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

/** Runs on the host JVM: native linkage or process creation would fail these tests. */
public class RemoteSessionTest {
    @Test public void remoteOutputParsesAnsiAndUtf8WithoutNativeLibrary() {
        List<String> callbacks = new ArrayList<>();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        TerminalSession session = session(input, callbacks);
        session.updateSize(80, 24, 8, 16);
        byte[] output = "\u001b[31mhello 日本語\u001b[0m\r\n\u001b]2;Remote title\u0007".getBytes(StandardCharsets.UTF_8);
        // Split inside multibyte codepoints to exercise streaming UTF-8.
        for (byte b : output) session.appendRemote(new byte[]{b});
        assertTrue(session.getEmulator().getScreen().getTranscriptText().contains("hello 日本語"));
        assertEquals("Remote title", session.getTitle());
        assertEquals(-1, session.getPid());
        assertTrue(callbacks.contains("onTextChanged"));
        assertTrue(callbacks.contains("onTitleChanged"));
        session.updateSize(40, 12, 8, 16);
        assertEquals(40, session.getEmulator().mColumns);
        assertEquals(12, session.getEmulator().mRows);
        assertTrue(callbacks.contains("resize:40:12"));
    }
    @Test public void inputEncoderAndDetachUseOnlyRemoteCallbacks() {
        List<String> callbacks = new ArrayList<>();
        ByteArrayOutputStream input = new ByteArrayOutputStream();
        TerminalSession session = session(input, callbacks);
        session.updateSize(80, 24, 8, 16);
        session.writeCodePoint(false, 0x1F408);
        session.writeCodePoint(true, 'x');
        session.write("\u0003");
        assertEquals("🐈\u001bx\u0003", new String(input.toByteArray(), StandardCharsets.UTF_8));
        session.finishIfRunning();
        assertTrue(callbacks.contains("close"));
        session.markExited(7);
        assertFalse(session.isRunning());
        assertEquals(7, session.getExitStatus());
        int before = input.size();
        session.write("discard");
        assertEquals(before, input.size());
        assertTrue(callbacks.contains("onSessionFinished"));
    }
    private TerminalSession session(ByteArrayOutputStream input, List<String> callbacks) {
        TerminalSessionClient client = (TerminalSessionClient) Proxy.newProxyInstance(
            getClass().getClassLoader(), new Class<?>[]{TerminalSessionClient.class}, (proxy, method, args) -> {
                callbacks.add(method.getName());
                return method.getName().equals("getTerminalCursorStyle") ? 0 : null;
            });
        return new TerminalSession(new TerminalSession.RemoteIO() {
            public void write(byte[] bytes) { input.write(bytes, 0, bytes.length); }
            public void resize(int cols, int rows) { callbacks.add("resize:" + cols + ":" + rows); }
            public void close() { callbacks.add("close"); }
        }, client);
    }
}
