package com.mitrahome.hub;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.provider.DocumentsContract.Document;
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
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
  static final int PICK_DRIVE_FOLDER_REQUEST = 74;
  private static final String TAG = "MitraPhotos";
  private static final String PREFS = "mitra_photos";
  private static final String KEY_LIBRARY = "library";
  private static final String KEY_DRIVE_FOLDER_URI = "drive_folder_uri";
  private static final String KEY_DRIVE_FOLDER_NAME = "drive_folder_name";
  private static final String KEY_IGNORED_DRIVE_SOURCES = "ignored_drive_sources";
  private static final String DIRECTORY = "mitra-photos";
  private static final String PHOTO_PATH = "/personal-photos/";
  private static final String PHOTO_HOST = "https://appassets.androidplatform.net";
  private static final int MAX_DRIVE_PHOTOS_PER_SYNC = 100;
  private static final long MAX_DRIVE_PHOTO_BYTES = 30L * 1024L * 1024L;

  private final Activity activity;
  private final WebView webView;
  private final SharedPreferences preferences;
  private final ExecutorService executor = Executors.newSingleThreadExecutor();

  PhotoLibraryBridge(Activity activity, WebView webView) {
    this.activity = activity;
    this.webView = webView;
    this.preferences = activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  @JavascriptInterface
  public String list() {
    return library().toString();
  }

  /**
   * Google Drive is accessed with Android's system document picker, not a broad
   * Drive OAuth scope. Android grants MITRA read access only to the tree that
   * the person selects in the picker.
   */
  @JavascriptInterface
  public String driveStatus() {
    return driveStatusPayload(hasDriveFolder() ? "connected" : "setup", hasDriveFolder() ? "Selected folder is ready to sync." : "Choose one Google Drive folder that contains your photos.").toString();
  }

  @JavascriptInterface
  public void chooseDriveFolder() {
    activity.runOnUiThread(() -> {
      Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
      intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
      try {
        sendDriveStatus("selecting", "Choose the Google Drive folder containing your MITRA photos.");
        activity.startActivityForResult(intent, PICK_DRIVE_FOLDER_REQUEST);
      } catch (Exception error) {
        Log.e(TAG, "Could not open the Drive folder picker", error);
        sendDriveStatus("error", "MITRA could not open the folder picker.");
      }
    });
  }

  @JavascriptInterface
  public void refreshDriveFolder() {
    executor.execute(this::synchronizeDriveFolder);
  }

  @JavascriptInterface
  public void disconnectDriveFolder() {
    executor.execute(() -> {
      sendDriveStatus("syncing", "Removing the selected Drive folder from MITRA.");
      String stored = preferences.getString(KEY_DRIVE_FOLDER_URI, "");
      if (!stored.isEmpty()) {
        try { activity.getContentResolver().releasePersistableUriPermission(Uri.parse(stored), Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
      }
      preferences.edit().remove(KEY_DRIVE_FOLDER_URI).remove(KEY_DRIVE_FOLDER_NAME).remove(KEY_IGNORED_DRIVE_SOURCES).apply();
      clearDrivePhotos();
      sendDriveStatus("setup", "Drive folder access was removed. Local photos remain on this Lenovo.");
    });
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
    if (requestCode == PICK_DRIVE_FOLDER_REQUEST) {
      if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
        sendDriveStatus(hasDriveFolder() ? "connected" : "setup", "Folder selection was cancelled.");
        return true;
      }
      Uri folder = data.getData();
      int grantedFlags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
      if (grantedFlags == 0) {
        sendDriveStatus("error", "Android did not grant read access to that folder.");
        return true;
      }
      try {
        activity.getContentResolver().takePersistableUriPermission(folder, grantedFlags);
        String previous = preferences.getString(KEY_DRIVE_FOLDER_URI, "");
        if (!folder.toString().equals(previous)) {
          clearDrivePhotos();
          preferences.edit().remove(KEY_IGNORED_DRIVE_SOURCES).apply();
        }
        preferences.edit().putString(KEY_DRIVE_FOLDER_URI, folder.toString()).putString(KEY_DRIVE_FOLDER_NAME, displayName(folder)).apply();
        executor.execute(this::synchronizeDriveFolder);
      } catch (SecurityException error) {
        Log.w(TAG, "Drive folder permission could not be saved", error);
        sendDriveStatus("error", "MITRA could not keep access to that Drive folder.");
      }
      return true;
    }
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
      JSONObject stored = uri == null ? null : copyToPrivateLibrary(uri, "device:" + uri);
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
        if ("drive".equals(photo.optString("origin"))) ignoreDriveSource(photo.optString("sourceKey"));
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

  private JSONObject copyToPrivateLibrary(Uri uri, String sourceKey) {
    String mimeType = activity.getContentResolver().getType(uri);
    String name = displayName(uri);
    if (!isImage(mimeType, name)) return null;
    long size = sizeOf(uri);
    if (sourceKey.startsWith("drive:") && size > MAX_DRIVE_PHOTO_BYTES) {
      Log.w(TAG, "Skipping oversized Drive photo: " + name);
      return null;
    }
    String extension = extensionFor(mimeType, name);
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
      photo.put("name", name);
      photo.put("origin", sourceKey.startsWith("drive:") ? "drive" : "device");
      photo.put("sourceKey", sourceKey);
      return photo;
    } catch (Exception error) {
      destination.delete();
      Log.w(TAG, "Could not copy selected photo", error);
      return null;
    }
  }

  private void synchronizeDriveFolder() {
    String stored = preferences.getString(KEY_DRIVE_FOLDER_URI, "");
    if (stored.isEmpty()) {
      sendDriveStatus("setup", "Choose one Google Drive folder that contains your photos.");
      return;
    }
    sendDriveStatus("syncing", "Syncing image files from the selected Drive folder.");
    try {
      Uri treeUri = Uri.parse(stored);
      ArrayList<Uri> files = imageFilesInTree(treeUri);
      Set<String> existing = existingDriveSources();
      Set<String> ignored = ignoredDriveSources();
      JSONArray current = library();
      JSONArray next = new JSONArray();
      int added = 0;
      for (Uri file : files) {
        if (added >= MAX_DRIVE_PHOTOS_PER_SYNC) break;
        String sourceKey = "drive:" + file;
        if (existing.contains(sourceKey) || ignored.contains(sourceKey)) continue;
        JSONObject photo = copyToPrivateLibrary(file, sourceKey);
        if (photo != null) {
          next.put(photo);
          added++;
        }
      }
      for (int index = 0; index < current.length(); index++) next.put(current.opt(index));
      if (added > 0) {
        save(next);
        sendLibrary(next);
      }
      int count = drivePhotoCount(added > 0 ? next : current);
      String suffix = files.size() > MAX_DRIVE_PHOTOS_PER_SYNC ? " The first " + MAX_DRIVE_PHOTOS_PER_SYNC + " are available." : "";
      sendDriveStatus("connected", count + " photo" + (count == 1 ? "" : "s") + " available from " + driveFolderName() + "." + suffix);
    } catch (SecurityException error) {
      Log.w(TAG, "Drive folder permission was lost", error);
      sendDriveStatus("error", "MITRA no longer has access to that folder. Choose it again to reconnect.");
    } catch (Exception error) {
      Log.w(TAG, "Could not sync Drive photos", error);
      sendDriveStatus("error", "MITRA could not read the selected Drive folder. Check your connection and try again.");
    }
  }

  private ArrayList<Uri> imageFilesInTree(Uri treeUri) {
    ArrayList<Uri> files = new ArrayList<>();
    String parentId = DocumentsContract.getTreeDocumentId(treeUri);
    Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentId);
    String[] projection = new String[] { Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE };
    try (Cursor cursor = activity.getContentResolver().query(children, projection, null, null, null)) {
      if (cursor == null) return files;
      int idColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DOCUMENT_ID);
      int nameColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_DISPLAY_NAME);
      int mimeColumn = cursor.getColumnIndexOrThrow(Document.COLUMN_MIME_TYPE);
      while (cursor.moveToNext()) {
        String documentId = cursor.getString(idColumn);
        String name = cursor.getString(nameColumn);
        String mimeType = cursor.getString(mimeColumn);
        if (documentId == null || Document.MIME_TYPE_DIR.equals(mimeType) || !isImage(mimeType, name)) continue;
        files.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, documentId));
      }
    }
    return files;
  }

  private boolean hasDriveFolder() {
    return !preferences.getString(KEY_DRIVE_FOLDER_URI, "").isEmpty();
  }

  private String driveFolderName() {
    String name = preferences.getString(KEY_DRIVE_FOLDER_NAME, "").trim();
    return name.isEmpty() ? "your selected Drive folder" : name;
  }

  private JSONObject driveStatusPayload(String state, String message) {
    try {
      JSONObject payload = new JSONObject();
      payload.put("state", state);
      payload.put("folderName", driveFolderName());
      payload.put("count", drivePhotoCount(library()));
      payload.put("message", message);
      return payload;
    } catch (JSONException ignored) {
      return new JSONObject();
    }
  }

  private void sendDriveStatus(String state, String message) {
    String payload = driveStatusPayload(state, message).toString();
    String script = "window.mitraDrivePhotoStatus && window.mitraDrivePhotoStatus(" + JSONObject.quote(payload) + ");";
    webView.post(() -> webView.evaluateJavascript(script, null));
  }

  private int drivePhotoCount(JSONArray photos) {
    int count = 0;
    for (int index = 0; index < photos.length(); index++) {
      JSONObject photo = photos.optJSONObject(index);
      if (photo != null && "drive".equals(photo.optString("origin"))) count++;
    }
    return count;
  }

  private Set<String> existingDriveSources() {
    Set<String> values = new HashSet<>();
    JSONArray photos = library();
    for (int index = 0; index < photos.length(); index++) {
      JSONObject photo = photos.optJSONObject(index);
      if (photo == null || !"drive".equals(photo.optString("origin"))) continue;
      String source = photo.optString("sourceKey", "");
      if (!source.isEmpty()) values.add(source);
    }
    return values;
  }

  private Set<String> ignoredDriveSources() {
    Set<String> values = new HashSet<>();
    try {
      JSONArray stored = new JSONArray(preferences.getString(KEY_IGNORED_DRIVE_SOURCES, "[]"));
      for (int index = 0; index < stored.length(); index++) {
        String source = stored.optString(index, "");
        if (source.startsWith("drive:")) values.add(source);
      }
    } catch (JSONException ignored) { }
    return values;
  }

  private void ignoreDriveSource(String source) {
    if (source == null || !source.startsWith("drive:")) return;
    Set<String> sources = ignoredDriveSources();
    sources.add(source);
    JSONArray saved = new JSONArray();
    for (String item : sources) saved.put(item);
    preferences.edit().putString(KEY_IGNORED_DRIVE_SOURCES, saved.toString()).apply();
  }

  private void clearDrivePhotos() {
    JSONArray previous = library();
    JSONArray next = new JSONArray();
    for (int index = 0; index < previous.length(); index++) {
      JSONObject photo = previous.optJSONObject(index);
      if (photo == null) continue;
      if ("drive".equals(photo.optString("origin"))) {
        String fileName = photo.optString("file");
        if (isSafeFileName(fileName)) new File(photoDirectory(), fileName).delete();
      } else {
        next.put(photo);
      }
    }
    save(next);
    sendLibrary(next);
  }

  private boolean isImage(String mimeType, String name) {
    if (mimeType != null && mimeType.toLowerCase(Locale.ROOT).startsWith("image/")) return true;
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png") || lower.endsWith(".webp") || lower.endsWith(".gif") || lower.endsWith(".heic") || lower.endsWith(".heif");
  }

  private long sizeOf(Uri uri) {
    try (Cursor cursor = activity.getContentResolver().query(uri, new String[] { OpenableColumns.SIZE }, null, null, null)) {
      if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) return cursor.getLong(0);
    } catch (Exception ignored) { }
    return 0L;
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
    String lower = displayName == null ? "" : displayName.toLowerCase(Locale.ROOT);
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

  void shutdown() { executor.shutdownNow(); }
}
