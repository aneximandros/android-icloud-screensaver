package com.aneximandros.icloudscreensaver;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.service.dreams.DreamService;
import android.util.Log;
import android.view.View;
import android.widget.ImageView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * DreamService that displays a slideshow from an iCloud Shared Album.
 *
 * Uses Apple's CloudKit Web Services API:
 *   1. POST /public/records/resolve  — exchanges the share token for an anonymous
 *      access token and the partition URL for the album's CloudKit zone.
 *   2. POST /shared/records/query    — queries CPLAssetAndMasterByAddedDate, an
 *      index that is accessible without authentication on public shared albums.
 *      Returns CPLMaster records whose resJPEGMedRes.downloadURL fields point to
 *      1536-px JPEG derivatives, served as plain (non-encrypted) files.
 *
 * The album URL is stored in SharedPreferences by SetupActivity.
 */
public class ICloudPhotosDream extends DreamService {

    static final String PREFS      = "icloud_screensaver";
    static final String PREF_URL   = "album_url";

    private static final long SLIDE_MS = 10_000;
    private static final long FADE_MS  = 1_500;
    private static final String TAG = "ICloudPhotosDream";

    private static final String CK_BASE =
            "https://ckdatabasews.icloud.com/database/1/com.apple.photos.cloud/production";
    private static final String CK_BUILD = "2642Build18";

    private ImageView imgA, imgB;
    private boolean showingA = true;
    private final List<String> photoUrls = new ArrayList<>();
    private int photoIndex = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private ExecutorService executor;
    private volatile boolean dreaming = false;

    @Override
    public void onAttachedToWindow() {
        super.onAttachedToWindow();
        setInteractive(false);
        setFullscreen(true);
        setScreenBright(true);
        setContentView(R.layout.dream_photos);
        imgA = findViewById(R.id.dreamPhotoA);
        imgB = findViewById(R.id.dreamPhotoB);
        executor = Executors.newSingleThreadExecutor();
    }

    @Override
    public void onDreamingStarted() {
        super.onDreamingStarted();
        dreaming = true;
        final String albumUrl = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getString(PREF_URL, "");
        Log.i(TAG, "onDreamingStarted albumUrl=" + (albumUrl.isEmpty() ? "(not set)" : albumUrl));
        if (albumUrl.isEmpty()) { finish(); return; }
        executor.execute(() -> loadPhotos(albumUrl));
    }

    @Override
    public void onDreamingStopped() {
        dreaming = false;
        handler.removeCallbacksAndMessages(null);
        super.onDreamingStopped();
    }

    @Override
    public void onDetachedFromWindow() {
        dreaming = false;
        handler.removeCallbacksAndMessages(null);
        if (executor != null) executor.shutdownNow();
        super.onDetachedFromWindow();
    }

    // ---- photo loading -------------------------------------------------------------------------

    private void loadPhotos(String albumUrl) {
        try {
            String token = albumUrl.substring(albumUrl.lastIndexOf('/') + 1);
            if (token.isEmpty()) { handler.post(this::finish); return; }

            List<String> urls = fetchPhotoUrls(token);

            if (urls == null || urls.isEmpty()) {
                Log.w(TAG, "loadPhotos: no photo URLs found — check logcat for details");
                handler.post(this::finish);
                return;
            }
            Collections.shuffle(urls);
            synchronized (photoUrls) { photoUrls.clear(); photoUrls.addAll(urls); }
            Log.i(TAG, "loadPhotos: slideshow ready, " + urls.size() + " photos");
            handler.post(this::showNextPhoto);
        } catch (Exception e) {
            Log.e(TAG, "loadPhotos error", e);
            handler.post(this::finish);
        }
    }

    // ---- CloudKit API --------------------------------------------------------------------------

