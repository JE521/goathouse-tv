package com.gathin.tvhome;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.KeyEvent;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ScrollView;

/**
 * goathouse: the media rows' vertical scroller. ScrollView's own focus scrolling snaps (and fights a second scroll),
 * so it is switched off here: Up/Down move focus between rows, and MediaShelf glides the focused row into place.
 */
public final class ShelfScrollView extends ScrollView {
    private ValueAnimator glide;
    /** Up from the top row: the host sends focus to the top bar (a plain focus search would pick the tucked dock,
     *  whose translated position the focus finder does not see). */
    Runnable onUpEdge;

    public ShelfScrollView(Context context, AttributeSet attrs) {
        super(context, attrs);
        setSmoothScrollingEnabled(false);
    }

    /** No automatic "bring the focused child on screen": glideTo does that, once and smoothly. */
    @Override
    protected int computeScrollDeltaToGetChildRectOnScreen(Rect rect) {
        return 0;
    }

    /** Up/Down: next row's card in that direction, or let the key leave the rows (top bar / dock). */
    @Override
    public boolean executeKeyEvent(KeyEvent event) {
        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
        int code = event.getKeyCode();
        if (code != KeyEvent.KEYCODE_DPAD_UP && code != KeyEvent.KEYCODE_DPAD_DOWN) return false;
        View focused = findFocus();
        if (focused == null) return false;
        View next = FocusFinder.getInstance().findNextFocus(this, focused,
                code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
        if (next == null) {
            if (code == KeyEvent.KEYCODE_DPAD_UP && onUpEdge != null) {
                onUpEdge.run();
                return true;
            }
            return false;
        }
        next.requestFocus(code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : View.FOCUS_DOWN);
        playSoundEffect(code == KeyEvent.KEYCODE_DPAD_UP
                ? android.view.SoundEffectConstants.NAVIGATION_UP : android.view.SoundEffectConstants.NAVIGATION_DOWN);
        return true;
    }

    /** Eases the scroll to y (clamped to the content), about a third of a second. */
    void glideTo(int y) {
        View child = getChildCount() > 0 ? getChildAt(0) : null;
        int max = child == null ? 0 : Math.max(0, child.getHeight() - (getHeight() - getPaddingTop() - getPaddingBottom()));
        int target = Math.max(0, Math.min(y, max));
        if (glide != null) glide.cancel();
        int from = getScrollY();
        if (from == target) return;
        glide = ValueAnimator.ofInt(from, target);
        glide.setDuration(320);
        glide.setInterpolator(new DecelerateInterpolator(2.2f));
        glide.addUpdateListener(a -> scrollTo(0, (int) a.getAnimatedValue()));
        glide.start();
    }
}
