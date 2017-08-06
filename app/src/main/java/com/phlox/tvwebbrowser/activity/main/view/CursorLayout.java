package com.phlox.tvwebbrowser.activity.main.view;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Point;
import android.graphics.PointF;
import android.graphics.Rect;
import android.os.Handler;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.FrameLayout;

/**
 * Created by PDT on 25.08.2016.
 */
public class CursorLayout extends FrameLayout {
    public static int CURSOR_RADIUS;
    public static float MAX_CURSOR_SPEED;
    public static final int UNCHANGED = -100;
    public static final int CURSOR_DISAPPEAR_TIMEOUT = 5000;
    public static int SCROLL_START_PADDING = 100;
    public static float CURSOR_STROKE_WIDTH;
    public int EFFECT_RADIUS;
    public int EFFECT_DIAMETER;
    private Point cursorDirection = new Point(0, 0);
    private PointF cursorPosition = new PointF(0, 0);
    private PointF cursorSpeed = new PointF(0, 0);
    private Paint paint = new Paint();
    private long lastCursorUpdate = System.currentTimeMillis();
    private boolean dpadCenterPressed = false;
    PointF tmpPointF = new PointF();
    private Callback callback;

    public interface Callback {
        void onUserInteraction();
    }

    public CursorLayout(Context context) {
        super(context);
        init();
    }

