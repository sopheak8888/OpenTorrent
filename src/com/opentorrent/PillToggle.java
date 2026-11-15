package com.opentorrent;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** 36dp x 22dp pill switch, same proportions as µTorrent's seeding toggle. */
public class PillToggle extends View {

    public interface OnChange {
        void onChanged(boolean on);
    }

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private boolean on;
    private float pos; // 0 = off, 1 = on
    private OnChange listener;

    public PillToggle(Context c) {
        this(c, null);
    }

    public PillToggle(Context c, AttributeSet a) {
        super(c, a);
        track.setColor(0xFFCCCCCC);
        knob.setColor(0xFFFFFFFF);
        setClickable(true);
        setFocusable(true);
    }

    public void setOnChangeListener(OnChange l) {
        listener = l;
    }

    public boolean isOn() {
        return on;
    }

    public void setOn(boolean value) {
        setOn(value, false);
    }

    public void setOn(boolean value, boolean animate) {
        if (on == value && pos > 0) return;
        on = value;
        if (!animate) {
            pos = value ? 1f : 0f;
            invalidate();
            return;
        }
        ValueAnimator a = ValueAnimator.ofFloat(pos, value ? 1f : 0f);
        a.setDuration(140);
        a.addUpdateListener(an -> {
            pos = (float) an.getAnimatedValue();
            invalidate();
        });
        a.start();
    }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float r = h / 2f;
        rect.set(0, 0, w, h);
        track.setColor(blend(0xFFCCCCCC, 0xFF1CB71C, pos));
        c.drawRoundRect(rect, r, r, track);

        float kr = r - 2.5f;
        float cx = kr + 2.5f + pos * (w - 2f * kr - 5f);
        c.drawCircle(cx, h / 2f, kr, knob);
    }

    private static int blend(int a, int b, float f) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int rr = (int) (ar + (br - ar) * f);
        int rg = (int) (ag + (bg - ag) * f);
        int rb = (int) (ab + (bb - ab) * f);
        return 0xFF000000 | (rr << 16) | (rg << 8) | rb;
    }

    @Override
    public boolean performClick() {
        boolean handled = super.performClick();
        setOn(!on, true);
        if (listener != null) listener.onChanged(on);
        return handled;
    }
}