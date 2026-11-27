package net.kdt.pojavlaunch.ultraoptimization;

import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.DataSource;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.ProjectInfo;
import net.kdt.pojavlaunch.ultraoptimization.UltraOptimizationModels.VersionInfo;

/**
 * On-demand Modrinth v2 client for Ultra-Optimization.
 *
 * Deliberately independent from the modpack search stack: it performs one request per call,
 * only while the user is looking at the Ultra-Optimization screen or running an optimization.
 * Nothing is scheduled, retried in the background, or kept in memory beyond a small LRU cache
 * of the exact projects and version lists that were requested.
 */
public final class ModrinthClient implements DataSource {
    private static final String TAG = "UltraOptimization";
    private static final String API_BASE = "https://api.modrinth.com/v2";
    private static final String USER_AGENT = "PojavLauncher-Renewed/Ultra-Optimization (self-hosted fork)";
    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final int CONNECT_TIMEOUT_MS = 15000;
    private static final int READ_TIMEOUT_MS = 30000;
    private static final int MAX_CACHED_PROJECTS = 12;
    private static final int MAX_CACHED_VERSION_LISTS = 12;

    private final Gson mGson = new Gson();

    private final LinkedHashMap<String, ProjectInfo> mProjectCache =
            new LimitedCache<>(MAX_CACHED_PROJECTS);
    private final LinkedHashMap<String, List<VersionInfo>> mVersionCache =
            new LimitedCache<>(MAX_CACHED_VERSION_LISTS);

    @Override
    public synchronized ProjectInfo project(String slugOrId) throws IOException {
        String key = cacheKey(slugOrId);
        ProjectInfo cached = mProjectCache.get(key);
        if (cached != null) return cached;
        ProjectInfo project = mGson.fromJson(get("project/" + encodePath(slugOrId)), ProjectInfo.class);
        if (project != null) mProjectCache.put(key, project);
        return project;
    }

    @Override
    public synchronized List<ProjectInfo> projects(List<String> idsOrSlugs) throws IOException {
        if (idsOrSlugs == null || idsOrSlugs.isEmpty()) return Collections.emptyList();
        JsonArray ids = new JsonArray();
        for (String id : idsOrSlugs) ids.add(id);
        JsonObject body = new JsonObject();
        body.add("ids", ids);
        String response = post("projects", body.toString());
        if (response == null) return Collections.emptyList();
        List<ProjectInfo> projects = mGson.fromJson(response,
                new TypeToken<List<ProjectInfo>>() {}.getType());
        if (projects == null) return Collections.emptyList();
        List<ProjectInfo> result = new ArrayList<>();
        for (ProjectInfo project : projects) {
            if (project == null) continue;
            result.add(project);
            if (project.slug != null) mProjectCache.put(cacheKey(project.slug), project);
            if (project.id != null) mProjectCache.put(cacheKey(project.id), project);
        }
        return result;
    }

    @Override
    public synchronized List<VersionInfo> versions(String projectIdOrSlug, String minecraftVersion,
                                                   List<String> loaders) throws IOException {
        StringBuilder endpoint = new StringBuilder("project/").append(encodePath(projectIdOrSlug))
                .append("/version?");
        List<String> query = new ArrayList<>();
        if (minecraftVersion != null && minecraftVersion.length() > 0) {
            query.add("game_versions=" + encode(jsonArray(Collections.singletonList(minecraftVersion))));
        }
        if (loaders != null && !loaders.isEmpty()) {
            query.add("loaders=" + encode(jsonArray(loaders)));
        }
        // Cap the payload: the exact version/loader filters normally return a handful of entries.
        query.add("limit=100");
        for (int i = 0; i < query.size(); i++) {
            if (i > 0) endpoint.append('&');
            endpoint.append(query.get(i));
        }
        String key = cacheKey(projectIdOrSlug) + '|' + minecraftVersion + '|' + loaders;
        List<VersionInfo> cached = mVersionCache.get(key);
        if (cached != null) return cached;
        List<VersionInfo> versions = mGson.fromJson(get(endpoint.toString()),
                new TypeToken<List<VersionInfo>>() {}.getType());
        if (versions == null) return Collections.emptyList();
        mVersionCache.put(key, versions);
        return versions;
    }

    @Override
    public synchronized VersionInfo version(String versionId) throws IOException {
        return mGson.fromJson(get("version/" + encodePath(versionId)), VersionInfo.class);
    }

    private String get(String endpoint) throws IOException {
        return request("GET", API_BASE + "/" + endpoint, null);
    }

    private String post(String endpoint, String jsonBody) throws IOException {
        return request("POST", API_BASE + "/" + endpoint, jsonBody);
    }

    private String request(String method, String url, String jsonBody) throws IOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Cancelled");
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("User-Agent", USER_AGENT);
            connection.setRequestProperty("Accept", "application/json");
            if (jsonBody != null) {
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setDoOutput(true);
                byte[] payload = jsonBody.getBytes(UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                OutputStream outputStream = connection.getOutputStream();
                try {
                    outputStream.write(payload);
                    outputStream.flush();
                } finally {
                    outputStream.close();
                }
            }
            int status = connection.getResponseCode();
            if (status == HttpURLConnection.HTTP_NOT_FOUND) return null;
            InputStream inputStream = status >= 200 && status < 300
                    ? connection.getInputStream() : connection.getErrorStream();
            String body = inputStream == null ? null : readFully(inputStream);
            if (status < 200 || status >= 300) {
                throw new IOException("Modrinth returned HTTP " + status + " for " + endpointOf(url));
            }
            return body;
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private static String endpointOf(String url) {
        int index = url.indexOf("/v2/");
        return index < 0 ? "request" : url.substring(index + 4);
    }

    private static String readFully(InputStream inputStream) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(16 * 1024);
        byte[] buffer = new byte[8192];
        try {
            int read;
            long total = 0;
            while ((read = inputStream.read(buffer)) != -1) {
                total += read;
                // A project/version listing is small JSON. Aborting here keeps memory bounded.
                if (total > 4L * 1024L * 1024L) throw new IOException("Modrinth response is unexpectedly large");
                output.write(buffer, 0, read);
            }
        } finally {
            inputStream.close();
        }
        return new String(output.toByteArray(), UTF_8);
    }

    private static String jsonArray(List<String> values) {
        StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) builder.append(',');
            builder.append('"').append(values.get(i).replace("\\", "\\\\").replace("\"", "\\\""))
                    .append('"');
        }
        return builder.append(']').toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException e) {
            throw new IllegalStateException("UTF-8 is required", e);
        }
    }

    private static String encodePath(String value) {
        if (value == null) return "";
        if (value.indexOf('/') >= 0 || value.indexOf('\\') >= 0 || value.indexOf('?') >= 0
                || value.indexOf('#') >= 0 || "..".equals(value)) {
            throw new IllegalArgumentException("Unsafe Modrinth identifier: " + value);
        }
        return value;
    }

    private static String cacheKey(String value) {
        return value == null ? "" : value.trim().toLowerCase(java.util.Locale.US);
    }

    /** Bounded LRU so the launcher never keeps a mod catalogue in memory. */
    private static final class LimitedCache<V> extends LinkedHashMap<String, V> {
        private final int mLimit;

        LimitedCache(int limit) {
            super(8, 0.75f, true);
            mLimit = limit;
        }

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, V> eldest) {
            return size() > mLimit;
        }
    }
}
