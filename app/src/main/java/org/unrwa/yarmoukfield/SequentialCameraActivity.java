package org.unrwa.yarmoukfield;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Size;
import android.view.Gravity;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * In-app continuous camera (Camera2). Unlike the system ACTION_IMAGE_CAPTURE flow, each shot is saved
 * immediately with NO per-photo accept/reject screen — the preview stays live so a whole room/house can be
 * photographed in one rhythm (شكوى المستخدم: «اجعل الصور متتابعة لا أريد خيار صح وخطأ»). Every captured JPEG is
 * written to a temp file in the app cache; the list of paths is returned to the caller, which copies them into
 * the beneficiary's SAF photo folder and the database. Back / «تم» both return whatever was shot so far.
 */
public final class SequentialCameraActivity extends Activity {
    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_PATHS = "captured_paths";
    private static final int REQ_CAMERA = 7001;

    private AutoFitTextureView textureView;
    private TextView counterView;
    private Button shutter, flashBtn;

    private CameraManager cameraManager;
    private String cameraId;
    private CameraCharacteristics characteristics;
    private CameraDevice cameraDevice;
    private CameraCaptureSession captureSession;
    private CaptureRequest.Builder previewBuilder;   // kept so the flash/torch toggle can re-issue the preview
    private ImageReader imageReader;
    private Size previewSize, jpegSize;
    private HandlerThread bgThread;
    private Handler bgHandler;
    private boolean hasFlash, flashOn;
    private int sensorOrientation;

    private final ArrayList<String> capturedPaths = new ArrayList<>();
    private File shotDir;
    private volatile boolean capturing;

    /** TextureView that measures itself to the camera preview's aspect ratio, so the live preview is never
     * stretched/distorted — it appears with natural proportions (letterboxed if needed) instead of squashed. */
    static final class AutoFitTextureView extends TextureView {
        private int ratioW = 0, ratioH = 0;
        AutoFitTextureView(Context c) { super(c); }
        void setAspectRatio(int w, int h) { ratioW = w; ratioH = h; requestLayout(); }
        @Override protected void onMeasure(int widthSpec, int heightSpec) {
            super.onMeasure(widthSpec, heightSpec);
            int w = getMeasuredWidth(), h = getMeasuredHeight();
            if (ratioW == 0 || ratioH == 0) { setMeasuredDimension(w, h); return; }
            // Fit inside the available space keeping the preview aspect ratio (portrait: ratioW<ratioH).
            if (w < (long) h * ratioW / ratioH) setMeasuredDimension(w, w * ratioH / ratioW);
            else setMeasuredDimension(h * ratioW / ratioH, h);
        }
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        // Lock portrait so the preview transform and the saved-photo rotation are deterministic on every device.
        setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        setContentView(buildUi());
        shotDir = new File(getExternalCacheDir(), "seqshots_" + System.currentTimeMillis());
        shotDir.mkdirs();
        cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        textureView = new AutoFitTextureView(this);
        FrameLayout.LayoutParams texP = new FrameLayout.LayoutParams(-1, -1);
        texP.gravity = Gravity.CENTER;                 // centre the aspect-correct preview
        root.addView(textureView, texP);
        textureView.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(SurfaceTexture s, int w, int h) { openCameraIfReady(); }
            @Override public void onSurfaceTextureSizeChanged(SurfaceTexture s, int w, int h) {}
            @Override public boolean onSurfaceTextureDestroyed(SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(SurfaceTexture s) {}
        });

        int pad = dp(14);
        // Top banner: title + running count
        counterView = new TextView(this);
        counterView.setTextColor(Color.WHITE);
        counterView.setTextSize(16);
        counterView.setPadding(pad, pad, pad, pad);
        counterView.setBackgroundColor(0x99000000);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        counterView.setText((title == null ? "التقاط الصور" : title) + "\nعدد الصور: 0  —  التقط بلا توقف، ثم «تم»");
        FrameLayout.LayoutParams tp = new FrameLayout.LayoutParams(-1, -2);
        tp.gravity = Gravity.TOP;
        root.addView(counterView, tp);

