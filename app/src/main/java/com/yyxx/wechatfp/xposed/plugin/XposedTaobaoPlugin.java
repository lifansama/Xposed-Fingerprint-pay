package com.yyxx.wechatfp.xposed.plugin;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.support.annotation.Keep;
import android.support.annotation.NonNull;
import android.support.annotation.Nullable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.wei.android.lib.fingerprintidentify.FingerprintIdentify;
import com.wei.android.lib.fingerprintidentify.base.BaseFingerprint;
import com.yyxx.wechatfp.BuildConfig;
import com.yyxx.wechatfp.Lang;
import com.yyxx.wechatfp.R;
import com.yyxx.wechatfp.network.updateCheck.UpdateFactory;
import com.yyxx.wechatfp.util.BlackListUtils;
import com.yyxx.wechatfp.util.Config;
import com.yyxx.wechatfp.util.DpUtil;
import com.yyxx.wechatfp.util.ImageUtil;
import com.yyxx.wechatfp.util.StyleUtil;
import com.yyxx.wechatfp.util.TaobaoVersionControl;
import com.yyxx.wechatfp.util.Task;
import com.yyxx.wechatfp.util.Umeng;
import com.yyxx.wechatfp.util.ViewUtil;
import com.yyxx.wechatfp.util.bugfixer.xposed.XposedLogNPEBugFixer;
import com.yyxx.wechatfp.util.log.L;
import com.yyxx.wechatfp.view.SettingsView;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import static com.yyxx.wechatfp.Constant.ICON_FINGER_PRINT_ALIPAY_BASE64;

/**
 * Created by Jason on 2017/9/8.
 */

public class XposedTaobaoPlugin {

    private AlertDialog mFingerPrintAlertDialog;
    private boolean mPwdActivityDontShowFlag;
    private int mPwdActivityReShowDelayTimeMsec;

    private FingerprintIdentify mFingerprintIdentify;
    private Activity mCurrentActivity;

    private boolean mIsViewTreeObserverFirst;
    private LinearLayout mItemHlinearLayout;
    private LinearLayout mLineTopCon;
    private View mLineBottomView;
    private int mTaobaoVersionCode;
    private boolean isFirstStartup = true;

