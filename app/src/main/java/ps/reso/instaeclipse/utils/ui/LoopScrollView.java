package ps.reso.instaeclipse.utils.ui;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.HorizontalScrollView;

/**
 * HorizontalScrollView with fling disabled, for the Home auto-scrolling carousels where fling would
 * race the loop driver. Dragging still works; only post-release momentum is suppressed.
 */
public class LoopScrollView extends HorizontalScrollView {
    public LoopScrollView(Context context) { super(context); }
    public LoopScrollView(Context context, AttributeSet attrs) { super(context, attrs); }
    public LoopScrollView(Context context, AttributeSet attrs, int defStyleAttr) { super(context, attrs, defStyleAttr); }

    @Override
    public void fling(int velocityX) {
        // no-op: the loop driver owns the motion; momentum would fight it.
    }
}
