package app.morphe.extension.youtube.patches;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.ResourceType;
import app.morphe.extension.shared.ResourceUtils;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.returnyoutubedislike.ReturnYouTubeDislikeButtons;
import app.morphe.extension.shared.spoof.SpoofAppVersionPatch;
import app.morphe.extension.shared.theme.ThemeUtils;
import app.morphe.extension.youtube.settings.Settings;
import app.morphe.extension.youtube.shared.PlayerType;

@SuppressWarnings("unused")
public class FixLikeButtonPatch {

    /**
     * Diagnostics tag, intentionally not routed through {@link Logger} because debug level
     * logging may be stripped or gated in release builds. Filter with: adb logcat -v time | findstr /i fixlike
     */
    private static final String TAG = "FIXLIKE";

    static {
        // Proves the patch extension code is inside the APK and the class was loaded.
        Log.i(TAG, "extension class initialized (patch code is in the APK)");
    }

    /**
     * YouTube marks its action bar buttons with an accessibility id view tag.
     * Same lookup the Return YouTube Dislike extension uses, which is proven on device
     * to find the like and dislike buttons of both the modern and the old action bar.
     */
    private static final String LIKE_BUTTON_ACCESSIBILITY_ID_PREFIX = "id.video.like";
    private static volatile int accessibilityIdTag = -1; // -1 = not looked up yet

    private static int accessibilityIdTag() {
        int tag = accessibilityIdTag;
        if (tag >= 0) {
            return tag;
        }
        tag = ResourceUtils.getIdentifier(ResourceType.ID, "elements_accessibility_view_tag_id");
        accessibilityIdTag = tag;
        return tag;
    }

    /**
     * Set after the first ComponentHost.setContentDescription call, to prove the patch
     * bytecode is in the APK and the Litho hook was injected. This log does not depend on
     * recognizing the like button, so it is the first link in the diagnostic chain.
     */
    private static volatile boolean hostHookAliveLogged;

    /**
     * Injection point. Injected at the start of Litho ComponentHost.setContentDescription,
     * the same method the Return YouTube Dislike patch hooks. Every action bar button passes
     * through here, and YouTube tags its like button with an accessibility view tag, so the
     * like button of the restored old action bar is reliably found here even though its
     * animation does not go through the lottie url path.
     * <p>
     * This hook is called for every Litho host in the app, so it must stay cheap: the common
     * case exits after one volatile read, a null check and a view tag lookup.
     *
     * @param host        The ComponentHost (a View) having its description set.
     * @param description The new content description.
     */
    public static void onComponentHostSetDescription(View host, CharSequence description) {
        if (!hostHookAliveLogged) {
            hostHookAliveLogged = true;
            Log.i(TAG, "component host hook alive (patch bytecode present): host="
                    + host.getClass().getName());
        }
        try {
            if (description == null) {
                return;
            }
            final int tag = accessibilityIdTag();
            if (tag == 0) {
                return;
            }
            Object idTag = host.getTag(tag);
            if (!(idTag instanceof String)) {
                return;
            }
            String accessibilityId = (String) idTag;
            if (!accessibilityId.startsWith(LIKE_BUTTON_ACCESSIBILITY_ID_PREFIX)) {
                return;
            }

            Log.i(TAG, "like button host found: id=" + accessibilityId
                    + " host=" + host.getClass().getName()
                    + " desc=" + description);

            if (oldVideoActionBarRestored()
                    && Settings.FIX_LIKE_BUTTON_STATIC_ICON.get()) {
                applyStaticThumbUpIcon(host);
            }
        } catch (Exception ex) {
            Logger.printException(() -> "onComponentHostSetDescription failure", ex);
        }
    }

    private static final String THEMED_LIKE_ANIMATIONS_PREFIX =
            "https://www.gstatic.com/youtube/img/lottie/custom_animated_like_icon/";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX =
            "https://cdn.jsdelivr.net/gh/MorpheApp/morphe-patches@dev/patches/src/main/resources/actionbar/assets/";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_DARK_ICON_RESIZED_URL =
            THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX + "animated_like_icon_dark_v4.json";
    private static final String THEMED_LIKE_ANIMATIONS_LEGACY_LIGHT_ICON_RESIZED_URL =
            THEMED_LIKE_ANIMATIONS_LEGACY_ICON_RESIZED_URL_PREFIX + "animated_like_icon_light_v4.json";