    public CursorLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        if (isInEditMode()) {
            return;
        }
        paint.setAntiAlias(true);
        setWillNotDraw(false);
        WindowManager wm = (WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE);
        Display display = wm.getDefaultDisplay();
        Point displaySize = new Point();
        display.getSize(displaySize);
        EFFECT_RADIUS = displaySize.x / 20;
        EFFECT_DIAMETER = EFFECT_RADIUS * 2;
        CURSOR_STROKE_WIDTH = displaySize.x / 400;
        CURSOR_RADIUS = displaySize.x / 110;
        MAX_CURSOR_SPEED = displaySize.x / 25;
        SCROLL_START_PADDING = displaySize.x / 15;
    }

    public void setCallback(Callback callback) {
        this.callback = callback;
    }

    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        if (callback != null) {
            callback.onUserInteraction();
        }
        return super.onInterceptTouchEvent(ev);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        if (isInEditMode()) {
            return;
        }
        cursorPosition.set(w / 2.0f, h / 2.0f);
        getHandler().postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (callback != null) {
            callback.onUserInteraction();
        }
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_LEFT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, UNCHANGED, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, UNCHANGED, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, UNCHANGED, false);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, UNCHANGED, -1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, UNCHANGED, 1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, UNCHANGED, 0, false);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP_LEFT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, -1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_UP_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, -1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN_LEFT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, -1, 1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false);
                }
                return true;

            case KeyEvent.KEYCODE_DPAD_DOWN_RIGHT:
                if (event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleDirectionKeyEvent(event, 1, 1, true);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    handleDirectionKeyEvent(event, 0, 0, false);
                }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER: case KeyEvent.KEYCODE_ENTER: case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (isCursorDissappear()) {
                    break;
                }
                if (event.getAction() == KeyEvent.ACTION_DOWN && !getKeyDispatcherState().isTracking(event)) {
                    getKeyDispatcherState().startTracking(event, this);
                    dpadCenterPressed = true;
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_DOWN);
                } else if (event.getAction() == KeyEvent.ACTION_UP) {
                    getKeyDispatcherState().handleUpEvent(event);
                    //loadUrl("javascript:function simulateClick(x,y){var clickEvent=document.createEvent('MouseEvents');clickEvent.initMouseEvent('click',true,true,window,0,0,0,x,y,false,false,false,false,0,null);document.elementFromPoint(x,y).dispatchEvent(clickEvent)}simulateClick("+(int)cursorPosition.x+","+(int)cursorPosition.y+");");
                    // Obtain MotionEvent object
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_UP);
                    dpadCenterPressed = false;
                }

                return true;
        }

        return super.dispatchKeyEvent(event);
    }

    private void dispatchMotionEvent(float x, float y, int action) {
        long downTime = SystemClock.uptimeMillis();
        long eventTime = SystemClock.uptimeMillis();
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[1];
        MotionEvent.PointerProperties pp1 = new MotionEvent.PointerProperties();
        pp1.id = 0;
        pp1.toolType = MotionEvent.TOOL_TYPE_FINGER;
        properties[0] = pp1;
        MotionEvent.PointerCoords[] pointerCoords = new MotionEvent.PointerCoords[1];
        MotionEvent.PointerCoords pc1 = new MotionEvent.PointerCoords();
        pc1.x = x;
        pc1.y = y;
        pc1.pressure = 1;
        pc1.size = 1;
        pointerCoords[0] = pc1;
        MotionEvent motionEvent = MotionEvent.obtain(downTime, eventTime,
                action, 1, properties,
                pointerCoords, 0,  0, 1, 1, 0, 0, 0, 0 );
        dispatchTouchEvent(motionEvent);
    }

    private void handleDirectionKeyEvent(KeyEvent event, int x, int y, boolean keyDown) {
        lastCursorUpdate = System.currentTimeMillis();
        if (keyDown) {
            if (getKeyDispatcherState().isTracking(event)) {
                return;
            }
            Handler handler = getHandler();
            handler.removeCallbacks(cursorUpdateRunnable);
            handler.post(cursorUpdateRunnable);
            getKeyDispatcherState().startTracking(event, this);
        } else {
            getKeyDispatcherState().handleUpEvent(event);
            cursorSpeed.set(0, 0);
        }

        cursorDirection.set(x == UNCHANGED ? cursorDirection.x : x, y == UNCHANGED ? cursorDirection.y : y);
    }

    private Runnable cursorHideRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
        }
    };

    private Runnable cursorUpdateRunnable = new Runnable() {
        @Override
        public void run() {
            getHandler().removeCallbacks(cursorHideRunnable);

            long newTime = System.currentTimeMillis();
            long dTime = newTime - lastCursorUpdate;
            lastCursorUpdate = newTime;

            float accelerationFactor = 0.05f * dTime;
            //float decelerationFactor = 1 - Math.min(0.5f, 0.005f * dTime);
            cursorSpeed.set(bound(cursorSpeed.x/* * decelerationFactor*/ + bound(cursorDirection.x, 1) * accelerationFactor, MAX_CURSOR_SPEED),
                    bound(cursorSpeed.y/* * decelerationFactor*/ + bound(cursorDirection.y, 1) * accelerationFactor, MAX_CURSOR_SPEED));
            if (Math.abs(cursorSpeed.x) < 0.1f) cursorSpeed.x = 0;
            if (Math.abs(cursorSpeed.y) < 0.1f) cursorSpeed.y = 0;
            if (cursorDirection.x == 0 && cursorDirection.y == 0 && cursorSpeed.x == 0 && cursorSpeed.y == 0) {
                getHandler().postDelayed(cursorHideRunnable, CURSOR_DISAPPEAR_TIMEOUT);
                return;
            }
            tmpPointF.set(cursorPosition);
            cursorPosition.offset(cursorSpeed.x, cursorSpeed.y);
            if (cursorPosition.x < 0) {
                cursorPosition.x = 0;
            } else if (cursorPosition.x > (getWidth() - 1)) {
                cursorPosition.x = getWidth() - 1;
            }
            if (cursorPosition.y < 0) {
                cursorPosition.y = 0;
            } else if (cursorPosition.y > (getHeight() - 1)) {
                cursorPosition.y = getHeight() - 1;
            }
            if (!tmpPointF.equals(cursorPosition)) {
                if (dpadCenterPressed) {
                    dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_MOVE);
                } else {
                    //dispatchMotionEvent(cursorPosition.x, cursorPosition.y, MotionEvent.ACTION_HOVER_MOVE);
                }
            }

            View child = getChildAt(0);
            if (child != null) {
                if (cursorPosition.y > getHeight() - SCROLL_START_PADDING) {
                    if (cursorSpeed.y > 0 && child.canScrollVertically((int) cursorSpeed.y)) {
                        child.scrollTo(child.getScrollX(), child.getScrollY() + (int) cursorSpeed.y);
                    }
                } else if (cursorPosition.y < SCROLL_START_PADDING) {
                    if (cursorSpeed.y < 0 && child.canScrollVertically((int) cursorSpeed.y)) {
                        child.scrollTo(child.getScrollX(), child.getScrollY() + (int) cursorSpeed.y);
                    }
                }
                if (cursorPosition.x > getWidth() - SCROLL_START_PADDING) {
                    if (cursorSpeed.x > 0 && child.canScrollHorizontally((int) cursorSpeed.x)) {
                        child.scrollTo(child.getScrollX() + (int) cursorSpeed.x, child.getScrollY());
                    }
                } else if (cursorPosition.x < SCROLL_START_PADDING) {
                    if (cursorSpeed.x < 0 && child.canScrollHorizontally((int) cursorSpeed.x)) {
                        child.scrollTo(child.getScrollX() + (int) cursorSpeed.x, child.getScrollY());
                    }
                }
            }

            invalidate();
            getHandler().post(this);
        }
    };

    private float bound(float value, float max) {
        if (value > max) {
            return max;
        } else if (value < -max) {
            return - max;
        } else {
            return value;
        }
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (isInEditMode()) {
            return;
        }
        if (isCursorDissappear()) {
            return;
        }

        float cx = cursorPosition.x;
        float cy = cursorPosition.y;

        paint.setColor(Color.argb(128, 255, 255, 255));
        paint.setStyle(Paint.Style.FILL);
        canvas.drawCircle(cx, cy, CURSOR_RADIUS, paint);

        paint.setColor(Color.GRAY);
        paint.setStrokeWidth(CURSOR_STROKE_WIDTH);
        paint.setStyle(Paint.Style.STROKE);
        canvas.drawCircle(cx, cy, CURSOR_RADIUS, paint);
    }

    private boolean isCursorDissappear() {
        long newTime = System.currentTimeMillis();
        return newTime - lastCursorUpdate > CURSOR_DISAPPEAR_TIMEOUT;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

    }
}