    @Keep
    public void main(final Context context, final XC_LoadPackage.LoadPackageParam lpparam) {
        L.d("Xposed plugin init version: " + BuildConfig.VERSION_NAME);
        try {
            Umeng.init(context);
            XposedLogNPEBugFixer.fix();
            final PackageInfo packageInfo = context.getPackageManager().getPackageInfo(lpparam.packageName, 0);
            mTaobaoVersionCode = packageInfo.versionCode;
            String versionName = packageInfo.versionName;
            L.d("app info: versionCode:" + mTaobaoVersionCode + " versionName:" + versionName);

            XposedHelpers.findAndHookMethod(Activity.class, "onResume", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;

                    final String activityClzName = activity.getClass().getName();
                    if (BuildConfig.DEBUG) {
                        L.d("activity", activity, "clz", activityClzName);
                    }
                    if (handleSettingsMenuInjection(activity)) {
                        return;
                    } else if (activityClzName.contains(".welcome.Welcome")) {
                        if (isFirstStartup) {
                            isFirstStartup = false;
                            Task.onMain(6000, () -> UpdateFactory.doUpdateCheck(activity));
                        }
                    }
                }
            });
            XposedHelpers.findAndHookMethod(Activity.class, "onCreate", Bundle.class, new XC_MethodHook() {

                @TargetApi(21)
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    final Activity activity = (Activity) param.thisObject;
                    final String activityClzName = activity.getClass().getName();
                    if (BuildConfig.DEBUG) {
                        L.d("activity", activity, "clz", activityClzName);
                    }
                    mCurrentActivity = activity;
                   if (activityClzName.contains(mTaobaoVersionCode >= 301 ? ".PayPwdDialogActivity" : ".MspContainerActivity")
                           || activityClzName.contains(".FlyBirdWindowActivity")) {
                        L.d("found");
                        final Config config = Config.from(activity);
                        if (!config.isOn()) {
                            return;
                        }
                        mIsViewTreeObserverFirst = true;
                        activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(() -> {
                            if (mCurrentActivity == null) {
                                return;
                            }
                            if (activity.isDestroyed()) {
                                return;
                            }
                            if (mCurrentActivity != activity) {
                                return;
                            }
                            if (ViewUtil.findViewByName(activity, "com.taobao.taobao", "mini_spwd_input") == null
                                    && ViewUtil.findViewByName(activity, "com.taobao.taobao", "simplePwdLayout") == null
                                    && ViewUtil.findViewByName(activity, "com.taobao.taobao", "input_et_password") == null ) {
                                return;
                            }
                            if (mIsViewTreeObserverFirst) {
                                mIsViewTreeObserverFirst = false;
                                showFingerPrintDialog(activity);
                            }
                        });

                   } else if (activityClzName.contains("PayPwdHalfActivity")) {
                        L.d("found");
                        final Config config = Config.from(activity);
                        if (!config.isOn()) {
                            return;
                        }
                        activity.getWindow().getDecorView().setAlpha(0);
                        Task.onMain(1500, () -> {
                            final String modulePackageName = "com.taobao.taobao";
                            View key1View = ViewUtil.findViewByName(activity, modulePackageName, "key_num_1");
                            if (key1View != null) {
                                showFingerPrintDialog(activity);
                                return;
                            }

                            //try again
                            Task.onMain(2000, () -> showFingerPrintDialog(activity));
                        });
                   } else if (mTaobaoVersionCode >= 323 /** 9.20.0 */ && handleSettingsMenuInjection(activity)) {
                       return;
                   }
                }
            });
        } catch (Throwable l) {
            XposedBridge.log(l);
        }
    }

    private boolean handleSettingsMenuInjection(Activity activity) {
        try {
            final String activityClzName = activity.getClass().getName();
            if (BuildConfig.DEBUG) {
                L.d("activity", activity, "clz", activityClzName);
            }
            if (activityClzName.contains(".NewTaobaoSettingActivity") // <- 这个命名真是SB, 再改版一次估计你要叫 NewNewTaobaoSettingActivity 了吧
                    || activityClzName.contains(".TaobaoSettingActivity")) {
                Task.onMain(250, () -> doSettingsMenuInject(activity)); // 100 -> 250, 这个改版的页面加载性能还没上一版好
                return true;
            }
        } catch (Exception e) {
            L.e(e);
        }
        return false;
    }

    public void initFingerPrintLock(final Context context, final Runnable onSuccessUnlockCallback) {
        mFingerprintIdentify = new FingerprintIdentify(context);
        if (mFingerprintIdentify.isFingerprintEnable()) {
            mFingerprintIdentify.startIdentify(3, new BaseFingerprint.FingerprintIdentifyListener() {
                @Override
                public void onSucceed() {
                    // 验证成功，自动结束指纹识别
                    Toast.makeText(context, Lang.getString(R.id.toast_fingerprint_match), Toast.LENGTH_SHORT).show();
                    L.d("指纹识别成功");
                    onSuccessUnlockCallback.run();
                }

                @Override
                public void onNotMatch(int availableTimes) {
                    // 指纹不匹配，并返回可用剩余次数并自动继续验证
                    L.d("指纹识别失败，还可尝试" + String.valueOf(availableTimes) + "次");
                    Toast.makeText(context, Lang.getString(R.id.toast_fingerprint_not_match), Toast.LENGTH_SHORT).show();
                }

                @Override
                public void onFailed(boolean isDeviceLocked) {
                    // 错误次数达到上限或者API报错停止了验证，自动结束指纹识别
                    // isDeviceLocked 表示指纹硬件是否被暂时锁定
                    L.d("多次尝试错误，请使用密码输入");
                    Toast.makeText(context, Lang.getString(R.id.toast_fingerprint_retry_ended), Toast.LENGTH_SHORT).show();
                    AlertDialog dialog = mFingerPrintAlertDialog;
                    if (dialog != null) {
                        if (dialog.isShowing()) {
                            dialog.dismiss();
                        }
                    }
                }

                @Override
                public void onStartFailedByDeviceLocked() {
                    // 第一次调用startIdentify失败，因为设备被暂时锁定
                    L.d("系统限制，重启后必须验证密码后才能使用指纹验证");
                    Toast.makeText(context, Lang.getString(R.id.toast_fingerprint_unlock_reboot), Toast.LENGTH_SHORT).show();
                }
            });
        } else {
            L.d("系统指纹功能未启用");
            Toast.makeText(context, Lang.getString(R.id.toast_fingerprint_not_enable), Toast.LENGTH_SHORT).show();
        }
    }

    public void showFingerPrintDialog(final Activity activity) {
        final Context context = activity;
        try {
            activity.getWindow().getDecorView().setAlpha(0);
            mPwdActivityDontShowFlag = false;
            mPwdActivityReShowDelayTimeMsec = 0;
            int defVMargin = DpUtil.dip2px(context, 30);
            final Bitmap bitmap = ImageUtil.base64ToBitmap(ICON_FINGER_PRINT_ALIPAY_BASE64);
            LinearLayout rootVLinearLayout = new LinearLayout(context);
            rootVLinearLayout.setOrientation(LinearLayout.VERTICAL);
            rootVLinearLayout.setGravity(Gravity.CENTER_HORIZONTAL);

            ImageView imageView = new ImageView(context);
            imageView.setImageBitmap(bitmap);

            TextView textView = new TextView(context);
            StyleUtil.apply(textView);
            textView.setText(Lang.getString(R.id.fingerprint_verification));

            View lineVView = new View(context);
            lineVView.setBackgroundColor(0xFFBBBBBB);

            LinearLayout buttonHLinearLayout = new LinearLayout(context);
            buttonHLinearLayout.setOrientation(LinearLayout.HORIZONTAL);
            buttonHLinearLayout.setWeightSum(2);


            Button cancelBtn = new Button(context);
            cancelBtn.setBackground(ViewUtil.genBackgroundDefaultDrawable());
            cancelBtn.setText(Lang.getString(R.id.cancel));
            StyleUtil.apply(cancelBtn);
            cancelBtn.setOnClickListener(view -> {
                AlertDialog dialog = mFingerPrintAlertDialog;
                if (dialog != null) {
                    dialog.dismiss();
                }
            });

            View lineHView = new View(context);
            lineHView.setBackgroundColor(0xFFBBBBBB);

            Button enterPassBtn = new Button(context);
            enterPassBtn.setBackground(ViewUtil.genBackgroundDefaultDrawable());
            enterPassBtn.setText(Lang.getString(R.id.enter_password));
            StyleUtil.apply(enterPassBtn);
            enterPassBtn.setOnClickListener(view -> {
                AlertDialog dialog = mFingerPrintAlertDialog;
                if (dialog != null) {
                    dialog.dismiss();
                }
            });

            buttonHLinearLayout.addView(cancelBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            buttonHLinearLayout.addView(lineHView, new LinearLayout.LayoutParams(1, LinearLayout.LayoutParams.MATCH_PARENT));
            buttonHLinearLayout.addView(enterPassBtn, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(DpUtil.dip2px(context, 60), DpUtil.dip2px(context, 60));
            params.topMargin = defVMargin;
            params.bottomMargin = defVMargin;
            rootVLinearLayout.addView(imageView, params);
            params = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = defVMargin;
            rootVLinearLayout.addView(textView, params);
            rootVLinearLayout.addView(lineVView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
            rootVLinearLayout.addView(buttonHLinearLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DpUtil.dip2px(context, 60)));

            initFingerPrintLock(context, () -> {
                BlackListUtils.applyIfNeeded(context);
                String pwd = Config.from(activity).getPassword();
                if (TextUtils.isEmpty(pwd)) {
                    Toast.makeText(activity, Lang.getString(R.id.toast_password_not_set_alipay), Toast.LENGTH_SHORT).show();
                    return;
                }

                Runnable onCompleteRunnable = () -> {
                    mPwdActivityReShowDelayTimeMsec = 1000;
                    AlertDialog dialog = mFingerPrintAlertDialog;
                    if (dialog != null) {
                        dialog.dismiss();
                    }
                };

                if (!tryInputGenericPassword(activity, pwd)) {
                    boolean tryAgain = false;
                    try {
                        inputDigitPassword(activity, pwd);
                    } catch (NullPointerException e) {
                        tryAgain = true;
                    } catch (Exception e) {
                        Toast.makeText(context, Lang.getString(R.id.toast_password_auto_enter_fail), Toast.LENGTH_LONG).show();
                        L.e(e);
                    }
                    if (tryAgain) {
                        Task.onMain(1000, ()-> {
                            try {
                                inputDigitPassword(activity, pwd);
                            } catch (NullPointerException e) {
                                Toast.makeText(context, Lang.getString(R.id.toast_password_auto_enter_fail), Toast.LENGTH_LONG).show();
                            } catch (Exception e) {
                                Toast.makeText(context, Lang.getString(R.id.toast_password_auto_enter_fail), Toast.LENGTH_LONG).show();
                                L.e(e);
                            }
                            onCompleteRunnable.run();
                        });
                        return;
                    }
                }
                onCompleteRunnable.run();
            });

            AlertDialog dialog = new AlertDialog.Builder(new ContextThemeWrapper(context, android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q ? android.R.style.Theme_DeviceDefault_DayNight : android.R.style.Theme_Holo_Light_Dialog_MinWidth))
                    .setView(rootVLinearLayout).setOnDismissListener(dialogInterface -> {
                FingerprintIdentify fingerprintIdentify = mFingerprintIdentify;
                if (fingerprintIdentify != null) {
                    fingerprintIdentify.cancelIdentify();
                }
                if (!mPwdActivityDontShowFlag) {
                    Task.onMain(mPwdActivityReShowDelayTimeMsec, () -> activity.getWindow().getDecorView().setAlpha(1));
                }
                try {
                    bitmap.recycle();
                } catch (Exception e) {
                }
            }).setCancelable(false).create();
            mFingerPrintAlertDialog = dialog;
            Task.onMain(100,  dialog::show);
        } catch (OutOfMemoryError e) {
        }
    }

    /**
     * 感觉是淘宝出bug了, 控件的id 和Resources 获取到的id 不符
     */
    @Nullable
    private View findTaobaoVSettingsPageItemView(@NonNull ViewGroup rootView) {
        List<View> childViewList = new ArrayList<>();
        ViewUtil.getChildViews(rootView, childViewList);
        String packageName = rootView.getContext().getPackageName();
        String identifier = packageName + ":id/v_setting_page_item} ";
        ViewUtil.sortViewListByYPosition(childViewList);
        for (View childView: childViewList) {
            if (ViewUtil.getViewInfo(childView).contains(identifier)) {
                if (childView.isShown()) {
                    return childView;
                }
            }
        }
        return null;
    }

    private void doSettingsMenuInject(final Activity activity) {
        ViewGroup rootView = (ViewGroup) activity.getWindow().getDecorView();
        if (ViewUtil.findViewByText(rootView, Lang.getString(R.id.app_settings_name)) != null) {
            return;
        }
        View itemView = ViewUtil.findViewByName(activity, activity.getPackageName(), "v_setting_page_item");
        if (itemView == null) {
            itemView = findTaobaoVSettingsPageItemView(rootView);
        }
        LinearLayout linearLayout = (LinearLayout) itemView.getParent();
        linearLayout.setPadding(0, 0, 0, 0);
        List<ViewGroup.LayoutParams> childViewParamsList = new ArrayList<>();
        List<View> childViewList = new ArrayList<>();
        int childViewCount = linearLayout.getChildCount();
        for (int i = 0; i < childViewCount; i++) {
            View view = linearLayout.getChildAt(i);
            childViewList.add(view);
            childViewParamsList.add(view.getLayoutParams());
        }

        linearLayout.removeAllViews();

        if (mLineTopCon == null) {
            mLineTopCon = new LinearLayout(activity);
        } else {
            ViewUtil.removeFromSuperView(mLineTopCon);
        }
        mLineTopCon.setPadding(DpUtil.dip2px(activity, 12), 0, 0, 0);
        mLineTopCon.setBackgroundColor(Color.WHITE);

        View lineTopView = new View(activity);
        lineTopView.setBackgroundColor(0xFFDFDFDF);
        mLineTopCon.addView(lineTopView, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        if (mItemHlinearLayout == null) {
            mItemHlinearLayout = new LinearLayout(activity);
        } else {
            ViewUtil.removeFromSuperView(mItemHlinearLayout);
            mItemHlinearLayout.removeAllViews();
        }
        mItemHlinearLayout.setOrientation(LinearLayout.HORIZONTAL);
        mItemHlinearLayout.setWeightSum(1);
        mItemHlinearLayout.setBackground(ViewUtil.genBackgroundDefaultDrawable(Color.WHITE));
        mItemHlinearLayout.setGravity(Gravity.CENTER_VERTICAL);
        mItemHlinearLayout.setClickable(true);
        mItemHlinearLayout.setOnClickListener(view -> new SettingsView(activity).showInDialog());


        TextView itemNameText = new TextView(activity);

        int defHPadding = DpUtil.dip2px(activity, 13);
        itemNameText.setTextColor(0xFF051B28);
        itemNameText.setText(Lang.getString(R.id.app_settings_name));
        itemNameText.setGravity(Gravity.CENTER_VERTICAL);
        itemNameText.setPadding(defHPadding, 0, 0, 0);
        itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, StyleUtil.TEXT_SIZE_BIG);

        TextView itemSummerText = new TextView(activity);
        StyleUtil.apply(itemSummerText);
        itemSummerText.setText(BuildConfig.VERSION_NAME);
        itemSummerText.setGravity(Gravity.CENTER_VERTICAL);
        itemSummerText.setPadding(0, 0, defHPadding, 0);
        itemSummerText.setTextColor(0xFF999999);

        //try use Taobao style
        try {
            View generalView = ViewUtil.findViewByText(activity.getWindow().getDecorView(), "通用", "General");
            L.d("generalView", generalView);
            if (generalView instanceof TextView) {
                TextView generalTextView = (TextView) generalView;
                float scale = itemNameText.getTextSize() / generalTextView.getTextSize();
                itemNameText.setTextSize(TypedValue.COMPLEX_UNIT_PX, generalTextView.getTextSize());
                itemSummerText.setTextSize(TypedValue.COMPLEX_UNIT_PX, itemSummerText.getTextSize() / scale);
                itemNameText.setTextColor(generalTextView.getCurrentTextColor());
            }
        } catch (Exception e) {
            L.e(e);
        }

        mItemHlinearLayout.addView(itemNameText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1));
        mItemHlinearLayout.addView(itemSummerText, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (mLineBottomView == null) {
            mLineBottomView = new View(activity);
        } else {
            ViewUtil.removeFromSuperView(mLineBottomView);
        }
        mLineBottomView.setBackgroundColor(0xFFDFDFDF);

        linearLayout.addView(mLineTopCon, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        linearLayout.addView(mItemHlinearLayout, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, DpUtil.dip2px(activity, 44)));
        LinearLayout.LayoutParams lineParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1);
        lineParams.bottomMargin = DpUtil.dip2px(activity, 10);
        linearLayout.addView(mLineBottomView, lineParams);

        for (int i = 0; i < childViewCount; i++) {
            View view = childViewList.get(i);
            ViewGroup.LayoutParams params = childViewParamsList.get(i);
            linearLayout.addView(view, params);
        }
    }

    private void inputDigitPassword(Activity activity, String password) {
        TaobaoVersionControl.DigitPasswordKeyPad digitPasswordKeyPad = TaobaoVersionControl.getDigitPasswordKeyPad(mTaobaoVersionCode);
        View ks[] = new View[] {
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key1),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key2),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key3),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key4),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key5),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key6),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key7),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key8),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key9),
                ViewUtil.findViewByName(activity, digitPasswordKeyPad.modulePackageName, digitPasswordKeyPad.key0),
        };
        char[] chars = password.toCharArray();
        for (char c : chars) {
            View v;
            switch (c) {
                case '1':
                    v = ks[0];
                    break;
                case '2':
                    v = ks[1];
                    break;
                case '3':
                    v = ks[2];
                    break;
                case '4':
                    v = ks[3];
                    break;
                case '5':
                    v = ks[4];
                    break;
                case '6':
                    v = ks[5];
                    break;
                case '7':
                    v = ks[6];
                    break;
                case '8':
                    v = ks[7];
                    break;
                case '9':
                    v = ks[8];
                    break;
                case '0':
                    v = ks[9];
                    break;
                default:
                    continue;
            }
            ViewUtil.performActionClick(v);
        }
    }

    private boolean tryInputGenericPassword(Activity activity, String password) {
        EditText pwdEditText = findPasswordEditText(activity);
        L.d("pwdEditText", pwdEditText);
        if (pwdEditText == null) {
            return false;
        }
        View confirmPwdBtn = findConfirmPasswordBtn(activity);
        L.d("confirmPwdBtn", confirmPwdBtn);
        if (confirmPwdBtn == null) {
            return false;
        }
        pwdEditText.setText(password);
        confirmPwdBtn.performClick();
        return true;
    }

    private EditText findPasswordEditText(Activity activity) {
        View pwdEditText = ViewUtil.findViewByName(activity, "com.taobao.taobao", "input_et_password");;
        if (pwdEditText instanceof EditText) {
            if (!pwdEditText.isShown()) {
                return null;
            }
            return (EditText) pwdEditText;
        }
        ViewGroup viewGroup = (ViewGroup)activity.getWindow().getDecorView();
        List<View> outList = new ArrayList<>();
        ViewUtil.getChildViews(viewGroup, "", outList);
        for (View view : outList) {
            if (view instanceof EditText) {
                if (view.getId() != -1) {
                    continue;
                }
                if (!view.isShown()) {
                    continue;
                }
                return (EditText) view;
            }
        }
        return null;
    }

    private View findConfirmPasswordBtn(Activity activity) {
        View okView =  ViewUtil.findViewByName(activity, "com.taobao.taobao", "button_ok");
        if (okView != null) {
            if (!okView.isShown()) {
                return null;
            }
            return okView;
        }
        ViewGroup viewGroup = (ViewGroup)activity.getWindow().getDecorView();
        List<View> outList = new ArrayList<>();
        ViewUtil.getChildViews(viewGroup, "确认", outList);
        if (outList.isEmpty()) {
            ViewUtil.getChildViews(viewGroup, "付款", outList);
        }
        if (outList.isEmpty()) {
            ViewUtil.getChildViews(viewGroup, "確認", outList);
        }
        if (outList.isEmpty()) {
            ViewUtil.getChildViews(viewGroup, "Pay", outList);
        }
        for (View view : outList) {
            if (view.getId() != -1) {
                continue;
            }
            if (!view.isShown()) {
                continue;
            }
            return (View) view.getParent();
        }
        return null;
    }
}
