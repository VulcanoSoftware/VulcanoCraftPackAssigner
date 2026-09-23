package be.vulcanosoftware.assignpack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class ResourcePackUtils {

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.ALWAYS)
            .connectTimeout(Duration.ofSeconds(3))
            .build();

    public static class MirrorResult {
        private final String workingUrl;
        private final String rawHash;
        private final byte[] hashBytes;
        private final String error;

        public MirrorResult(String workingUrl, String rawHash, byte[] hashBytes) {
            this.workingUrl = workingUrl;
            this.rawHash = rawHash;
            this.hashBytes = hashBytes;
            this.error = null;
        }

        public MirrorResult(String error) {
            this.workingUrl = null;
            this.rawHash = null;
            this.hashBytes = null;
            this.error = error;
        }

        public boolean isSuccess() {
            return workingUrl != null;
        }

        public String getWorkingUrl() {
            return workingUrl;
        }

        public String getRawHash() {
            return rawHash;
        }

        public byte[] getHashBytes() {
            return hashBytes;
        }

        public String getError() {
            return error;
        }
    }

    public static class ParsedArgs {
        private final List<String> urls;
        private final String hashArg; // null, "auto", or 40-char hex string

        public ParsedArgs(List<String> urls, String hashArg) {
            this.urls = urls;
            this.hashArg = hashArg;
        }

        public List<String> getUrls() {
            return urls;
        }

        public String getHashArg() {
            return hashArg;
        }
    }

    /**
     * Parses command arguments for URLs and hash argument.
     * Expects args starting from mirror URLs up to optional hash/auto argument.
     */
    public static ParsedArgs parseUrlsAndHash(String[] args, int startIndex) {
        List<String> urls = new ArrayList<>();
        String hashArg = null;

        for (int i = startIndex; i < args.length; i++) {
            String arg = args[i].trim();
            if (arg.isEmpty()) continue;

            if (i == args.length - 1) {
                // Last argument could be "auto", a 40-hex hash, or a URL.
                if (arg.equalsIgnoreCase("auto")) {
                    hashArg = "auto";
                    continue;
                } else if (isValidSha1Hex(arg)) {
                    hashArg = arg.toLowerCase(Locale.ROOT);
                    continue;
                }
            }
            urls.add(arg);
        }

        return new ParsedArgs(urls, hashArg);
    }

    /**
     * Tests a list of URLs in order using HEAD request (3s timeout).
     * Returns the first reachable URL, or null if none reachable.
     */
    public static String findFirstWorkingMirror(List<String> urls) {
        for (String url : urls) {
            if (testMirrorUrl(url)) {
                return url;
            }
        }
        return null;
    }

    /**
     * Tests a single URL with a HEAD request. Returns true if status 200 OK.
     */
    public static boolean testMirrorUrl(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "VulcanoCraftResourcePackAssigner/1.3")
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();

            HttpResponse<Void> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Resolves mirror failover and SHA-1 calculation/fetching.
     */
    public static MirrorResult selectMirrorAndFetchHash(List<String> urls, String hashArg) {
        if (urls.isEmpty()) {
            return new MirrorResult("Geen geldige URLs opgegeven.");
        }

        String workingUrl = findFirstWorkingMirror(urls);
        if (workingUrl == null) {
            return new MirrorResult("Geen van de opgegeven resourcepack mirrors is bereikbaar.");
        }

        String hexHash = null;
        if (hashArg != null && isValidSha1Hex(hashArg)) {
            hexHash = hashArg.toLowerCase(Locale.ROOT);
        } else if (hashArg == null || hashArg.equalsIgnoreCase("auto")) {
            // Attempt sidecar .sha1 fetch
            hexHash = fetchSha1Sidecar(workingUrl);
        }

        byte[] hashBytes = null;
        if (hexHash != null && isValidSha1Hex(hexHash)) {
            hashBytes = hexToBytes(hexHash);
        }

        return new MirrorResult(workingUrl, hexHash, hashBytes);
    }

    /**
     * Fetches sidecar .sha1 file for the working URL.
     * e.g., url + ".sha1"
     */
    public static String fetchSha1Sidecar(String url) {
        try {
            String sha1Url = url + ".sha1";
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(sha1Url))
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "VulcanoCraftResourcePackAssigner/1.3")
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                String body = response.body().trim();
                // If body contains multiple words or lines, take the first 40-hex token
                String[] parts = body.split("\\s+");
                for (String part : parts) {
                    if (isValidSha1Hex(part)) {
                        return part.toLowerCase(Locale.ROOT);
                    }
                }
            }
        } catch (Exception e) {
            // Sidecar fetch failed or non-200
        }
        return null;
    }

    public static boolean isValidSha1Hex(String str) {
        if (str == null || str.length() != 40) return false;
        for (int i = 0; i < str.length(); i++) {
            char c = str.charAt(i);
            if (Character.digit(c, 16) == -1) return false;
        }
        return true;
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.length() % 2 != 0) return null;
        byte[] bytes = new byte[hex.length() / 2];
        for (int i = 0; i < hex.length(); i += 2) {
            int high = Character.digit(hex.charAt(i), 16);
            int low = Character.digit(hex.charAt(i + 1), 16);
            if (high == -1 || low == -1) return null;
            bytes[i / 2] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return null;
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
