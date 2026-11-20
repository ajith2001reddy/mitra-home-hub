package com.mitrahome.hub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.util.Log;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Private on-device photo library for MITRA. Photos are copied into app-private
 * storage so they remain available after a restart and are never sent to Home
 * Assistant, the photo source, or an external server.
 */
final class PhotoLibraryBridge {
  static final int PICK_PHOTOS_REQUEST = 73;
  private static final String TAG = "MitraPhotos";
  private static final String PREFS = "mitra_photos";
  private static final String KEY_LIBRARY = "library";
  private static final String DIRECTORY = "mitra-photos";
  private static final String PHOTO_PATH = "/personal-photos/";
  private static final String PHOTO_HOST = "https://appassets.androidplatform.net";

  private final Activity activity;
  private final WebView webView;
  private final SharedPreferences preferences;

  PhotoLibraryBridge(Activity activity, WebView webView) {
    this.activity = activity;
    this.webView = webView;
    this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @JavascriptInterface
  public String list() {
    return library().toString();
  }

  @JavascriptInterface
  public void choose() {
    activity.runOnUiThread(() -> {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType("image/*");
      intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
      try {
        activity.startActivityForResult(Intent.createChooser(intent, "Add photos to MITRA"), PICK_PHOTOS_REQUEST);
      } catch (Exception error) {
        Log.e(TAG, "Could not open the photo picker", error);
        sendError("MITRA could not open the photo picker.");
      }
    });
  }

  boolean handleActivityResult(int requestCode, int resultCode, Intent data) {
    if (requestCode != PICK_PHOTOS_REQUEST) return false;
    if (resultCode != Activity.RESULT_OK || data == null) return true;
    JSONArray updated = library();
    ArrayList<Uri> incoming = new ArrayList<>();
    if (data.getClipData() != null) {
      for (int index = 0; index < data.getClipData().getItemCount(); index++) incoming.add(data.getClipData().getItemAt(index).getUri());
    } else if (data.getData() != null) {
      incoming.add(data.getData());
    }
    JSONArray next = new JSONArray();
    for (Uri uri : incoming) {
      JSONObject stored = uri == null ? null : copyToPrivateLibrary(uri);
      if (stored != null) next.put(stored);
    }
    for (int index = 0; index < updated.length(); index++) next.put(updated.opt(index));
    save(next);
    sendLibrary(next);
    return true;
  }

  @JavascriptInterface
  public void remove(String id) {
    if (id == null || !id.matches("[a-zA-Z0-9_-]{1,80}")) return;
    JSONArray previous = library();
    JSONArray next = new JSONArray();
    for (int index = 0; index < previous.length(); index++) {
      JSONObject photo = previous.optJSONObject(index);
      if (photo != null && id.equals(photo.optString("id"))) {
        String fileName = photo.optString("file");
        if (isSafeFileName(fileName)) new File(photoDirectory(), fileName).delete();
      } else if (photo != null) {
        next.put(photo);
      }
    }
    save(next);
    sendLibrary(next);
  }

  WebResourceResponse openPersonalPhoto(String fileName) {
    if (!isSafeFileName(fileName)) return null;
    try {
      File directory = photoDirectory().getCanonicalFile();
      File photo = new File(directory, fileName).getCanonicalFile();
      if (!photo.getPath().startsWith(directory.getPath() + File.separator) || !photo.isFile()) return null;
      return new WebResourceResponse(mimeForFile(fileName), null, new FileInputStream(photo));
    } catch (IOException error) {
      Log.w(TAG, "Could not read local photo", error);
      return null;
    }
  }

  private JSONObject copyToPrivateLibrary(Uri uri) {
    String mimeType = activity.getContentResolver().getType(uri);
    if (mimeType == null || !mimeType.startsWith("image/")) return null;
    String extension = extensionFor(mimeType, displayName(uri));
    String id = UUID.randomUUID().toString().replace("-", "");
    String fileName = "photo_" + id + extension;
    File destination = new File(photoDirectory(), fileName);
    try (InputStream input = activity.getContentResolver().openInputStream(uri); OutputStream output = new FileOutputStream(destination)) {
      if (input == null) throw new IOException("The selected photo could not be opened.");
      byte[] buffer = new byte[32 * 1024];
      int read;
      while ((read = input.read(buffer)) != -1) output.write(buffer, 0, read);
      JSONObject photo = new JSONObject();
      photo.put("id", id);
      photo.put("file", fileName);
      photo.put("url", PHOTO_HOST + PHOTO_PATH + fileName);
      photo.put("createdAt", System.currentTimeMillis());
      return photo;
    } catch (Exception error) {
      destination.delete();
      Log.w(TAG, "Could not copy selected photo", error);
      return null;
    }
  }

  private JSONArray library() {
    try {
      return new JSONArray(preferences.getString(KEY_LIBRARY, "[]"));
    } catch (JSONException error) {
      return new JSONArray();
    }
  }

  private void save(JSONArray photos) {
    preferences.edit().putString(KEY_LIBRARY, photos.toString()).apply();
  }

  private void sendLibrary(JSONArray photos) {
    String script = "window.mitraPhotoLibrary && window.mitraPhotoLibrary(" + JSONObject.quote(photos.toString()) + ");";
    webView.post(() -> webView.evaluateJavascript(script, null));
  }

  private void sendError(String message) {
    String script = "window.console && console.error(" + JSONObject.quote(message) + ");";
    webView.post(() -> webView.evaluateJavascript(script, null));
  }

  private File photoDirectory() {
    File directory = new File(activity.getFilesDir(), DIRECTORY);
    if (!directory.exists()) directory.mkdirs();
    return directory;
  }

  private boolean isSafeFileName(String fileName) {
    return fileName != null && fileName.matches("photo_[a-zA-Z0-9]{1,64}\\.(jpg|jpeg|png|webp|gif|heic)");
  }

  private String displayName(Uri uri) {
    try (Cursor cursor = activity.getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) {
      if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
    } catch (Exception ignored) { }
    return "";
  }

  private String extensionFor(String mimeType, String displayName) {
    String lower = displayName.toLowerCase();
    if (lower.endsWith(".jpeg")) return ".jpeg";
    if (lower.endsWith(".png")) return ".png";
    if (lower.endsWith(".webp")) return ".webp";
    if (lower.endsWith(".gif")) return ".gif";
    if (lower.endsWith(".heic") || lower.endsWith(".heif")) return ".heic";
    return "image/png".equals(mimeType) ? ".png" : "image/webp".equals(mimeType) ? ".webp" : "image/gif".equals(mimeType) ? ".gif" : "image/heic".equals(mimeType) || "image/heif".equals(mimeType) ? ".heic" : ".jpg";
  }

  private String mimeForFile(String fileName) {
    if (fileName.endsWith(".png")) return "image/png";
    if (fileName.endsWith(".webp")) return "image/webp";
    if (fileName.endsWith(".gif")) return "image/gif";
    if (fileName.endsWith(".heic")) return "image/heic";
    return "image/jpeg";
  }
}
