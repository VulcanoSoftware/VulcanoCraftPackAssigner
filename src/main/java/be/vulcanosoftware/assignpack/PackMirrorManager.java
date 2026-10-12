package be.vulcanosoftware.assignpack;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.*;

public class PackMirrorManager {

    public static class ParsedArgs {
        private final List<String> urls;
        private final String hash; // 40-hex hash, "auto", or null
        private final String uuidStr;
        private final boolean required;

        public ParsedArgs(List<String> urls, String hash, String uuidStr, boolean required) {
            this.urls = urls;
            this.hash = hash;
            this.uuidStr = uuidStr;
            this.required = required;
        }

        public List<String> getUrls() {
            return urls;
        }

        public String getHash() {
            return hash;
        }

        public String getUuidStr() {
            return uuidStr;
        }

        public boolean isRequired() {
            return required;
        }

        public boolean isAutoSha1() {
            return hash == null || hash.equalsIgnoreCase("auto");
        }
    }

    public static class MirrorTestItem {
        private final String url;
        private final boolean success;
        private final int statusCode;
        private final long durationMs;
        private final String error;

        public MirrorTestItem(String url, boolean success, int statusCode, long durationMs, String error) {
            this.url = url;
            this.success = success;
            this.statusCode = statusCode;
            this.durationMs = durationMs;
            this.error = error;
        }

        public String getUrl() {
            return url;
        }

        public boolean isSuccess() {
            return success;
        }

        public int getStatusCode() {
            return statusCode;
        }

        public long getDurationMs() {
            return durationMs;
        }

        public String getError() {
            return error;
        }
    }

    public static class MirrorTestSummary {
        private final List<MirrorTestItem> results;
        private final String selectedUrl;
        private final String fetchedHash;

        public MirrorTestSummary(List<MirrorTestItem> results, String selectedUrl, String fetchedHash) {
            this.results = results;
            this.selectedUrl = selectedUrl;
            this.fetchedHash = fetchedHash;
        }

        public List<MirrorTestItem> getResults() {
            return results;
        }

        public String getSelectedUrl() {
            return selectedUrl;
        }

        public String getFetchedHash() {
            return fetchedHash;
        }
    }

    public static class LastUsedInfo {
        private final List<String> mirrors;
        private final String selectedMirror;
        private final String hash;
        private final long timestamp;

        public LastUsedInfo(List<String> mirrors, String selectedMirror, String hash) {
            this.mirrors = new ArrayList<>(mirrors);
            this.selectedMirror = selectedMirror;
            this.hash = hash;
            this.timestamp = System.currentTimeMillis();
        }

        public List<String> getMirrors() {
            return mirrors;
        }

        public String getSelectedMirror() {
            return selectedMirror;
        }

        public String getHash() {
            return hash;
        }

        public long getTimestamp() {
            return timestamp;
        }
    }

    private final HttpClient httpClient;
    private LastUsedInfo lastUsedInfo;

