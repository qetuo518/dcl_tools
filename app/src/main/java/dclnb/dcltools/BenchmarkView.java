package dclnb.dcltools;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.os.SystemClock;

/**
 * Canvas渲染视图 - 用于性能跑分测试
 */
public class BenchmarkView extends View {

    private Paint paint;
    private boolean running = false;
    private int frameCount = 0;
    private long startTime = 0;
    
    private float cx = 0f;
    private float cy = 0f;
    
    private int mode = 0;
    public static final int MODE_STARFIELD = 0;
    public static final int MODE_CUBIC = 1;
    public static final int MODE_GAME = 2;
    
    // 星空
    private Star[] stars;
    private static final int STAR_COUNT = 200;
    
    // 立方体
    private float angleX = 0f;
    private float angleY = 0f;
    private float[][] vertices = new float[8][3];
    private static final float SIZE = 60f;
    
    // 游戏
    private float playerX = 0f;
    private Obstacle[] obstacles;
    private static final int OBSTACLE_COUNT = 50;
    
    // Handler
    private android.os.Handler renderHandler;
    private Runnable renderRunnable;
    
    public BenchmarkView(Context context) {
        super(context);
        init();
    }
    
    public BenchmarkView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }
    
    private void init() {
        paint = new Paint();
        paint.setColor(Color.CYAN);
        paint.setStrokeWidth(3f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setAntiAlias(true);
        
        // 初始化星空
        stars = new Star[STAR_COUNT];
        for (int i = 0; i < STAR_COUNT; i++) {
            stars[i] = new Star();
            stars[i].reset();
        }
        
        // 初始化立方体
        vertices[0] = new float[]{-SIZE, -SIZE, -SIZE};
        vertices[1] = new float[]{SIZE, -SIZE, -SIZE};
        vertices[2] = new float[]{SIZE, SIZE, -SIZE};
        vertices[3] = new float[]{-SIZE, SIZE, -SIZE};
        vertices[4] = new float[]{-SIZE, -SIZE, SIZE};
        vertices[5] = new float[]{SIZE, -SIZE, SIZE};
        vertices[6] = new float[]{SIZE, SIZE, SIZE};
        vertices[7] = new float[]{-SIZE, SIZE, SIZE};
        
        // 初始化游戏障碍物
        obstacles = new Obstacle[OBSTACLE_COUNT];
        for (int i = 0; i < OBSTACLE_COUNT; i++) {
            obstacles[i] = new Obstacle();
            obstacles[i].reset();
        }
    }
    
    public void setMode(int m) {
        mode = m;
    }
    
    public void start() {
        if (!running) {
            running = true;
            if (renderHandler == null) {
                renderHandler = new android.os.Handler(android.os.Looper.getMainLooper());
            }
            if (renderRunnable == null) {
                renderRunnable = new Runnable() {
                    public void run() {
                        if (running) {
                            postInvalidate();
                            renderHandler.postDelayed(this, 16);
                        }
                    }
                };
            }
            renderHandler.post(renderRunnable);
        }
    }
    
    public void stop() {
        running = false;
        if (renderHandler != null) {
            renderHandler.removeCallbacksAndMessages(null);
        }
    }
    
    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        cx = w / 2f;
        cy = h / 2f;
        playerX = cx;
    }
    
    @Override
    protected void onDraw(Canvas canvas) {
        canvas.drawColor(Color.parseColor("#0a0a1a"));
        
        if (mode == MODE_STARFIELD) {
            drawStarfield(canvas);
        } else if (mode == MODE_CUBIC) {
            drawCube(canvas);
        } else {
            drawGame(canvas);
        }
        
        if (startTime > 0) {
            frameCount++;
        }
    }
    
    private void drawStarfield(Canvas canvas) {
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(1f);
        
        for (Star s : stars) {
            s.update(cx, cy);
            int alpha = (int)(s.brightness * 255);
            paint.setAlpha(alpha);
            canvas.drawCircle(s.x, s.y, s.size, paint);
        }
    }
    
    private void drawCube(Canvas canvas) {
        angleX += 0.5f;
        angleY += 0.8f;
        if (angleX > 360f) angleX -= 360f;
        if (angleY > 360f) angleY -= 360f;
        
        float[][] rotated = new float[8][3];
        for (int i = 0; i < 8; i++) {
            rotated[i] = rotateX(vertices[i], angleX);
            rotated[i] = rotateY(rotated[i], angleY);
        }
        
        float[][] projected = new float[8][2];
        for (int i = 0; i < 8; i++) {
            projected[i] = project(rotated[i]);
        }
        
        paint.setColor(Color.CYAN);
        paint.setStrokeWidth(3f);
        
        canvas.drawLine(projected[0][0], projected[0][1], projected[1][0], projected[1][1], paint);
        canvas.drawLine(projected[1][0], projected[1][1], projected[2][0], projected[2][1], paint);
        canvas.drawLine(projected[2][0], projected[2][1], projected[3][0], projected[3][1], paint);
        canvas.drawLine(projected[3][0], projected[3][1], projected[0][0], projected[0][1], paint);
        canvas.drawLine(projected[4][0], projected[4][1], projected[5][0], projected[5][1], paint);
        canvas.drawLine(projected[5][0], projected[5][1], projected[6][0], projected[6][1], paint);
        canvas.drawLine(projected[6][0], projected[6][1], projected[7][0], projected[7][1], paint);
        canvas.drawLine(projected[7][0], projected[7][1], projected[4][0], projected[4][1], paint);
        canvas.drawLine(projected[0][0], projected[0][1], projected[4][0], projected[4][1], paint);
        canvas.drawLine(projected[1][0], projected[1][1], projected[5][0], projected[5][1], paint);
        canvas.drawLine(projected[2][0], projected[2][1], projected[6][0], projected[6][1], paint);
        canvas.drawLine(projected[3][0], projected[3][1], projected[7][0], projected[7][1], paint);
        
        paint.setColor(Color.WHITE);
        paint.setStrokeWidth(0f);
        canvas.drawCircle(cx, cy, 3, paint);
    }
    
    private void drawGame(Canvas canvas) {
        // 绘制玩家（底部方块）
        paint.setColor(Color.GREEN);
        paint.setStrokeWidth(0f);
        paint.setAlpha(200);
        canvas.drawRect(playerX - 20, cy + 80, playerX + 20, cy + 100, paint);
        
        // 绘制障碍物
        paint.setColor(Color.RED);
        for (Obstacle obs : obstacles) {
            obs.update();
            if (obs.y < cy + 100) {
                canvas.drawRect(obs.x - 20, obs.y - 10, obs.x + 20, obs.y + 10, paint);
            }
        }
    }
    
    private float[] rotateX(float[] p, float angle) {
        float rad = (float) Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        return new float[]{p[0], p[1] * cos - p[2] * sin, p[1] * sin + p[2] * cos};
    }
    
    private float[] rotateY(float[] p, float angle) {
        float rad = (float) Math.toRadians(angle);
        float cos = (float) Math.cos(rad);
        float sin = (float) Math.sin(rad);
        return new float[]{p[0] * cos + p[2] * sin, p[1], -p[0] * sin + p[2] * cos};
    }
    
    private float[] project(float[] p) {
        float z = p[2] + 200f;
        float scale = 200f / z;
        return new float[]{p[0] * scale + cx, -p[1] * scale + cy};
    }
    
    public void startTest() {
        startTime = SystemClock.elapsedRealtime();
        frameCount = 0;
    }
    
    public float getFPS() {
        if (startTime <= 0) return 0f;
        long elapsed = SystemClock.elapsedRealtime() - startTime;
        if (elapsed <= 0) return 0f;
        return (frameCount * 1000.0f) / elapsed;
    }
    
    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        stop();
    }
    
    static class Star {
        float x, y, z;
        float size;
        float brightness;
        
        void reset() {
            x = (float)(Math.random() * 400 - 200);
            y = (float)(Math.random() * 400 - 200);
            z = (float)(Math.random() * 500 + 100);
            size = (float)(Math.random() * 2 + 0.5);
            brightness = (float)(Math.random() * 0.5 + 0.5);
        }
        
        void update(float centerX, float centerY) {
            z -= 2f;
            if (z <= 0) {
                reset();
                z = 500;
            }
            float scale = 300f / z;
            x = (float)((x + 100) * scale - 100);
            y = (float)((y + 100) * scale - 100);
        }
    }
    
    static class Obstacle {
        float x, y, speed;
        
        void reset() {
            x = (float)(Math.random() * 400 - 200);
            y = (float)(Math.random() * -300 - 100);
            speed = (float)(Math.random() * 3 + 2);
        }
        
        void update() {
            y += speed;
            if (y > 200) {
                reset();
            }
        }
    }
}