    /**
     * The action button icon changes based on the server response.
     * It cannot be turned on or off with an experimental flag on the client side.
     * <p>
     * YouTube 20.39+ uses a bold icon in the action bar, while YouTube 20.38 uses a legacy icon in the action bar.
     * <p>
     * When the old video action bar is restored on YouTube 20.39+, the native bold themed
     * animation is incompatible with the restored old layout: the like button renders no icon
     * and gives no visual feedback for the liked state. Verified on device with YouTube
     * 21.30.216 (the icon returns when the old action bar is turned off). The legacy
     * replacement animation is the same one used by YouTube 20.38 and renders correctly
     * in the restored old layout.
     */
    private static final boolean USE_LEGACY_ICON = !VersionCheckPatch.IS_20_39_OR_GREATER
            || SpoofAppVersionPatch.isSpoofingToLessThan("20.38.00")
            || oldVideoActionBarRestored();

    /**
     * Must match the effective condition of {@link RestoreOldVideoActionBarPatch}:
     * the old action bar is only restored when the user enables it, layout updates are
     * not disabled, and the device is not a tablet (tablets keep the modern non-collapsed bar).
     */
    private static boolean oldVideoActionBarRestored() {
        return Settings.RESTORE_OLD_VIDEO_ACTION_BAR.get()
                && !Settings.DISABLE_LAYOUT_UPDATES.get()
                && !Utils.isTablet();
    }

    /**
     * Injection point.
     * <p>
     * The legacy replacement icons are fetched from a CDN (cdn.jsdelivr.net) that is unreachable
     * on some networks. If the like button shows no icon, the user can turn off
     * {@link Settings#FIX_LIKE_BUTTON_LEGACY_ICON} to keep the native YouTube animation.
     */
    public static String fixThemedLikeAnimations(String url) {
        if (USE_LEGACY_ICON && Settings.FIX_LIKE_BUTTON_LEGACY_ICON.get()
                && url != null && url.startsWith(THEMED_LIKE_ANIMATIONS_PREFIX)) {
            String finalURL =
                    // Fullscreen quick actions always use a white icon (animated_like_icon_dark_v4.json)
                    PlayerType.getCurrent() == PlayerType.WATCH_WHILE_FULLSCREEN
                            // If not fullscreen, follows the app theme.
                            || Utils.isDarkModeEnabled()
                            ? THEMED_LIKE_ANIMATIONS_LEGACY_DARK_ICON_RESIZED_URL
                            : THEMED_LIKE_ANIMATIONS_LEGACY_LIGHT_ICON_RESIZED_URL;

            Log.i(TAG, "lottie url replacement: from " + url + " to " + finalURL);

            return finalURL;
        }

        return url;
    }

    // region Static icon fallback for the restored old action bar

    /**
     * Like button views with the static icon applied, and the liked state the icon was last drawn for.
     * Null state means the icon has not been drawn yet.
     */
    private static final Map<View, Boolean> STATIC_ICON_VIEWS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private static Drawable.ConstantState thumbUpOutlineDrawableState;
    private static Drawable.ConstantState thumbUpFilledDrawableState;

    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    /**
     * Set once the first like lottie view is encountered. All subsequent log messages are
     * quiet, but the first message contains everything needed to diagnose this patch.
     * Filter with: adb logcat -v time | findstr /i fixlike
     */
    private static final AtomicBoolean DIAGNOSTICS_LOGGED = new AtomicBoolean(false);

    private static void logFixLikeDiagnostics(String url) {
        if (DIAGNOSTICS_LOGGED.getAndSet(true)) {
            return;
        }
        Log.i(TAG, "diagnostics"
                + " appVersion=" + Utils.getAppVersionName()
                + " is20_39=" + VersionCheckPatch.IS_20_39_OR_GREATER
                + " is21_36=" + VersionCheckPatch.IS_21_36_OR_GREATER
                + " useLegacyIcon=" + USE_LEGACY_ICON
                + " oldActionBarRestored=" + oldVideoActionBarRestored()
                + " restoreOldActionBarSetting=" + Settings.RESTORE_OLD_VIDEO_ACTION_BAR.get()
                + " disableLayoutUpdates=" + Settings.DISABLE_LAYOUT_UPDATES.get()
                + " isTablet=" + Utils.isTablet()
                + " staticIconSetting=" + Settings.FIX_LIKE_BUTTON_STATIC_ICON.get()
                + " legacyIconSetting=" + Settings.FIX_LIKE_BUTTON_LEGACY_ICON.get());
    }

