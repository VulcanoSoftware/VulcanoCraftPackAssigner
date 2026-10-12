package be.vulcanosoftware.assignpack;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PackMirrorManagerTest {

    private HttpClient mockHttpClient;
    private PackMirrorManager manager;

    @BeforeEach
    void setUp() {
        mockHttpClient = mock(HttpClient.class);
        manager = new PackMirrorManager(mockHttpClient);
    }

    @Test
    void testParsePackArgs_SingleUrl() {
        String[] args = new String[]{"https://github.com/test/pack.zip"};
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(1, parsed.getUrls().size());
        assertEquals("https://github.com/test/pack.zip", parsed.getUrls().get(0));
        assertNull(parsed.getHash());
        assertNull(parsed.getUuidStr());
        assertFalse(parsed.isRequired());
        assertTrue(parsed.isAutoSha1());
    }

    @Test
    void testParsePackArgs_MultipleUrls() {
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "https://gitlab.com/test/pack.zip",
                "https://cdn.example.com/pack.zip"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(3, parsed.getUrls().size());
        assertEquals("https://github.com/test/pack.zip", parsed.getUrls().get(0));
        assertEquals("https://gitlab.com/test/pack.zip", parsed.getUrls().get(1));
        assertEquals("https://cdn.example.com/pack.zip", parsed.getUrls().get(2));
        assertNull(parsed.getHash());
        assertFalse(parsed.isRequired());
    }

    @Test
    void testParsePackArgs_WithManualSha1() {
        String sha1 = "a1b2c3d4e5f6789012345678901234567890abcd";
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "https://gitlab.com/test/pack.zip",
                sha1
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(2, parsed.getUrls().size());
        assertEquals(sha1, parsed.getHash());
        assertFalse(parsed.isAutoSha1());
    }

    @Test
    void testParsePackArgs_WithAutoKeyword() {
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "https://gitlab.com/test/pack.zip",
                "auto"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(2, parsed.getUrls().size());
        assertEquals("auto", parsed.getHash());
        assertTrue(parsed.isAutoSha1());
    }

    @Test
    void testParsePackArgs_FullParameters() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "auto",
                uuid,
                "true"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(1, parsed.getUrls().size());
        assertEquals("https://github.com/test/pack.zip", parsed.getUrls().get(0));
        assertEquals("auto", parsed.getHash());
        assertEquals(uuid, parsed.getUuidStr());
        assertTrue(parsed.isRequired());
    }

    @Test
    void testParsePackArgs_OptionalHashOmittedWithUuidAndRequired() {
        String uuid = "123e4567-e89b-12d3-a456-426614174000";
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                uuid,
                "true"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(1, parsed.getUrls().size());
        assertEquals("https://github.com/test/pack.zip", parsed.getUrls().get(0));
        assertNull(parsed.getHash());
        assertEquals(uuid, parsed.getUuidStr());
        assertTrue(parsed.isRequired());
        assertTrue(parsed.isAutoSha1());
    }

    @Test
    void testParsePackArgs_OptionalHashOmittedWithRequiredOnly() {
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "true"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(1, parsed.getUrls().size());
        assertEquals("https://github.com/test/pack.zip", parsed.getUrls().get(0));
        assertNull(parsed.getHash());
        assertNull(parsed.getUuidStr());
        assertTrue(parsed.isRequired());
    }

    @Test
    void testParsePackArgs_WithNoneHash() {
        String[] args = new String[]{
                "https://github.com/test/pack.zip",
                "none"
        };
        PackMirrorManager.ParsedArgs parsed = PackMirrorManager.parsePackArgs(args);

        assertNotNull(parsed);
        assertEquals(1, parsed.getUrls().size());
        assertEquals("none", parsed.getHash());

        String resolved = manager.resolveHash("none", "https://github.com/test/pack.zip");
        assertNull(resolved);
    }

    @Test
    void testParsePackArgs_EmptyOrNull() {
        assertNull(PackMirrorManager.parsePackArgs(null));
        assertNull(PackMirrorManager.parsePackArgs(new String[0]));
        assertNull(PackMirrorManager.parsePackArgs(new String[]{"true"}));
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSelectFirstWorkingMirror_Failover() throws IOException, InterruptedException {
        String url1 = "https://mirror1.example.com/pack.zip";
        String url2 = "https://mirror2.example.com/pack.zip";

        HttpResponse<Void> resp404 = mock(HttpResponse.class);
        when(resp404.statusCode()).thenReturn(404);

        HttpResponse<Void> resp200 = mock(HttpResponse.class);
        when(resp200.statusCode()).thenReturn(200);

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    HttpRequest req = invocation.getArgument(0);
                    if (req.uri().toString().equals(url1)) {
                        return resp404;
                    } else if (req.uri().toString().equals(url2)) {
                        return resp200;
                    }
                    return resp404;
                });

        String selected = manager.selectFirstWorkingMirror(Arrays.asList(url1, url2));
        assertEquals(url2, selected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testSelectFirstWorkingMirror_FallbackWhenAllFail() throws IOException, InterruptedException {
        String url1 = "https://mirror1.example.com/pack.zip";
        String url2 = "https://mirror2.example.com/pack.zip";

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new IOException("Connection refused"));

        String selected = manager.selectFirstWorkingMirror(Arrays.asList(url1, url2));
        assertEquals(url1, selected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFetchSha1FromSidecar_Success() throws IOException, InterruptedException {
        String packUrl = "https://mirror.example.com/pack.zip";
        String expectedHash = "a1b2c3d4e5f6789012345678901234567890abcd";

        HttpResponse<String> resp200 = mock(HttpResponse.class);
        when(resp200.statusCode()).thenReturn(200);
        when(resp200.body()).thenReturn(expectedHash + "\n");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> resp200);

        String hash = manager.fetchSha1FromSidecar(packUrl);
        assertEquals(expectedHash, hash);
    }

    @Test
    @SuppressWarnings("unchecked")
    void testFetchSha1FromSidecar_InvalidContentOrStatus() throws IOException, InterruptedException {
        String packUrl = "https://mirror.example.com/pack.zip";

        HttpResponse<String> resp200 = mock(HttpResponse.class);
        when(resp200.statusCode()).thenReturn(200);
        when(resp200.body()).thenReturn("invalid-hash-content");

        when(mockHttpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> resp200);

        String hash = manager.fetchSha1FromSidecar(packUrl);
        assertNull(hash);
    }

    @Test
    void testResolveHash_ManualHashPreserved() {
        String manualHash = "1111222233334444555566667777888899990000";
        String resolved = manager.resolveHash(manualHash, "https://mirror.example.com/pack.zip");
        assertEquals(manualHash.toLowerCase(), resolved);
    }

    @Test
    void testLastUsedRecording() {
        List<String> mirrors = Arrays.asList("https://m1.com/pack.zip", "https://m2.com/pack.zip");
        manager.recordLastUsed(mirrors, "https://m1.com/pack.zip", "a1b2c3d4e5f6789012345678901234567890abcd");

        PackMirrorManager.LastUsedInfo info = manager.getLastUsedInfo();
        assertNotNull(info);
        assertEquals(2, info.getMirrors().size());
        assertEquals("https://m1.com/pack.zip", info.getSelectedMirror());
        assertEquals("a1b2c3d4e5f6789012345678901234567890abcd", info.getHash());
    }
}
