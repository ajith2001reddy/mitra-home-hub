package com.mitrahome.hub;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.Manifest;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.webkit.ConsoleMessage;
import android.webkit.PermissionRequest;
import android.webkit.RenderProcessGoneDetail;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.IOException;
import java.io.InputStream;

public final class MainActivity extends Activity {
  private static final int CAMERA_PERMISSION_REQUEST = 41;
  private static final int MICROPHONE_PERMISSION_REQUEST = 42;
  private HomeAssistantBridge homeAssistantBridge;
  private GmailBridge gmailBridge;
  private MitraBrainBridge mitraBrainBridge;
  private VoiceBridge voiceBridge;
  private PhotoLibraryBridge photoLibraryBridge;
  private PermissionRequest pendingCameraRequest;
  private WebView dashboard;
  private boolean activityDestroyed;

  @SuppressLint("SetJavaScriptEnabled")
  @Override
  public void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    WebView.setWebContentsDebuggingEnabled(false);
    getWindow().setStatusBarColor(Color.TRANSPARENT);
    getWindow().setNavigationBarColor(Color.rgb(5, 11, 20));
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow().getDecorView().setSystemUiVisibility(
      View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
      View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
      View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
      View.SYSTEM_UI_FLAG_FULLSCREEN |
      View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
    );