        // Flash / torch toggle — a steady light that also lights the shot (works as capture flash). Hidden on
        // devices with no flash unit. Placed bottom-end above the shutter bar so it's easy to reach.
        flashBtn = new Button(this);
        flashBtn.setText("⚡ فلاش: إيقاف");
        flashBtn.setAllCaps(false);
        flashBtn.setTextColor(Color.WHITE);
        flashBtn.setBackgroundColor(0x99000000);
        flashBtn.setVisibility(View.GONE);
        flashBtn.setOnClickListener(v -> toggleFlash());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(-2, -2);
        fp.gravity = Gravity.TOP | Gravity.END;
        fp.setMargins(0, dp(90), dp(14), 0);
        root.addView(flashBtn, fp);

        // Bottom bar: cancel · shutter · done
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(pad, pad, pad, pad * 2);
        bar.setBackgroundColor(0x99000000);
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(-1, -2);
        bp.gravity = Gravity.BOTTOM;

        Button cancel = new Button(this);
        cancel.setText("إلغاء");
        cancel.setAllCaps(false);
        cancel.setOnClickListener(v -> { capturedPaths.clear(); finishWithResult(); });
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(cancel, cp);

        shutter = new Button(this);
        shutter.setText("● التقاط");
        shutter.setAllCaps(false);
        shutter.setTextSize(18);
        shutter.setOnClickListener(v -> takePicture());
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(64), 2f);
        int m = dp(8); sp.setMargins(m, 0, m, 0);
        bar.addView(shutter, sp);

        Button done = new Button(this);
        done.setText("تم");
        done.setAllCaps(false);
        done.setOnClickListener(v -> finishWithResult());
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(0, -2, 1f);
        bar.addView(done, dp2);

        root.addView(bar, bp);
        return root;
    }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] grants) {
        super.onRequestPermissionsResult(code, perms, grants);
        if (code == REQ_CAMERA) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) openCameraIfReady();
            else { toast("لم تُمنح صلاحية الكاميرا"); finishWithResult(); }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        startBgThread();
        if (textureView.isAvailable()) openCameraIfReady();
    }

    @Override protected void onPause() {
        closeCamera();
        stopBgThread();
        super.onPause();
    }

    @Override public void onBackPressed() { finishWithResult(); }

    private void openCameraIfReady() {
        if (cameraDevice != null || !textureView.isAvailable()) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            cameraId = pickBackCamera();
            if (cameraId == null) { toast("لا توجد كاميرا خلفية على الجهاز"); finishWithResult(); return; }
            characteristics = cameraManager.getCameraCharacteristics(cameraId);
            Integer so = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION); sensorOrientation = so == null ? 90 : so;
            Boolean fa = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE); hasFlash = fa != null && fa;
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            jpegSize = chooseSize(Arrays.asList(map.getOutputSizes(ImageFormat.JPEG)), 1920);
            previewSize = chooseSize(Arrays.asList(map.getOutputSizes(SurfaceTexture.class)), 1280);
            // Portrait: the preview buffer is landscape (W>H); give the AutoFitTextureView the SWAPPED aspect so
            // the live view keeps natural proportions instead of being squashed.
            textureView.setAspectRatio(previewSize.getHeight(), previewSize.getWidth());
            runOnUiThread(() -> flashBtn.setVisibility(hasFlash ? View.VISIBLE : View.GONE));
            imageReader = ImageReader.newInstance(jpegSize.getWidth(), jpegSize.getHeight(), ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(onImage, bgHandler);
            cameraManager.openCamera(cameraId, deviceCallback, bgHandler);
        } catch (Exception e) { toast("تعذر فتح الكاميرا: " + e.getMessage()); finishWithResult(); }
    }

    private String pickBackCamera() throws CameraAccessException {
        for (String id : cameraManager.getCameraIdList()) {
            Integer facing = cameraManager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
        }
        String[] ids = cameraManager.getCameraIdList();
        return ids.length > 0 ? ids[0] : null;
    }

    private final CameraDevice.StateCallback deviceCallback = new CameraDevice.StateCallback() {
        @Override public void onOpened(CameraDevice device) { cameraDevice = device; startPreview(); }
        @Override public void onDisconnected(CameraDevice device) { device.close(); cameraDevice = null; }
        @Override public void onError(CameraDevice device, int error) { device.close(); cameraDevice = null; runOnUiThread(() -> { toast("خطأ كاميرا: " + error); finishWithResult(); }); }
    };

    private void startPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            texture.setDefaultBufferSize(previewSize.getWidth(), previewSize.getHeight());
            Surface previewSurface = new Surface(texture);
            previewBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            previewBuilder.addTarget(previewSurface);
            previewBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            applyFlashToPreview();
            cameraDevice.createCaptureSession(Arrays.asList(previewSurface, imageReader.getSurface()),
                new CameraCaptureSession.StateCallback() {
                    @Override public void onConfigured(CameraCaptureSession session) {
                        if (cameraDevice == null) return;
                        captureSession = session;
                        try { session.setRepeatingRequest(previewBuilder.build(), null, bgHandler); }
                        catch (Exception e) { runOnUiThread(() -> toast("تعذر تشغيل المعاينة")); }
                    }
                    @Override public void onConfigureFailed(CameraCaptureSession session) { runOnUiThread(() -> { toast("تعذر تهيئة الكاميرا"); finishWithResult(); }); }
                }, bgHandler);
        } catch (Exception e) { toast("تعذر بدء المعاينة: " + e.getMessage()); finishWithResult(); }
    }

    /** Torch-style flash: a steady light that stays on for both preview and the shot. Simpler and more reliable
     * across devices than pre-capture AE flash sequences, and it doubles as a work light in dark rooms. */
    private void toggleFlash() {
        if (!hasFlash) return;
        flashOn = !flashOn;
        flashBtn.setText(flashOn ? "⚡ فلاش: تشغيل" : "⚡ فلاش: إيقاف");
        try {
            if (previewBuilder != null && captureSession != null) {
                applyFlashToPreview();
                captureSession.setRepeatingRequest(previewBuilder.build(), null, bgHandler);
            }
        } catch (Exception ignored) {}
    }
    private void applyFlashToPreview() {
        if (previewBuilder == null) return;
        previewBuilder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
        previewBuilder.set(CaptureRequest.FLASH_MODE, flashOn ? CaptureRequest.FLASH_MODE_TORCH : CaptureRequest.FLASH_MODE_OFF);
    }

    private void takePicture() {
        if (cameraDevice == null || captureSession == null || capturing) return;
        capturing = true;
        runOnUiThread(() -> shutter.setEnabled(false));
        try {
            CaptureRequest.Builder still = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            still.addTarget(imageReader.getSurface());
            still.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
            still.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON);
            if (flashOn) still.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH); // steady flash lights the shot
            // Deterministic orientation: ask the HAL for NO rotation (0) so the JPEG is always sensor-native, then
            // we rotate the pixels ourselves by SENSOR_ORIENTATION in the save path. This makes every device produce
            // the SAME upright photo — no reliance on the device honoring JPEG_ORIENTATION or writing an EXIF tag.
            still.set(CaptureRequest.JPEG_ORIENTATION, 0);
            captureSession.capture(still.build(), null, bgHandler);
        } catch (Exception e) {
            capturing = false;
            runOnUiThread(() -> { shutter.setEnabled(true); toast("تعذر الالتقاط: " + e.getMessage()); });
        }
    }

    private final ImageReader.OnImageAvailableListener onImage = reader -> {
        try (Image image = reader.acquireLatestImage()) {
            if (image != null) {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                byte[] bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                File out = new File(shotDir, "shot_" + System.currentTimeMillis() + "_" + capturedPaths.size() + ".jpg");
                try (FileOutputStream fos = new FileOutputStream(out)) { fos.write(bytes); }
                rotateUpright(out);
                capturedPaths.add(out.getAbsolutePath());
            }
        } catch (Exception ignored) {
        } finally {
            capturing = false;
            runOnUiThread(() -> { shutter.setEnabled(true); updateCounter(); });
        }
    };

    /** Camera photo orientation — DETERMINISTIC. Because takePicture() asks for JPEG_ORIENTATION=0, the saved JPEG
     * is always sensor-native (landscape). We rotate the PIXELS by SENSOR_ORIENTATION (portrait-locked activity, so
     * display rotation is 0) and write EXIF=NORMAL, so the file is upright everywhere — in-app, in the beneficiary
     * package, and on a desktop PC — with no reliance on any device's EXIF/HAL behaviour. This is what fixes the
     * «الصور تتصدر ملتفّة» complaint. Imported Excel reference photos are never touched by this method. */
    private void rotateUpright(File file) {
        int degrees = ((sensorOrientation % 360) + 360) % 360;
        try {
            android.graphics.Bitmap src = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
            if (src == null) return;
            android.graphics.Bitmap fixed = src;
            if (degrees != 0) {
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                matrix.postRotate(degrees);
                fixed = android.graphics.Bitmap.createBitmap(src, 0, 0, src.getWidth(), src.getHeight(), matrix, true);
            }
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fixed.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, fos);
            }
            if (fixed != src) src.recycle();
            fixed.recycle();
            android.media.ExifInterface exif = new android.media.ExifInterface(file.getAbsolutePath());
            exif.setAttribute(android.media.ExifInterface.TAG_ORIENTATION, String.valueOf(android.media.ExifInterface.ORIENTATION_NORMAL));
            exif.saveAttributes();
        } catch (Exception ignored) {
            // Best-effort: on failure the original file is left as-is rather than lost.
        }
    }

    private void updateCounter() {
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        counterView.setText((title == null ? "التقاط الصور" : title) + "\nعدد الصور: " + capturedPaths.size() + "  —  التقط بلا توقف، ثم «تم»");
    }

    private void finishWithResult() {
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_PATHS, capturedPaths);
        setResult(RESULT_OK, data);
        finish();
    }

    private void closeCamera() {
        try { if (captureSession != null) { captureSession.close(); captureSession = null; } } catch (Exception ignored) {}
        try { if (cameraDevice != null) { cameraDevice.close(); cameraDevice = null; } } catch (Exception ignored) {}
        try { if (imageReader != null) { imageReader.close(); imageReader = null; } } catch (Exception ignored) {}
    }

    private void startBgThread() { bgThread = new HandlerThread("seqcam"); bgThread.start(); bgHandler = new Handler(bgThread.getLooper()); }
    private void stopBgThread() {
        if (bgThread == null) return;
        bgThread.quitSafely();
        try { bgThread.join(); } catch (InterruptedException ignored) {}
        bgThread = null; bgHandler = null;
    }

    /** Largest available size whose longer edge is ≤ target (keeps files field-sized); falls back to the smallest. */
    private static Size chooseSize(List<Size> sizes, int targetLong) {
        List<Size> ok = new ArrayList<>();
        for (Size s : sizes) if (Math.max(s.getWidth(), s.getHeight()) <= targetLong) ok.add(s);
        if (!ok.isEmpty()) return Collections.max(ok, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
        return Collections.min(sizes, Comparator.comparingLong(s -> (long) s.getWidth() * s.getHeight()));
    }

    private int dp(int v) { return Math.round(getResources().getDisplayMetrics().density * v); }
    private void toast(String m) { runOnUiThread(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
}