    /**
     * Fetches JPEG download URLs for all photos in the shared album.
     *
     * Step 1 — resolve:  POST /public/records/resolve?sharing_url_key={token}
     *   Body: {"shortGUIDs":[{"value":"{token}"}]}
     *   Returns: anonymousPublicAccess.token, .databasePartition, and the zone's zoneID.
     *
     * Step 2 — query:    POST {partition}/shared/records/query?publicAccessAuthToken={anonToken}
     *   Body: {"query":{"recordType":"CPLAssetAndMasterByAddedDate"}, "zoneID":{...}, ...}
     *   Returns: CPLAsset + CPLMaster record pairs.
     *   CPLMaster.fields.resJPEGMedRes.value.downloadURL is a signed HTTPS URL to
     *   a 1536-px JPEG, served decrypted by Apple's CDN.
     */
    private List<String> fetchPhotoUrls(String albumToken) {
        try {
            // Step 1: resolve token → anonymous credentials + zone
            String resolveBody = "{\"shortGUIDs\":[{\"value\":\"" + albumToken + "\"}]}";
            String resolveUrl = CK_BASE + "/public/records/resolve"
                    + "?remapEnums=true&clientBuildNumber=" + CK_BUILD
                    + "&clientMasteringNumber=" + CK_BUILD
                    + "&sharing_url_key=" + albumToken;
            String resolveJson = httpPost(resolveUrl, resolveBody);
            if (resolveJson == null) return null;

            JSONObject resolve = new JSONObject(resolveJson);
            JSONArray results = resolve.optJSONArray("results");
            if (results == null || results.length() == 0) {
                Log.w(TAG, "fetchPhotoUrls: resolve returned no results");
                return null;
            }
            JSONObject result = results.getJSONObject(0);

            JSONObject apa = result.optJSONObject("anonymousPublicAccess");
            if (apa == null) {
                Log.w(TAG, "fetchPhotoUrls: no anonymousPublicAccess — is the album public?");
                return null;
            }
            String anonToken = apa.optString("token");
            String partition  = apa.optString("databasePartition").replaceAll("/$", "");

            JSONObject zoneID = result.optJSONObject("zoneID");
            if (zoneID == null) { Log.w(TAG, "fetchPhotoUrls: no zoneID"); return null; }
            String zoneName        = zoneID.optString("zoneName");
            String ownerRecordName = zoneID.optString("ownerRecordName", "");

            Log.i(TAG, "fetchPhotoUrls: zone=" + zoneName);

            // Step 2: query CPLAssetAndMasterByAddedDate with pagination
            List<String> urls = new ArrayList<>();
            String continuationMarker = null;
            do {
                JSONObject queryBody = new JSONObject();
                queryBody.put("query", new JSONObject().put("recordType", "CPLAssetAndMasterByAddedDate"));
                queryBody.put("zoneID", new JSONObject()
                        .put("zoneName", zoneName)
                        .put("ownerRecordName", ownerRecordName));
                queryBody.put("resultsLimit", 200);
                if (continuationMarker != null) queryBody.put("continuationMarker", continuationMarker);

                String queryUrl = partition + "/database/1/com.apple.photos.cloud/production"
                        + "/shared/records/query"
                        + "?remapEnums=true&clientBuildNumber=" + CK_BUILD
                        + "&clientMasteringNumber=" + CK_BUILD
                        + "&publicAccessAuthToken=" + anonToken;
                String queryJson = httpPost(queryUrl, queryBody.toString());
                if (queryJson == null) break;

                JSONObject queryData = new JSONObject(queryJson);
                JSONArray records = queryData.optJSONArray("records");
                if (records != null) {
                    for (int i = 0; i < records.length(); i++) {
                        JSONObject rec = records.getJSONObject(i);
                        if (!"CPLMaster".equals(rec.optString("recordType"))) continue;
                        JSONObject fields = rec.optJSONObject("fields");
                        if (fields == null) continue;
                        String url = extractAssetDownloadUrl(fields, "resJPEGMedRes");
                        if (url == null) url = extractAssetDownloadUrl(fields, "resJPEGFullRes");
                        if (url == null) url = extractAssetDownloadUrl(fields, "resOriginalRes");
                        if (url != null) urls.add(url);
                    }
                }
                if (queryData.has("continuationMarker")) {
                    continuationMarker = queryData.getString("continuationMarker");
                    if (continuationMarker.isEmpty()) continuationMarker = null;
                } else {
                    continuationMarker = null;
                }
            } while (continuationMarker != null);

            Log.i(TAG, "fetchPhotoUrls: found " + urls.size() + " photos");
            return urls.isEmpty() ? null : urls;

        } catch (Exception e) {
            Log.e(TAG, "fetchPhotoUrls error", e);
            return null;
        }
    }

