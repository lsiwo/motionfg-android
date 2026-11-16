package dev.lsiwo.motionfg;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;
import android.view.Display;
import android.view.Surface;
import android.widget.Toast;

public final class CaptureService extends Service {
    static final String CONSENT = "projection_consent";
    static final String FPS = "base_fps";
    static final String COUNTER = "fps_counter";
    private static final String STOP = "dev.lsiwo.motionfg.STOP";
    private static final String CHANNEL = "motion_fg_session";
    private static final int NOTIFICATION = 41;

    static boolean running;

    private final Handler main = new Handler(Looper.getMainLooper());
    private MotionEngine engine;
    private MediaProjection projection;
    private VirtualDisplay capture;
    private Surface captureSurface;
    private boolean visible, reqHdr, closing, overlayShown;
    private RenderSettings settings;
    private ColorSignal capturedColor;
    private int outputColorMode = -1;
    private String colorStatus = "";
    private int sizeRequest;
    private int density;
    private int contentWidth, contentHeight, captureWidth, captureHeight;
    private CaptureGeometry captureGeometry;
    private final Runnable watchOutput = this::watchOverlayOutput;

    private final MediaProjection.Callback projectionEvents = new MediaProjection.Callback() {
        @Override public void onStop() {
            stopSelf();
        }

        @Override public void onCapturedContentResize(int width, int height) {
            if (!closing && width > 0 && height > 0) configureCapture(width, height);
        }

        @Override public void onCapturedContentVisibilityChanged(boolean shown) {
            visible = shown;
            updateOverlay();
        }
    };

    @Override
    public int onStartCommand(Intent request, int flags, int startId) {
        if (request == null || STOP.equals(request.getAction())) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (engine != null) return START_NOT_STICKY;
        try {
            launchNotification();
            Intent permission = request.getParcelableExtra(CONSENT, Intent.class);
            if (permission == null || MotionOverlay.active == null) {
                stopWithMessage("Включи службу слоя вывода и заново выбери игру.");
                return START_NOT_STICKY;
            }
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            projection = manager.getMediaProjection(Activity.RESULT_OK, permission);
            if (projection == null) throw new IllegalStateException("Android не разрешил захват.");
            projection.registerCallback(projectionEvents, main);

            settings = RenderSettings.fromIntent(request);
            reqHdr = settings.hdr == RenderSettings.HDR10;
            Display display = getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
            density = getResources().getConfiguration().densityDpi;
            engine = new MotionEngine(getAssets(), settings, false, ColorSignal.displayHdr10(display),
                    new MotionEngine.Listener() {
                @Override public void failed(String reason) {
                    main.post(() -> stopWithMessage(reason));
                }
                @Override public void rates(int game, int output) {
                    main.post(() -> {
                        if (!closing && MotionOverlay.active != null) {
                            MotionOverlay.active.updateRates(game, output);
                        }
                    });
                }
                @Override public void outputChanged() {
                    main.post(CaptureService.this::updateOverlayOutput);
                }
                @Override public void colorMode(int mode) {
                    main.post(() -> {
                        if (closing) return;
                        outputColorMode = mode;
                        updateColorStatus();
                    });
                }

                @Override public void inputColor(ColorSignal signal) {
                    main.post(() -> {
                        if (closing) return;
                        capturedColor = signal;
                        updateColorStatus();
                    });
                }
            });
            android.graphics.Rect bounds = getSystemService(android.view.WindowManager.class)
                    .getMaximumWindowMetrics().getBounds();
            configureCapture(bounds.width(), bounds.height());
            running = true;
            setStatus("Обработка запущена");
        } catch (RuntimeException error) {
            Log.e("MotionFG", "Cannot start capture", error);
            stopWithMessage("Не удалось запустить захват. Повтори выбор приложения.");
        }
        return START_NOT_STICKY;
    }

    private void updateColorStatus() {
        if (outputColorMode < 0) return;
        String from = capturedColor == null ? "" : capturedColor.label() + " → ";
        String line = from + ColorSignal.outputLabel(outputColorMode);
        if (reqHdr && outputColorMode == GlContext.SDR) line += " · HDR недоступен для вывода";
        if (line.equals(colorStatus)) return;
        colorStatus = line;
        if (MotionOverlay.active != null) MotionOverlay.active.updateColorStatus(line);
        setStatus(reqHdr ? line : "Обработка запущена");
    }