    public PackMirrorManager() {
        this(HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public PackMirrorManager(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * Parse raw arguments after player name into ParsedArgs.
     * Expected trailing pattern: [urls...] [hash|auto] [uuid] [required]
     */
    public static ParsedArgs parsePackArgs(String[] args) {
        if (args == null || args.length == 0) {
            return null;
        }

        List<String> argList = new ArrayList<>(Arrays.asList(args));
        boolean required = false;
        String uuidStr = null;
        String hash = null;

        // 1. Check last arg for boolean 'required'
        if (!argList.isEmpty()) {
            String last = argList.get(argList.size() - 1);
            if (last.equalsIgnoreCase("true") || last.equalsIgnoreCase("false")) {
                required = Boolean.parseBoolean(last);
                argList.remove(argList.size() - 1);
            }
        }

        // 2. Check last arg for UUID
        if (!argList.isEmpty()) {
            String last = argList.get(argList.size() - 1);
            if (isValidUuid(last)) {
                uuidStr = last;
                argList.remove(argList.size() - 1);
            }
        }

        // 3. Check last arg for hash, "auto", "none", or empty string hash indicator
        if (!argList.isEmpty()) {
            String last = argList.get(argList.size() - 1);
            if (last.equalsIgnoreCase("auto") || last.equalsIgnoreCase("none") || last.matches("^[a-fA-F0-9]{40}$")) {
                hash = last;
                argList.remove(argList.size() - 1);
            }
        }

        // Remaining items are candidate URLs
        List<String> urls = new ArrayList<>();
        for (String arg : argList) {
            if (!arg.trim().isEmpty()) {
                urls.add(arg.trim());
            }
        }

        if (urls.isEmpty()) {
            return null;
        }

        return new ParsedArgs(urls, hash, uuidStr, required);
    }

    private static boolean isValidUuid(String str) {
        if (str == null || str.length() != 36) {
            return false;
        }
        try {
            UUID.fromString(str);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Proactively checks mirrors from left to right using HTTP HEAD requests.
     * Returns the first URL that returns a 2xx response code.
     * If all fail, returns the first URL in the list as fallback.
     */
    public String selectFirstWorkingMirror(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            return null;
        }

        for (String urlStr : urls) {
            if (isMirrorReachable(urlStr)) {
                return urlStr;
            }
        }

        // Fallback to first URL if none reachable
        return urls.get(0);
    }

    public boolean isMirrorReachable(String urlStr) {
        try {
            URI uri = URI.create(urlStr);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "Mozilla/5.0 (Minecraft ResourcePack Assigner)")
                    .build();

            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() >= 200 && response.statusCode() < 300;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Fetches sidecar .sha1 file for the given pack URL.
     * Returns lowercase 40-character hex hash if valid, or null if not found/invalid.
     */
    public String fetchSha1FromSidecar(String packUrl) {
        if (packUrl == null || packUrl.isEmpty()) {
            return null;
        }

        try {
            String sha1Url = packUrl + ".sha1";
            URI uri = URI.create(sha1Url);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(uri)
                    .GET()
                    .timeout(Duration.ofSeconds(3))
                    .header("User-Agent", "Mozilla/5.0 (Minecraft ResourcePack Assigner)")
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200 && response.body() != null) {
                String body = response.body().trim();
                if (body.matches("^[a-fA-F0-9]{40}$")) {
                    return body.toLowerCase();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /**
     * Resolves final SHA-1 hash to use for a pack URL.
     * If hashParam is explicit "none", returns null.
     * If hashParam is explicit 40 hex chars, use it.
     * If hashParam is null or "auto", attempts to fetch .sha1 sidecar file.
     */
    public String resolveHash(String hashParam, String packUrl) {
        if (hashParam != null && hashParam.equalsIgnoreCase("none")) {
            return null;
        }
        if (hashParam != null && !hashParam.equalsIgnoreCase("auto") && hashParam.matches("^[a-fA-F0-9]{40}$")) {
            return hashParam.toLowerCase();
        }
        return fetchSha1FromSidecar(packUrl);
    }

    /**
     * Test all given URLs and produce a MirrorTestSummary.
     */
    public MirrorTestSummary testMirrors(List<String> urls) {
        List<MirrorTestItem> testResults = new ArrayList<>();
        String workingMirror = null;

        for (String urlStr : urls) {
            long startTime = System.currentTimeMillis();
            try {
                URI uri = URI.create(urlStr);
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(uri)
                        .method("HEAD", HttpRequest.BodyPublishers.noBody())
                        .timeout(Duration.ofSeconds(3))
                        .header("User-Agent", "Mozilla/5.0 (Minecraft ResourcePack Assigner)")
                        .build();

                HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
                long duration = System.currentTimeMillis() - startTime;
                int code = response.statusCode();
                boolean success = code >= 200 && code < 300;

                testResults.add(new MirrorTestItem(urlStr, success, code, duration, null));
                if (success && workingMirror == null) {
                    workingMirror = urlStr;
                }
            } catch (Exception e) {
                long duration = System.currentTimeMillis() - startTime;
                testResults.add(new MirrorTestItem(urlStr, false, -1, duration, e.getMessage()));
            }
        }

        String fetchedHash = null;
        if (workingMirror != null) {
            fetchedHash = fetchSha1FromSidecar(workingMirror);
        }

        return new MirrorTestSummary(testResults, workingMirror, fetchedHash);
    }

    public synchronized void recordLastUsed(List<String> mirrors, String selectedMirror, String hash) {
        this.lastUsedInfo = new LastUsedInfo(mirrors, selectedMirror, hash);
    }

    public synchronized LastUsedInfo getLastUsedInfo() {
        return lastUsedInfo;
    }
}
