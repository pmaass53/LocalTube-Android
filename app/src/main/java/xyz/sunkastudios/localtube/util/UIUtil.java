package xyz.sunkastudios.localtube.util;

import android.app.Activity;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

public class UIUtil {

    public static void applyInsets(Activity activity) {
        if (activity == null) return;
        
        View rootView = activity.findViewById(android.R.id.content);
        if (rootView instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) rootView;
            if (group.getChildCount() > 0) {
                View actualRoot = group.getChildAt(0);
                
                int topDp = ConfigManager.getInt("ui_inset_top");
                int bottomDp = ConfigManager.getInt("ui_inset_bottom");
                
                int topPx = dpToPx(activity, topDp);
                int bottomPx = dpToPx(activity, bottomDp);
                
                actualRoot.setPadding(
                    actualRoot.getPaddingLeft(),
                    actualRoot.getPaddingTop() + topPx,
                    actualRoot.getPaddingRight(),
                    actualRoot.getPaddingBottom() + bottomPx
                );
            }
        }
    }

    public static int getAccentColor() {
        return ConfigManager.getColor("accent_color", "#FF4081");
    }

    private static int dpToPx(Activity activity, int dp) {
        return (int) TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP, 
            dp, 
            activity.getResources().getDisplayMetrics()
        );
    }
}