    private void configureCapture(int width, int height) {
        if (engine == null || projection == null) return;
        contentWidth = width;
        contentHeight = height;
        int request = ++sizeRequest;
        engine.setSourceSize(width, height, (surface, size, resized) -> main.post(() -> {
            if (closing || request != sizeRequest || projection == null || !surface.isValid()) return;
            try {
                int w = size.bufferWidth, h = size.bufferHeight;
                if (capture == null) {
                    capture = projection.createVirtualDisplay("Motion FG", w, h, density,
                            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, surface, null, main);
                    if (capture == null) throw new IllegalStateException("Нет виртуального экрана");
                } else {
                    if (w != captureWidth || h != captureHeight) capture.resize(w, h, density);
                    if (captureSurface != surface) capture.setSurface(surface);
                }
                captureSurface = surface;
                captureWidth = w;
                captureHeight = h;
                captureGeometry = size;
                resized.run();
                if (overlayShown && MotionOverlay.active != null) MotionOverlay.active.updateCaptureSize(size);
            } catch (RuntimeException error) {
                Log.e("MotionFG", "Cannot attach virtual display", error);
                stopWithMessage("Android не создал экран захвата. Повтори выбор приложения.");
            }
        }));
    }

    private void updateLiveSettings(RenderSettings changed) {
        if (closing || engine == null) return;
        boolean resized = changed.profile != settings.profile || changed.canvasWidth != settings.canvasWidth
                || changed.canvasHeight != settings.canvasHeight;
        SharedPreferences.Editor p = getSharedPreferences("settings", MODE_PRIVATE).edit();
        p.putInt(RenderSettings.PROFILE, changed.profile.ordinal()).putInt(FPS, changed.fps)
                .putInt(RenderSettings.FACTOR, changed.factor)
                .putBoolean(RenderSettings.VSYNC, changed.vsync).putBoolean(RenderSettings.LOW_LATENCY, changed.lowLatency)
                .putInt(RenderSettings.SDR_WHITE, changed.sdrWhite).apply();
        settings = changed;
        engine.setLiveSettings(settings);
        if (resized && contentWidth > 0 && contentHeight > 0) configureCapture(contentWidth, contentHeight);
    }

    private void updateOverlay() {
        if (closing || engine == null) return;
        MotionOverlay overlay = MotionOverlay.active;
        if (overlay == null) {
            stopWithMessage("Служба слоя вывода отключена.");
            return;
        }
        try {
            if (visible && !overlayShown) {
                overlay.show(engine, settings, this::updateLiveSettings);
                if (captureGeometry != null) overlay.updateCaptureSize(captureGeometry);
                overlay.updateColorStatus(colorStatus);
                overlayShown = true;
                watchOverlayOutput();
            } else if (!visible && overlayShown) {
                main.removeCallbacks(watchOutput);
                overlay.hide();
                overlayShown = false;
            }
        } catch (RuntimeException error) {
            Log.e("MotionFG", "Cannot show overlay", error);
            stopWithMessage("Прошивка не разрешила создать слой вывода.");
        }
    }

    private void watchOverlayOutput() {
        if (closing || !overlayShown) return;
        updateOverlayOutput();
        main.postDelayed(watchOutput, 100);
    }

    private void updateOverlayOutput() {
        MotionOverlay overlay = MotionOverlay.active;
        if (!closing && overlayShown && engine != null && overlay != null) {
            overlay.setOutputReady(engine.outputUsable());
        }
    }

    private void launchNotification() {
        getSystemService(NotificationManager.class).createNotificationChannel(
                new NotificationChannel(CHANNEL, "Генерация кадров", NotificationManager.IMPORTANCE_LOW));
        int pendingFlags = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
        PendingIntent open = PendingIntent.getActivity(this, 1, new Intent(this, MainActivity.class), pendingFlags);
        PendingIntent stop = PendingIntent.getService(this, 2,
                new Intent(this, CaptureService.class).setAction(STOP), pendingFlags);
        Notification notification = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_motion)
                .setContentTitle("Motion FG запущен")
                .setContentText("Кадры обрабатываются на устройстве")
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, "Остановить", stop).build())
                .setOnlyAlertOnce(true).setOngoing(true).build();
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
    }

    private void stopWithMessage(String message) {
        if (closing) return;
        setStatus(message);
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        stopSelf();
    }

    private void setStatus(String message) {
        getSharedPreferences("session", MODE_PRIVATE).edit().putString("status", message).apply();
    }

    @Override
    public void onDestroy() {
        closing = true;
        running = false;
        main.removeCallbacksAndMessages(null);
        if (MotionOverlay.active != null) MotionOverlay.active.hide();
        if (capture != null) capture.release();
        if (projection != null) {
            projection.unregisterCallback(projectionEvents);
            projection.stop();
            projection = null;
        }
        if (engine != null) engine.close();
        stopForeground(STOP_FOREGROUND_REMOVE);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
