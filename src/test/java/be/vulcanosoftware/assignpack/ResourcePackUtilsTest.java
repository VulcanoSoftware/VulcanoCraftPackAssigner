package be.vulcanosoftware.assignpack;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

public class ResourcePackUtilsTest {

    @Test
    public void testIsValidSha1Hex() {
        assertTrue(ResourcePackUtils.isValidSha1Hex("a1b2c3d4e5f6789012345678901234567890abcd"));
        assertTrue(ResourcePackUtils.isValidSha1Hex("A1B2C3D4E5F6789012345678901234567890ABCD"));
        assertFalse(ResourcePackUtils.isValidSha1Hex("a1b2c3d4e5f6789012345678901234567890abc")); // 39 chars
        assertFalse(ResourcePackUtils.isValidSha1Hex("a1b2c3d4e5f6789012345678901234567890abcde")); // 41 chars
        assertFalse(ResourcePackUtils.isValidSha1Hex("a1b2c3d4e5f6789012345678901234567890xyzg")); // invalid hex chars
    }

    @Test
    public void testHexToBytesAndBack() {
        String hex = "a1b2c3d4e5f6789012345678901234567890abcd";
        byte[] bytes = ResourcePackUtils.hexToBytes(hex);
        assertNotNull(bytes);
        assertEquals(20, bytes.length);

        String resultHex = ResourcePackUtils.bytesToHex(bytes);
        assertEquals(hex, resultHex);
    }

    @Test
    public void testParseUrlsAndHashSingleUrl() {
        String[] args = {"Player1", "https://example.com/pack.zip"};
        ResourcePackUtils.ParsedArgs parsed = ResourcePackUtils.parseUrlsAndHash(args, 1);

        assertEquals(1, parsed.getUrls().size());
        assertEquals("https://example.com/pack.zip", parsed.getUrls().get(0));
        assertNull(parsed.getHashArg());
    }

    @Test
    public void testParseUrlsAndHashMultipleUrls() {
        String[] args = {"Player1", "https://mirror1.com/pack.zip", "https://mirror2.com/pack.zip"};
        ResourcePackUtils.ParsedArgs parsed = ResourcePackUtils.parseUrlsAndHash(args, 1);

        assertEquals(2, parsed.getUrls().size());
        assertEquals("https://mirror1.com/pack.zip", parsed.getUrls().get(0));
        assertEquals("https://mirror2.com/pack.zip", parsed.getUrls().get(1));
        assertNull(parsed.getHashArg());
    }

    @Test
    public void testParseUrlsAndHashAuto() {
        String[] args = {"Player1", "https://mirror1.com/pack.zip", "https://mirror2.com/pack.zip", "auto"};
        ResourcePackUtils.ParsedArgs parsed = ResourcePackUtils.parseUrlsAndHash(args, 1);

        assertEquals(2, parsed.getUrls().size());
        assertEquals("https://mirror1.com/pack.zip", parsed.getUrls().get(0));
        assertEquals("https://mirror2.com/pack.zip", parsed.getUrls().get(1));
        assertEquals("auto", parsed.getHashArg());
    }

    @Test
    public void testParseUrlsAndHashExplicitHex() {
        String hash = "a1b2c3d4e5f6789012345678901234567890abcd";
        String[] args = {"Player1", "https://mirror1.com/pack.zip", "https://mirror2.com/pack.zip", hash};
        ResourcePackUtils.ParsedArgs parsed = ResourcePackUtils.parseUrlsAndHash(args, 1);

        assertEquals(2, parsed.getUrls().size());
        assertEquals("https://mirror1.com/pack.zip", parsed.getUrls().get(0));
        assertEquals("https://mirror2.com/pack.zip", parsed.getUrls().get(1));
        assertEquals(hash, parsed.getHashArg());
    }
}
