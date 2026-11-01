package io.freemytv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Dedicated visual Cache Cleaner activity for Fire TV Stick.
 * Provides real-time feedback on freed space and storage optimization.
 */
public class CacheCleanerActivity extends Activity {
    private CleanerView view;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean cleaning = true;
    private CacheCleaner.Result result;
    private Runnable autoCloseRunnable;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Watchdog.install(this);
        Ui.init(this);

        boolean silent = getIntent() != null && getIntent().getBooleanExtra("silent", false);
        if (silent) {
            executor.execute(() -> {
                CacheCleaner.Result res = CacheCleaner.clean(getApplicationContext());
                handler.post(() -> {
                    Toast.makeText(getApplicationContext(),
                            "✓ Caché liberada: " + res.formattedFreed() + " de espacio optimizado",
                            Toast.LENGTH_LONG).show();
                    finish();
                });
            });
            return;
        }

        view = new CleanerView(this);
        setContentView(view);

        // Start cache clearing task
        executor.execute(() -> {
            long start = SystemClock.uptimeMillis();
            CacheCleaner.Result res = CacheCleaner.clean(getApplicationContext());
            // Ensure minimum visual feedback time of 1.2s
            long elapsed = SystemClock.uptimeMillis() - start;
            if (elapsed < 1200) {
                try { Thread.sleep(1200 - elapsed); } catch (InterruptedException ignored) {}
            }
            handler.post(() -> {
                cleaning = false;
                result = res;
                view.invalidate();
                // Schedule auto close after 8 seconds
                autoCloseRunnable = this::finish;
                handler.postDelayed(autoCloseRunnable, 8000);
            });
        });
    }

    @Override
    protected void onDestroy() {
        if (autoCloseRunnable != null) handler.removeCallbacks(autoCloseRunnable);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            int code = event.getKeyCode();
            if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER) {
                finish();
                return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }

    private final class CleanerView extends View {
        private final Paint bgPaint = new Paint();
        private final Paint glowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint cardPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint btnPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF rect = new RectF();
        private long animStart = SystemClock.uptimeMillis();

        private float dp(float val) {
            return val * getResources().getDisplayMetrics().density;
        }

        CleanerView(Context context) {
            super(context);
            setFocusable(true);
            requestFocus();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int w = getWidth(), h = getHeight();
            if (w <= 0 || h <= 0) return;

            // 1. Sleek OLED dark gradient background
            bgPaint.setShader(new LinearGradient(0, 0, 0, h, 0xFF080C14, 0xFF030509, Shader.TileMode.CLAMP));
            canvas.drawRect(0, 0, w, h, bgPaint);

            float cx = w / 2f, cy = h * 0.42f;

            // 2. Radial atmospheric glow
            int glowColor = cleaning ? 0x3300E5FF : 0x4400E676;
            glowPaint.setShader(new RadialGradient(cx, cy, h * 0.55f, glowColor, 0x00000000, Shader.TileMode.CLAMP));
            canvas.drawCircle(cx, cy, h * 0.55f, glowPaint);

            // 3. Central Glass Card
            float cardW = Math.min(w * 0.76f, dp(780));
            float cardH = Math.min(h * 0.72f, dp(440));
            float cardL = cx - cardW / 2f, cardT = cy - cardH / 2f + dp(20);
            rect.set(cardL, cardT, cardL + cardW, cardT + cardH);

            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(0xCC131926); // Dark translucent glass
            canvas.drawRoundRect(rect, dp(24), dp(24), cardPaint);

            cardPaint.setStyle(Paint.Style.STROKE);
            cardPaint.setStrokeWidth(dp(1.5f));
            cardPaint.setColor(0x33FFFFFF);
            canvas.drawRoundRect(rect, dp(24), dp(24), cardPaint);

            // 4. Icon Frame & Symbol
            float iconSize = dp(76);
            float iconY = cardT + dp(45);
            rect.set(cx - iconSize / 2f, iconY - iconSize / 2f, cx + iconSize / 2f, iconY + iconSize / 2f);

            cardPaint.setStyle(Paint.Style.FILL);
            cardPaint.setColor(cleaning ? 0xFF003847 : 0xFF004D25);
            canvas.drawRoundRect(rect, dp(18), dp(18), cardPaint);

            cardPaint.setStyle(Paint.Style.STROKE);
            cardPaint.setStrokeWidth(dp(2f));
            cardPaint.setColor(cleaning ? 0xFF00E5FF : 0xFF00E676);
            canvas.drawRoundRect(rect, dp(18), dp(18), cardPaint);

            // Draw Symbol (cleaner icon / sparkles)
            String symbol = cleaning ? "sync" : "sparkles";
            Symbols.draw(getContext(), canvas, symbol, cx, iconY, iconSize * 0.52f, 0xFFFFFFFF);

            // 5. Titles and content
            textPaint.setTextAlign(Paint.Align.CENTER);
            if (cleaning) {
                // Title
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setTextSize(dp(26));
                textPaint.setFakeBoldText(true);
                canvas.drawText("Limpiando Caché del Sistema", cx, iconY + dp(72), textPaint);

                // Subtitle
                textPaint.setColor(0xFF94A3B8);
                textPaint.setTextSize(dp(16));
                textPaint.setFakeBoldText(false);
                canvas.drawText("Optimizando almacenamiento y liberando memoria de aplicaciones…", cx, iconY + dp(108), textPaint);

                // Pulsing dot status
                long now = SystemClock.uptimeMillis();
                int dots = (int) ((now / 400) % 4);
                StringBuilder sb = new StringBuilder("Purgando búfers y datos temporales");
                for (int i = 0; i < dots; i++) sb.append(".");
                textPaint.setColor(0xFF00E5FF);
                textPaint.setTextSize(dp(15));
                canvas.drawText(sb.toString(), cx, iconY + dp(150), textPaint);

                postInvalidateDelayed(100);
            } else {
                // Result State
                textPaint.setColor(0xFF00E676);
                textPaint.setTextSize(dp(26));
                textPaint.setFakeBoldText(true);
                canvas.drawText("✓ ¡Caché Limpiada con Éxito!", cx, iconY + dp(66), textPaint);

                // Big Freed Metric
                String freedStr = result != null ? "+" + result.formattedFreed() : "+0 MB";
                textPaint.setColor(0xFFFFFFFF);
                textPaint.setTextSize(dp(44));
                textPaint.setFakeBoldText(true);
                canvas.drawText(freedStr, cx, iconY + dp(122), textPaint);

                textPaint.setColor(0xFF00E676);
                textPaint.setTextSize(dp(14));
                textPaint.setFakeBoldText(true);
                canvas.drawText("ESPACIO LIBERADO", cx, iconY + dp(146), textPaint);

                // Storage summary info
                String availStr = result != null ?
                        "Almacenamiento disponible: " + result.formattedAvailable() + " (Total: " + result.formattedTotal() + ")" :
                        "Almacenamiento disponible optimizado";
                textPaint.setColor(0xFFCBD5E1);
                textPaint.setTextSize(dp(16));
                textPaint.setFakeBoldText(false);
                canvas.drawText(availStr, cx, iconY + dp(185), textPaint);

                textPaint.setColor(0xFF64748B);
                textPaint.setTextSize(dp(13));
                canvas.drawText("Tus cuentas, inicios de sesión y configuraciones están intactos.", cx, iconY + dp(212), textPaint);

                // 6. Action Button: "Listo"
                float btnW = dp(220), btnH = dp(48);
                float btnT = cardT + cardH - dp(70);
                rect.set(cx - btnW / 2f, btnT, cx + btnW / 2f, btnT + btnH);

                btnPaint.setStyle(Paint.Style.FILL);
                btnPaint.setColor(0xFFFFFFFF); // High-contrast focused button
                canvas.drawRoundRect(rect, dp(24), dp(24), btnPaint);

                textPaint.setColor(0xFF0F172A);
                textPaint.setTextSize(dp(17));
                textPaint.setFakeBoldText(true);
                canvas.drawText("Listo (Volver)", cx, btnT + dp(30), textPaint);
            }
        }
    }
}