    private String extractAssetDownloadUrl(JSONObject fields, String key) {
        try {
            JSONObject field = fields.optJSONObject(key);
            if (field == null) return null;
            JSONObject value = field.optJSONObject("value");
            if (value == null) return null;
            String url = value.optString("downloadURL", "");
            if (url.isEmpty()) return null;
            // The URL contains a ${f} placeholder for the filename; replace it with a JPEG name.
            return url.replace("${f}", "photo.jpeg");
        } catch (Exception e) { return null; }
    }

    // ---- slideshow ------------------------------------------------------------------------------

    private void showNextPhoto() {
        if (!dreaming) return;
        List<String> snap;
        synchronized (photoUrls) { snap = new ArrayList<>(photoUrls); }
        if (snap.isEmpty()) return;
        String url = snap.get(photoIndex % snap.size());
        photoIndex++;
        executor.execute(() -> {
            Bitmap bmp = downloadBitmap(url);
            handler.post(() -> {
                if (!dreaming) return;
                ImageView next = showingA ? imgB : imgA;
                ImageView curr = showingA ? imgA : imgB;
                if (bmp != null) next.setImageBitmap(bmp);
                next.setAlpha(0f);
                next.setVisibility(View.VISIBLE);
                ObjectAnimator.ofFloat(next, "alpha", 0f, 1f).setDuration(FADE_MS).start();
                ObjectAnimator fadeOut = ObjectAnimator.ofFloat(curr, "alpha", 1f, 0f);
                fadeOut.setDuration(FADE_MS);
                fadeOut.addListener(new AnimatorListenerAdapter() {
                    @Override public void onAnimationEnd(Animator a) {
                        curr.setVisibility(View.INVISIBLE);
                    }
                });
                fadeOut.start();
                showingA = !showingA;
                handler.postDelayed(this::showNextPhoto, SLIDE_MS);
            });
        });
    }

    // ---- HTTP helpers ---------------------------------------------------------------------------

    private Bitmap downloadBitmap(String urlStr) {
        try {
            File cacheDir = new File(getCacheDir(), "photos");
            if (!cacheDir.exists()) //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
            // Cache by path only (strip expiring query string) for reuse across URL refreshes.
            String pathOnly = urlStr.contains("?") ? urlStr.substring(0, urlStr.indexOf('?')) : urlStr;
            File cached = new File(cacheDir, Integer.toHexString(pathOnly.hashCode()) + ".jpg");
            if (cached.exists()) {
                Bitmap cached_bmp = BitmapFactory.decodeFile(cached.getAbsolutePath());
                if (cached_bmp != null) return cached_bmp;
                cached.delete();
            }

            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(30_000);
            c.connect();
            byte[] bytes = readBytes(c.getInputStream());
            c.disconnect();

            Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
            if (bmp != null) {
                try (FileOutputStream fos = new FileOutputStream(cached)) { fos.write(bytes); }
            }
            return bmp;
        } catch (Exception e) {
            Log.w(TAG, "downloadBitmap error: " + e);
            return null;
        }
    }

    private String httpPost(String urlStr, String body) {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection c = (HttpURLConnection) url.openConnection();
            c.setConnectTimeout(10_000);
            c.setReadTimeout(15_000);
            c.setRequestMethod("POST");
            c.setRequestProperty("Content-Type", "text/plain");
            c.setRequestProperty("Origin", "https://www.icloud.com");
            c.setDoOutput(true);
            c.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
            int code = c.getResponseCode();
            Log.i(TAG, "httpPost -> " + code);
            if (code == 200) return readStream(c.getInputStream());
            InputStream err = c.getErrorStream();
            if (err != null) Log.w(TAG, "httpPost error body: " + readStream(err));
            return null;
        } catch (Exception e) {
            Log.w(TAG, "httpPost error: " + e);
            return null;
        }
    }

    private String readStream(InputStream in) throws Exception {
        return new String(readBytes(in), StandardCharsets.UTF_8);
    }

    private byte[] readBytes(InputStream in) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
