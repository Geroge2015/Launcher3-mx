package com.android.launcher3.popup;

import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.UserHandle;
import android.util.Log;
import android.view.View;

import com.android.launcher3.AbstractFloatingView;
import com.android.launcher3.BaseDraggingActivity;
import com.android.launcher3.FolderInfo;
import com.android.launcher3.ItemInfo;
import com.android.launcher3.Launcher;
import com.android.launcher3.LauncherSettings;
import com.android.launcher3.R;
import com.android.launcher3.ShortcutInfo;
import com.android.launcher3.compat.LauncherAppsCompat;
import com.android.launcher3.model.WidgetItem;
import com.android.launcher3.util.InstantAppResolver;
import com.android.launcher3.util.PackageManagerHelper;
import com.android.launcher3.util.PackageUserKey;
import com.android.launcher3.widget.WidgetsBottomSheet;

import java.net.URISyntaxException;
import java.util.List;

import static com.android.launcher3.userevent.nano.LauncherLogProto.Action;
import static com.android.launcher3.userevent.nano.LauncherLogProto.ControlType;

/**
 * Represents a system shortcut for a given app. The shortcut should have a static label and
 * icon, and an onClickListener that depends on the item that the shortcut services.
 * <p>
 * Example system shortcuts, defined as inner classes, include Widgets and AppInfo.
 */
public abstract class SystemShortcut<T extends BaseDraggingActivity> extends ItemInfo {

    private static final String TAG = "SystemShortcut";

    public final int iconResId;
    public final int labelResId;

    public SystemShortcut(int iconResId, int labelResId) {
        this.iconResId = iconResId;
        this.labelResId = labelResId;
    }

    public abstract View.OnClickListener getOnClickListener(T activity, ItemInfo itemInfo);

    public View.OnClickListener getOnClickListener(T activity, ItemInfo itemInfo, View view) {
        return null;
    }

    public static class Widgets extends SystemShortcut<Launcher> {

        public Widgets() {
            super(R.drawable.ic_widget, R.string.widget_button_text);
        }

        @Override
        public View.OnClickListener getOnClickListener(final Launcher launcher,
                                                       final ItemInfo itemInfo) {
            final List<WidgetItem> widgets =
                    launcher.getPopupDataProvider().getWidgetsForPackageUser(new PackageUserKey(
                            itemInfo.getTargetComponent().getPackageName(), itemInfo.user));
            if (widgets == null) {
                return null;
            }
            return (view) -> {
                AbstractFloatingView.closeAllOpenViews(launcher);
                WidgetsBottomSheet widgetsBottomSheet =
                        (WidgetsBottomSheet) launcher.getLayoutInflater().inflate(
                                R.layout.widgets_bottom_sheet, launcher.getDragLayer(), false);
                widgetsBottomSheet.populateAndShow(itemInfo);
                launcher.getUserEventDispatcher().logActionOnControl(Action.Touch.TAP,
                        ControlType.WIDGETS_BUTTON, view);
            };
        }
    }