    createDashboard();
  }

  @SuppressLint("SetJavaScriptEnabled")
  private void createDashboard() {
    dashboard = new WebView(this);
    WebSettings settings = dashboard.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setUserAgentString(settings.getUserAgentString() + " JarvisHomeNative");
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setAllowFileAccessFromFileURLs(false);
    settings.setAllowUniversalAccessFromFileURLs(false);
    settings.setCacheMode(WebSettings.LOAD_NO_CACHE);
    settings.setOffscreenPreRaster(false);
    dashboard.setBackgroundColor(Color.rgb(5, 11, 20));
    homeAssistantBridge = new HomeAssistantBridge(this, dashboard);
    gmailBridge = new GmailBridge(this, dashboard);
    mitraBrainBridge = new MitraBrainBridge(this, dashboard, homeAssistantBridge);
    voiceBridge = new VoiceBridge(this, dashboard);
    photoLibraryBridge = new PhotoLibraryBridge(this, dashboard);
    // ChromeOS on this Duet has a reproducible Vulkan driver abort in the
    // WebView render thread. MITRA deliberately uses the software path so it
    // can remain on screen continuously without invoking that driver.
    dashboard.setLayerType(View.LAYER_TYPE_SOFTWARE, null);
    dashboard.addJavascriptInterface(homeAssistantBridge, "JarvisNative");
    dashboard.addJavascriptInterface(gmailBridge, "MitraGoogle");
    // The phone is a Home Assistant controller only. MITRA Brain, its local
    // history, and its API key are exposed solely on the Duet-sized hub screen.
    if (isLenovoHubScreen()) {
      dashboard.addJavascriptInterface(mitraBrainBridge, "MitraBrain");
      dashboard.addJavascriptInterface(voiceBridge, "MitraVoice");
      dashboard.addJavascriptInterface(photoLibraryBridge, "MitraPhotos");
    }
    dashboard.setWebChromeClient(new WebChromeClient() {
      @Override
      public void onPermissionRequest(final PermissionRequest request) {
        runOnUiThread(() -> {
          if (!"appassets.androidplatform.net".equals(request.getOrigin().getHost()) || !requestsCamera(request)) {
            request.deny();
            return;
          }
          if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            request.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
            return;
          }
          pendingCameraRequest = request;
          requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION_REQUEST);
        });
      }

      @Override
      public void onPermissionRequestCanceled(PermissionRequest request) {
        if (pendingCameraRequest == request) pendingCameraRequest = null;
      }

      @Override
      public boolean onConsoleMessage(ConsoleMessage message) {
        Log.e("JarvisWeb", message.message() + " @ " + message.sourceId() + ":" + message.lineNumber());
        return true;
      }
    });
    dashboard.setWebViewClient(new WebViewClient() {
      @Override
      public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        // Keep trusted MITRA assets in this WebView. Any external web-search
        // citation opens in the device browser, where native interfaces do not exist.
        if ("appassets.androidplatform.net".equals(url.getHost())) return false;
        try {
          startActivity(new Intent(Intent.ACTION_VIEW, url));
        } catch (Exception error) {
          Log.w("JarvisWeb", "Could not open external link", error);
        }
        return true;
      }

      @Override
      public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
        Uri url = request.getUrl();
        if (!"appassets.androidplatform.net".equals(url.getHost())) {
          return super.shouldInterceptRequest(view, request);
        }
        String path = url.getPath();
        if (path != null && path.startsWith("/personal-photos/")) {
          WebResourceResponse photo = photoLibraryBridge.openPersonalPhoto(path.substring("/personal-photos/".length()));
          return photo != null ? photo : super.shouldInterceptRequest(view, request);
        }
        if (path == null || !path.startsWith("/assets/")) {
          return super.shouldInterceptRequest(view, request);
        }
        String assetPath = path.substring("/assets/".length());
        try {
          InputStream stream = getAssets().open(assetPath);
          return new WebResourceResponse(mimeType(assetPath), "UTF-8", stream);
        } catch (IOException error) {
          Log.e("JarvisWeb", "Missing bundled asset: " + assetPath, error);
          return super.shouldInterceptRequest(view, request);
        }
      }

      @Override
      public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
        Log.e("JarvisWeb", "Load error " + errorCode + ": " + description + " @ " + failingUrl);
      }

      @Override
      public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
        Log.w("JarvisWeb", "WebView renderer exited; restoring MITRA dashboard. didCrash=" + detail.didCrash());
        runOnUiThread(MainActivity.this::recreateDashboard);
        return true;
      }
    });
    dashboard.loadUrl("https://appassets.androidplatform.net/assets/www/index.html");
    setContentView(dashboard);
  }

  private void recreateDashboard() {
    if (activityDestroyed || isFinishing()) return;
    pendingCameraRequest = null;
    disposeBridges();
    if (dashboard != null) {
      dashboard.destroy();
      dashboard = null;
    }
    createDashboard();
  }

  private void disposeBridges() {
    if (homeAssistantBridge != null) homeAssistantBridge.shutdown();
    if (gmailBridge != null) gmailBridge.shutdown();
    if (mitraBrainBridge != null) mitraBrainBridge.shutdown();
    if (voiceBridge != null) voiceBridge.shutdown();
    homeAssistantBridge = null;
    gmailBridge = null;
    mitraBrainBridge = null;
    voiceBridge = null;
    photoLibraryBridge = null;
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (requestCode == MICROPHONE_PERMISSION_REQUEST) {
      boolean granted = grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED;
      if (voiceBridge != null) voiceBridge.onMicrophonePermissionResult(granted);
      return;
    }
    if (requestCode != CAMERA_PERMISSION_REQUEST || pendingCameraRequest == null) return;
    PermissionRequest cameraRequest = pendingCameraRequest;
    pendingCameraRequest = null;
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      cameraRequest.grant(new String[] { PermissionRequest.RESOURCE_VIDEO_CAPTURE });
    } else {
      cameraRequest.deny();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, Intent data) {
    super.onActivityResult(requestCode, resultCode, data);
    if (photoLibraryBridge != null && photoLibraryBridge.handleActivityResult(requestCode, resultCode, data)) return;
    if (gmailBridge != null && gmailBridge.handleActivityResult(requestCode, data)) return;
  }

  private boolean requestsCamera(PermissionRequest request) {
    for (String resource : request.getResources()) {
      if (PermissionRequest.RESOURCE_VIDEO_CAPTURE.equals(resource)) return true;
    }
    return false;
  }

  private boolean isLenovoHubScreen() {
    Configuration configuration = getResources().getConfiguration();
    return configuration.smallestScreenWidthDp >= 600;
  }

  void requestVoiceMicrophonePermission() {
    if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
      if (voiceBridge != null) voiceBridge.beginListening();
      return;
    }
    requestPermissions(new String[] { Manifest.permission.RECORD_AUDIO }, MICROPHONE_PERMISSION_REQUEST);
  }

  @Override
  protected void onDestroy() {
    activityDestroyed = true;
    pendingCameraRequest = null;
    disposeBridges();
    if (dashboard != null) {
      dashboard.destroy();
      dashboard = null;
    }
    super.onDestroy();
  }

  private String mimeType(String path) {
    if (path.endsWith(".html")) return "text/html";
    if (path.endsWith(".js")) return "application/javascript";
    if (path.endsWith(".css")) return "text/css";
    if (path.endsWith(".svg")) return "image/svg+xml";
    if (path.endsWith(".webmanifest")) return "application/manifest+json";
    return "application/octet-stream";
  }

  @Override
  public void onBackPressed() {
    if (dashboard != null && dashboard.canGoBack()) {
      dashboard.goBack();
    } else {
      super.onBackPressed();
    }
  }
}