    /**
     * Injection point. Called right after a lottie animation view reads its animation url
     * from its own view tag (the like button is a lottie view, dislike/share/save are static
     * vector icons and never reach this code).
     *
     * @param view The lottie animation view.
     * @param url  Animation url read from the view tag.
     * @return The url to load. Unmodified unless the legacy replacement applies.
     */
    public static String onLikeLottieView(View view, String url) {
        // Logs on every call: proves the injection point was hit and shows the raw url.
        // Called only while like button lottie views are built, so volume is tiny.
        Log.i(TAG, "hook hit: view=" + view.getClass().getName() + " url=" + url);

        logFixLikeDiagnostics(url);

        if (url != null && url.startsWith(THEMED_LIKE_ANIMATIONS_PREFIX)
                && oldVideoActionBarRestored()
                && Settings.FIX_LIKE_BUTTON_STATIC_ICON.get()) {
            applyStaticThumbUpIcon(view);
        }

        return fixThemedLikeAnimations(url);
    }

    /**
     * @return The view that draws the like icon: the host itself or its first icon child.
     *         The like/dislike button hosts wrap a single ImageView (same structure the
     *         dislike count drawing relies on), or null if the layout is unrecognized.
     */
    @Nullable
    private static ImageView iconViewOf(View host) {
        if (host instanceof ImageView) {
            return (ImageView) host;
        }
        if (host instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) host;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ImageView) {
                    return (ImageView) child;
                }
            }
        }
        return null;
    }

    private static void applyStaticThumbUpIcon(View host) {
        ImageView iconView = iconViewOf(host);
        if (iconView == null) {
            Log.i(TAG, "static icon branch: no icon view in like button host: "
                    + host.getClass().getName() + " childCount="
                    + (host instanceof ViewGroup ? ((ViewGroup) host).getChildCount() : -1));
            return;
        }
        synchronized (STATIC_ICON_VIEWS) {
            if (STATIC_ICON_VIEWS.containsKey(iconView)) {
                return;
            }
            STATIC_ICON_VIEWS.put(iconView, null);
        }
        Log.i(TAG, "static icon branch: scheduling static thumb up icon for: "
                + iconView.getClass().getName()
                + " staticIconSetting=" + Settings.FIX_LIKE_BUTTON_STATIC_ICON.get());
        MAIN_HANDLER.post(STATIC_ICON_REFRESH_RUNNABLE);
    }

    private static Drawable getThumbUpDrawable(boolean liked) {
        if (thumbUpOutlineDrawableState == null) {
            Context context = Utils.getContext();
            int outlineId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "morphe_ic_thumb_up_outline");
            int filledId = ResourceUtils.getIdentifier(ResourceType.DRAWABLE, "morphe_ic_thumb_up_filled");
            if (outlineId == 0 || filledId == 0) {
                Log.i(TAG, "resource error: thumb up drawables not found"
                        + " outlineId=" + outlineId + " filledId=" + filledId);
                Logger.printException(() -> "FIXLIKE: thumb up drawables not found in resources");
                return null;
            }
            thumbUpOutlineDrawableState = context.getDrawable(outlineId).getConstantState();
            thumbUpFilledDrawableState = context.getDrawable(filledId).getConstantState();
        }
        Drawable drawable = (liked ? thumbUpFilledDrawableState : thumbUpOutlineDrawableState).newDrawable();
        drawable.setTint(ThemeUtils.getAppForegroundColor());
        return drawable;
    }

    /**
     * Periodically re-applies the static icon. Handles liked state changes and also re-draws
     * if a failed lottie load cleared the drawable. Stops itself once no views are attached.
     */
    private static final Runnable STATIC_ICON_REFRESH_RUNNABLE = new Runnable() {
        @Override
        public void run() {
            synchronized (STATIC_ICON_VIEWS) {
                STATIC_ICON_VIEWS.keySet().removeIf(view -> !view.isAttachedToWindow());
            }
            if (STATIC_ICON_VIEWS.isEmpty()) {
                return;
            }
            final boolean liked = ReturnYouTubeDislikeButtons.isCurrentVideoLikedByUser();
            synchronized (STATIC_ICON_VIEWS) {
                for (Map.Entry<View, Boolean> entry : STATIC_ICON_VIEWS.entrySet()) {
                    ImageView imageView = (ImageView) entry.getKey();
                    Boolean appliedState = entry.getValue();
                    if (appliedState == null || appliedState != liked || imageView.getDrawable() == null) {
                        Drawable drawable = getThumbUpDrawable(liked);
                        Log.i(TAG, "drawing static icon: liked=" + liked
                                + (drawable == null ? " drawable=NULL" : " drawable=ok")
                                + " previousState=" + appliedState);
                        imageView.setImageDrawable(drawable);
                        entry.setValue(liked);
                    }
                }
            }
            MAIN_HANDLER.postDelayed(this, 400);
        }
    };

    // endregion
}