    /**
     * 应用信息
     */
    public static class AppInfo extends SystemShortcut {
        public AppInfo() {
            super(R.drawable.ic_info_no_shadow, R.string.app_info_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(
                BaseDraggingActivity activity, ItemInfo itemInfo) {
            return (view) -> {
                Rect sourceBounds = activity.getViewBounds(view);
                Bundle opts = activity.getActivityLaunchOptionsAsBundle(view);
                new PackageManagerHelper(activity).startDetailsActivityForInfo(
                        itemInfo, sourceBounds, opts);
                activity.getUserEventDispatcher().logActionOnControl(Action.Touch.TAP,
                        ControlType.APPINFO_TARGET, view);
            };
        }
    }

    /**
     * 安装
     */
    public static class Install extends SystemShortcut {
        public Install() {
            super(R.drawable.ic_install_no_shadow, R.string.install_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(
                BaseDraggingActivity activity, ItemInfo itemInfo) {
            boolean supportsWebUI = (itemInfo instanceof ShortcutInfo) &&
                    ((ShortcutInfo) itemInfo).hasStatusFlag(ShortcutInfo.FLAG_SUPPORTS_WEB_UI);
            boolean isInstantApp = false;
            if (itemInfo instanceof com.android.launcher3.ShortcutInfo) {
                com.android.launcher3.ShortcutInfo appInfo = (com.android.launcher3.ShortcutInfo) itemInfo;
                isInstantApp = InstantAppResolver.newInstance(activity).isInstantApp(appInfo);
            }
            boolean enabled = supportsWebUI || isInstantApp;
            if (!enabled) {
                return null;
            }
            return createOnClickListener(activity, itemInfo);
        }

        public View.OnClickListener createOnClickListener(
                BaseDraggingActivity activity, ItemInfo itemInfo) {
            return view -> {
                Intent intent = new PackageManagerHelper(view.getContext()).getMarketIntent(
                        itemInfo.getTargetComponent().getPackageName());
                activity.startActivitySafely(view, intent, itemInfo);
                AbstractFloatingView.closeAllOpenViews(activity);
            };
        }
    }

    /**
     * 卸载
     */
    public static class Uninstall extends SystemShortcut {

        public Uninstall() {
            super(R.drawable.ic_uninstall_no_shadow, R.string.uninstall_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(BaseDraggingActivity activity,
                                                       ItemInfo itemInfo) {
            Launcher launcher = Launcher.getLauncher(activity);
            ComponentName cn = getUninstallTarget(launcher, itemInfo);
            if (cn == null) {
                // System applications cannot be installed. For now, show a toast explaining that.
                // We may give them the option of disabling apps this way.
//                Toast.makeText(launcher, R.string.uninstall_system_app_text, Toast.LENGTH_SHORT).show();
                return null;
            }
            return createOnClickListener(launcher, itemInfo, cn);
        }

        public View.OnClickListener createOnClickListener(Launcher launcher,
                                                          ItemInfo itemInfo,
                                                          ComponentName cn) {
            return view -> {
                try {
                    Intent i = Intent.parseUri(launcher.getString(R.string.delete_package_intent), 0)
                            .setData(Uri.fromParts("package", cn.getPackageName(), cn.getClassName()))
                            .putExtra(Intent.EXTRA_USER, itemInfo.user);
                    launcher.startActivity(i);
                } catch (URISyntaxException e) {
                    Log.e(TAG, "Failed to parse intent to start uninstall activity for item=" + itemInfo);
                }
            };
        }

        /**
         * @return the component name that should be uninstalled or null.
         */
        private ComponentName getUninstallTarget(Launcher launcher, ItemInfo item) {
            Intent intent = null;
            UserHandle user = null;
            if (item != null &&
                    item.itemType == LauncherSettings.BaseLauncherColumns.ITEM_TYPE_APPLICATION) {
                intent = item.getIntent();
                user = item.user;
            }
            if (intent != null) {
                LauncherActivityInfo info = LauncherAppsCompat.getInstance(launcher)
                        .resolveActivity(intent, user);
                if (info != null
                        && (info.getApplicationInfo().flags & ApplicationInfo.FLAG_SYSTEM) == 0) {
                    return info.getComponentName();
                }
            }
            return null;
        }
    }

    /**
     * 移除：目前设计只移除桌面上的widget，可能此处不需要
     */
    public static class Delete extends SystemShortcut {

        public Delete() {
            super(R.drawable.ic_remove_no_shadow, R.string.remove_drop_target_label);
        }

        @Override
        public View.OnClickListener getOnClickListener(BaseDraggingActivity activity, ItemInfo itemInfo) {
            if (itemInfo instanceof FolderInfo || itemInfo instanceof ShortcutInfo) {
                return null;
            }
            return createOnClickListener(Launcher.getLauncher(activity), itemInfo);
        }

        @Override
        public View.OnClickListener getOnClickListener(BaseDraggingActivity activity, ItemInfo itemInfo, View view) {
            if (itemInfo instanceof FolderInfo || itemInfo instanceof ShortcutInfo) {
                return null;
            }
            return createOnClickListener(Launcher.getLauncher(activity), itemInfo, view);
        }

        public View.OnClickListener createOnClickListener(Launcher launcher,
                                                          ItemInfo itemInfo,
                                                          View widget) {
            return view -> {
                launcher.getWorkspace().removeWorkspaceItem(widget);
                launcher.getWorkspace().stripEmptyScreens();
                launcher.getDragLayer()
                        .announceForAccessibility(launcher.getString(R.string.item_removed));
                launcher.getModelWriter().deleteItemFromDatabase(itemInfo);
                PopupWidgetWithArrow arrow = PopupWidgetWithArrow.getOpen(launcher);
                if (arrow != null) {
                    arrow.closeComplete();
                }
            };
        }

        public View.OnClickListener createOnClickListener(Launcher launcher,
                                                          ItemInfo itemInfo) {
            return view -> {
                launcher.removeItem(null, itemInfo, true /* deleteFromDb */);
                launcher.getWorkspace().stripEmptyScreens();
                launcher.getDragLayer()
                        .announceForAccessibility(launcher.getString(R.string.item_removed));
                launcher.getModelWriter().deleteItemFromDatabase(itemInfo);
                PopupWidgetWithArrow arrow = PopupWidgetWithArrow.getOpen(launcher);
                if (arrow != null) {
                    arrow.closeComplete();
                }
            };
        }

    }
}
