/*
 * Copyright (C) 2012 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package com.google.android.marvin.screenspeak;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.accessibility.AccessibilityNodeInfo;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.annotation.TargetApi;
import android.app.AlertDialog;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.DialogInterface.OnDismissListener;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.SharedPreferences.OnSharedPreferenceChangeListener;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.os.Build;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.support.v4.app.NotificationCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.telephony.TelephonyManager;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import com.android.switchaccess.SwitchAccessService;
import com.android.screenspeak.Analytics;
import com.android.screenspeak.BatteryMonitor;
import com.android.screenspeak.CallStateMonitor;
import com.android.screenspeak.KeyComboManager;
import com.android.screenspeak.KeyboardSearchManager;
import com.android.screenspeak.OrientationMonitor;
import com.android.screenspeak.R;
import com.android.screenspeak.RingerModeAndScreenMonitor;
import com.android.screenspeak.SavedNode;
import com.android.screenspeak.ShakeDetector;
import com.android.screenspeak.ShortcutProxyActivity;
import com.android.screenspeak.SideTapManager;
import com.android.screenspeak.SpeechController;
import com.android.screenspeak.ScreenSpeakAnalytics;
import com.android.screenspeak.ScreenSpeakPreferencesActivity;
import com.android.screenspeak.ScreenSpeakUpdateHelper;
import com.android.screenspeak.VolumeMonitor;
import com.android.screenspeak.contextmenu.ListMenuManager;
import com.android.screenspeak.contextmenu.MenuManager;
import com.android.screenspeak.contextmenu.MenuManagerWrapper;
import com.android.screenspeak.contextmenu.RadialMenuManager;
import com.android.screenspeak.contextmenu.ScreenSpeakRadialMenuClient;
import com.android.screenspeak.controller.CursorController;
import com.android.screenspeak.controller.CursorControllerApp;
import com.android.screenspeak.controller.DimScreenController;
import com.android.screenspeak.controller.DimScreenControllerApp;
import com.android.screenspeak.controller.FeedbackController;
import com.android.screenspeak.controller.FeedbackControllerApp;
import com.android.screenspeak.controller.FullScreenReadController;
import com.android.screenspeak.controller.FullScreenReadControllerApp;
import com.android.screenspeak.controller.GestureController;
import com.android.screenspeak.controller.GestureControllerApp;
import com.android.screenspeak.eventprocessor.AccessibilityEventProcessor;
import com.android.screenspeak.eventprocessor.AccessibilityEventProcessor.ScreenSpeakListener;
import com.android.screenspeak.eventprocessor.ProcessorEventQueue;
import com.android.screenspeak.eventprocessor.ProcessorFocusAndSingleTap;
import com.android.screenspeak.eventprocessor.ProcessorGestureVibrator;
import com.android.screenspeak.eventprocessor.ProcessorAccessibilityHints;
import com.android.screenspeak.eventprocessor.ProcessorPhoneticLetters;
import com.android.screenspeak.eventprocessor.ProcessorScrollPosition;
import com.android.screenspeak.eventprocessor.ProcessorVolumeStream;
import com.android.screenspeak.eventprocessor.ProcessorWebContent;
import com.android.screenspeak.controller.TextCursorController;
import com.android.screenspeak.controller.TextCursorControllerApp;
import com.android.screenspeak.speechrules.NodeHintRule;
import com.android.screenspeak.speechrules.NodeSpeechRuleProcessor;
import com.android.screenspeak.tutorial.AccessibilityTutorialActivity;
import com.android.utils.AccessibilityEventListener;
import com.android.utils.AccessibilityNodeInfoUtils;
import com.android.utils.LogUtils;
import com.android.utils.PerformActionUtils;
import com.android.utils.SharedPreferencesUtils;
import com.android.utils.WebInterfaceUtils;
import com.android.utils.labeling.CustomLabelManager;
import com.android.utils.labeling.PackageRemovalReceiver;

import java.lang.Thread.UncaughtExceptionHandler;
import java.util.LinkedList;
import java.util.List;

/**
 * An {@link AccessibilityService} that provides spoken, haptic, and audible
 * feedback.
 */
public class ScreenSpeakService extends AccessibilityService
        implements Thread.UncaughtExceptionHandler {
    /** Whether the user has seen the ScreenSpeak tutorial. */
    public static final String PREF_FIRST_TIME_USER = "first_time_user";

    /** Permission required to perform gestures. */
    public static final String PERMISSION_SCREENSPEAK =
            "com.google.android.marvin.feedback.permission.SCREENSPEAK";

    /** The intent action used to perform a custom gesture action. */
    public static final String ACTION_PERFORM_GESTURE_ACTION = "performCustomGestureAction";

    /**
     * The gesture action to pass with {@link #ACTION_PERFORM_GESTURE_ACTION} as a string extra.
     * Expected to be the name of the shortcut pref value, like R.strings.shortcut_value_previous
     */
    public static final String EXTRA_GESTURE_ACTION = "gestureAction";

    /** Whether the current SDK supports service-managed web scripts. */
    private static final boolean SUPPORTS_WEB_SCRIPT_TOGGLE = (Build.VERSION.SDK_INT >= 18);

    /** Action used to resume feedback. */
    private static final String ACTION_RESUME_FEEDBACK =
            "com.google.android.marvin.screenspeak.RESUME_FEEDBACK";

    /** An active instance of ScreenSpeak. */
    private static ScreenSpeakService sInstance = null;

    /** The possible states of the service. */
    /** The state of the service before the system has bound to it or after it is destroyed. */
    public static final int SERVICE_STATE_INACTIVE = 0;
    /** The state of the service when it initialized and active. */
    public static final int SERVICE_STATE_ACTIVE = 1;
    /** The state of the service when it has been suspended by the user. */
    public static final int SERVICE_STATE_SUSPENDED = 2;

    private final static String LOGTAG = "ScreenSpeakService";

    /**
     * List of key event processors. Processors in the list are sent the event
     * in the order they were added until a processor consumes the event.
     */
    private final LinkedList<KeyEventListener> mKeyEventListeners = new LinkedList<>();

    /** The current state of the service. */
    private int mServiceState;

    /** Components to receive callbacks on changes in the service's state. */
    private List<ServiceStateListener> mServiceStateListeners = new LinkedList<>();

    /** Controller for cursor movement. */
    private CursorControllerApp mCursorController;

    /** Controller for speech feedback. */
    private SpeechController mSpeechController;

    /** Controller for audio and haptic feedback. */
    private FeedbackController mFeedbackController;

    /** Controller for reading the entire hierarchy. */
    private FullScreenReadControllerApp mFullScreenReadController;

    /** Controller for monitoring current and previous cursor position in editable node */
    private TextCursorController mTextCursorController;

    /** Controller for manage keyboard commands */
    private KeyComboManager mKeyComboManager;

    /** Listener for device shake events. */
    private ShakeDetector mShakeDetector;

    /** Manager for side tap events */
    private SideTapManager mSideTapManager;

    /** Manager for showing radial menus. */
    private MenuManagerWrapper mMenuManager;

    /** Manager for handling custom labels. */
    private CustomLabelManager mLabelManager;

    /** Manager for keyboard search. */
    private KeyboardSearchManager mKeyboardSearchManager;

    /** Processor for moving access focus. Used in Jelly Bean and above. */
    private ProcessorFocusAndSingleTap mProcessorFollowFocus;

    /** Orientation monitor for watching orientation changes. */
    private OrientationMonitor mOrientationMonitor;

    /** {@link BroadcastReceiver} for tracking the ringer and screen states. */
    private RingerModeAndScreenMonitor mRingerModeAndScreenMonitor;

    /** {@link BroadcastReceiver} for tracking the call state. */
    private CallStateMonitor mCallStateMonitor;

    /** {@link BroadcastReceiver} for tracking volume changes. */
    private VolumeMonitor mVolumeMonitor;

    /** {@link android.content.BroadcastReceiver} for tracking battery status changes. */
    private BatteryMonitor mBatteryMonitor;

    /** Manages screen dimming */
    private DimScreenController mDimScreenController;

    /**
     * {@link BroadcastReceiver} for tracking package removals for custom label
     * data consistency.
     */
    private PackageRemovalReceiver mPackageReceiver;

    /** The analytics instance, used for sending data to Google Analytics. */
    private Analytics mAnalytics;

    private GestureController mGestureController;

    /** Alert dialog shown when the user attempts to suspend feedback. */
    private AlertDialog mSuspendDialog;

    /** Shared preferences used within ScreenSpeak. */
    private SharedPreferences mPrefs;

    /** The system's uncaught exception handler */
    private UncaughtExceptionHandler mSystemUeh;

    /** The node that was focused during the last call to {@link #saveFocusedNode()} */
    private SavedNode mSavedNode = new SavedNode();

    /** The system feature if the device supports touch screen */
    private boolean mSupportsTouchScreen = true;

    /** Preference specifying when ScreenSpeak should automatically resume. */
    private String mAutomaticResume;

    /**
     * Whether the current root node is dirty or not.
     **/
    private boolean mIsRootNodeDirty = true;
    /**
     * Keep Track of current root node.
     */
    private AccessibilityNodeInfo mRootNode;

    private AccessibilityEventProcessor mAccessibilityEventProcessor;

    @Override
    public void onCreate() {
        super.onCreate();

        sInstance = this;
        setServiceState(SERVICE_STATE_INACTIVE);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mSystemUeh = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler(this);

        mAccessibilityEventProcessor = new AccessibilityEventProcessor(this);
        initializeInfrastructure();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        if (isServiceActive()) {
            suspendInfrastructure();
        }

        sInstance = null;

        // Shutdown and unregister all components.
        shutdownInfrastructure();
        setServiceState(SERVICE_STATE_INACTIVE);
        mServiceStateListeners.clear();
    }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        if (isServiceActive() && (mOrientationMonitor != null)) {
            mOrientationMonitor.onConfigurationChanged(newConfig);
        }

        // Clear the radial menu cache to reload localized strings.
        mMenuManager.clearCache();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        mAccessibilityEventProcessor.onAccessibilityEvent(event);
    }

    public boolean supportsTouchScreen() {
        return mSupportsTouchScreen;
    }

    @Override
    public AccessibilityNodeInfo getRootInActiveWindow() {
        if(mIsRootNodeDirty || mRootNode == null) {
            mRootNode = super.getRootInActiveWindow();
            mIsRootNodeDirty = false;
        }
        return mRootNode == null ? null : AccessibilityNodeInfo.obtain(mRootNode);
    }

    public void setRootDirty(boolean rootIsDirty) {
        mIsRootNodeDirty = rootIsDirty;
    }

    private void setServiceState(int newState) {
        if (mServiceState == newState) {
            return;
        }

        mServiceState = newState;
        for (ServiceStateListener listener : mServiceStateListeners) {
            listener.onServiceStateChanged(newState);
        }
    }

    @Override
    public AccessibilityNodeInfo findFocus(int focus) {
        if (Build.VERSION.SDK_INT >= 21) {
            return super.findFocus(focus);
        } else {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            return root == null ? null : root.findFocus(focus);
        }
    }

    public void addServiceStateListener(ServiceStateListener listener) {
        if (listener != null) {
            mServiceStateListeners.add(listener);
        }
    }

    public void removeServiceStateListener(ServiceStateListener listener) {
        if (listener != null) {
            mServiceStateListeners.remove(listener);
        }
    }

    /**
     * Suspends ScreenSpeak, showing a confirmation dialog if applicable.
     */
    public void requestSuspendScreenSpeak() {
        final boolean showConfirmation = SharedPreferencesUtils.getBooleanPref(mPrefs,
                getResources(), R.string.pref_show_suspension_confirmation_dialog,
                R.bool.pref_show_suspension_confirmation_dialog_default);
        if (showConfirmation) {
            confirmSuspendScreenSpeak();
        } else {
            suspendScreenSpeak();
        }
    }

    /**
     * Shows a dialog asking the user to confirm suspension of ScreenSpeak.
     */
    private void confirmSuspendScreenSpeak() {
        // Ensure only one dialog is showing.
        if (mSuspendDialog != null) {
            if (mSuspendDialog.isShowing()) {
                return;
            } else {
                mSuspendDialog.dismiss();
                mSuspendDialog = null;
            }
        }

        final LayoutInflater inflater = LayoutInflater.from(this);
        @SuppressLint("InflateParams") final ScrollView root = (ScrollView) inflater.inflate(
                R.layout.suspend_screenspeak_dialog, null);
        final CheckBox confirmCheckBox = (CheckBox) root.findViewById(R.id.show_warning_checkbox);
        final TextView message = (TextView) root.findViewById(R.id.message_resume);

        final DialogInterface.OnClickListener okayClick = new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                if (which == DialogInterface.BUTTON_POSITIVE) {
                    if (!confirmCheckBox.isChecked()) {
                        SharedPreferencesUtils.putBooleanPref(mPrefs, getResources(),
                                R.string.pref_show_suspension_confirmation_dialog, false);
                    }

                    suspendScreenSpeak();
                }
            }
        };

        final OnDismissListener onDismissListener = new OnDismissListener() {
            @Override
            public void onDismiss(DialogInterface dialog) {
                mSuspendDialog = null;
            }
        };

        if (mAutomaticResume.equals(getString(R.string.resume_screen_keyguard))) {
            message.setText(getString(R.string.message_resume_keyguard));
        } else if (mAutomaticResume.equals(getString(R.string.resume_screen_manual))) {
            message.setText(getString(R.string.message_resume_manual));
        } else { // screen on is the default value
            message.setText(getString(R.string.message_resume_screen_on));
        }

        mSuspendDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.dialog_title_suspend_screenspeak)
                .setView(root)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok, okayClick)
                .create();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            mSuspendDialog.getWindow().setType(
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
        } else {
            mSuspendDialog.getWindow().setType(WindowManager.LayoutParams.TYPE_SYSTEM_ERROR);
        }

        mSuspendDialog.setOnDismissListener(onDismissListener);
        mSuspendDialog.show();
    }

    /**
     * Suspends ScreenSpeak and Explore by Touch.
     */
    public void suspendScreenSpeak() {
        if (!isServiceActive()) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG, "Attempted to suspend ScreenSpeak while already suspended.");
            }
            return;
        }

        SharedPreferencesUtils.storeBooleanAsync(mPrefs, getString(R.string.pref_suspended), true);
        mFeedbackController.playAuditory(R.raw.paused_feedback);

        if (mSupportsTouchScreen) {
            requestTouchExploration(false);
        }

        if (mCursorController != null) {
            try {
                mCursorController.clearCursor();
            } catch (SecurityException e) {
                if (LogUtils.LOG_LEVEL >= Log.ERROR) {
                    Log.e(LOGTAG, "Unable to clear cursor");
                }
            }
        }

        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_RESUME_FEEDBACK);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        registerReceiver(mSuspendedReceiver, filter, PERMISSION_SCREENSPEAK, null);

        // Suspending infrastructure sets sIsScreenSpeakSuspended to true.
        suspendInfrastructure();

        final Intent resumeIntent = new Intent(ACTION_RESUME_FEEDBACK);
        final PendingIntent pendingIntent = PendingIntent.getBroadcast(this, 0, resumeIntent, 0);
        final Notification notification = new NotificationCompat.Builder(this)
                .setContentTitle(getString(R.string.notification_title_screenspeak_suspended))
                .setContentText(getString(R.string.notification_message_screenspeak_suspended))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setSmallIcon(R.drawable.ic_stat_info)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setWhen(0)
                .build();

        startForeground(R.id.notification_suspended, notification);

        mSpeechController.speak(getString(R.string.screenspeak_suspended),
                SpeechController.QUEUE_MODE_UNINTERRUPTIBLE, 0, null);
    }

    /**
     * Resumes ScreenSpeak and Explore by Touch.
     */
    public void resumeScreenSpeak() {
        if (isServiceActive()) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG, "Attempted to resume ScreenSpeak when not suspended.");
            }
            return;
        }

        SharedPreferencesUtils.storeBooleanAsync(mPrefs, getString(R.string.pref_suspended), false);

        unregisterReceiver(mSuspendedReceiver);
        resumeInfrastructure();

        mSpeechController.speak(getString(R.string.screenspeak_resumed),
                SpeechController.QUEUE_MODE_UNINTERRUPTIBLE, 0, null);
    }

    /**
     * Intended to mimic the behavior of onKeyEvent if this were the only service running.
     * It will be called from onKeyEvent, both from this service and from others in this apk
     * (ScreenSpeak). This method must not block, since it will block onKeyEvent as well.
     * @param keyEvent A key event
     * @return {@code true} if the event is handled, {@code false} otherwise.
     */
    public boolean onKeyEventShared(KeyEvent keyEvent) {
        for (KeyEventListener listener : mKeyEventListeners) {
            if (!isServiceActive() && !listener.processWhenServiceSuspended()) {
                continue;
            }

            if (listener.onKeyEvent(keyEvent)) {
                return true;
            }
        }

        return false;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent keyEvent) {
        boolean keyHandled = onKeyEventShared(keyEvent);
        SwitchAccessService switchAccessService = SwitchAccessService.getInstance();
        if (switchAccessService != null) {
            keyHandled = switchAccessService.onKeyEventShared(keyEvent) || keyHandled;
        }
        return keyHandled;
    }

    @Override
    protected boolean onGesture(int gestureId) {
        if (!isServiceActive()) return false;

        if (LogUtils.LOG_LEVEL <= Log.VERBOSE) {
            Log.v(LOGTAG, String.format("Recognized gesture %s", gestureId));
        }

        if (mKeyboardSearchManager != null && mKeyboardSearchManager.onGesture()) return true;
        mAnalytics.onGesture(gestureId);
        mFeedbackController.playAuditory(R.raw.gesture_end);

        // Gestures always stop global speech on API 16. On API 17+ we silence
        // on TOUCH_INTERACTION_START.
        // TODO(KM): Will this negatively affect something like Books?
        if (Build.VERSION.SDK_INT <= 16) {
            interruptAllFeedback();
        }

        mMenuManager.onGesture(gestureId);
        mGestureController.onGesture(gestureId);
        return true;
    }

    public GestureController getGestureController() {
        if (mGestureController == null) {
            throw new RuntimeException("mGestureController has not been initialized");
        }

        return mGestureController;
    }

    public SpeechController getSpeechController() {
        if (mSpeechController == null) {
            throw new RuntimeException("mSpeechController has not been initialized");
        }

        return mSpeechController;
    }

    public FeedbackController getFeedbackController() {
        if (mFeedbackController == null) {
            throw new RuntimeException("mFeedbackController has not been initialized");
        }

        return mFeedbackController;
    }

    public CursorController getCursorController() {
        if (mCursorController == null) {
            throw new RuntimeException("mCursorController has not been initialized");
        }

        return mCursorController;
    }

    public TextCursorController getTextCursorController() {
        if (mTextCursorController == null) {
            throw new RuntimeException("mTextCursorController has not been initialized");
        }

        return mTextCursorController;
    }

    public KeyComboManager getKeyComboManager() {
        if (mKeyComboManager == null) {
            throw new RuntimeException("mKeyComboManager has not been initialized");
        }

        return mKeyComboManager;
    }

    public FullScreenReadController getFullScreenReadController() {
        if (mFullScreenReadController == null) {
            throw new RuntimeException("mFullScreenReadController has not been initialized");
        }

        return mFullScreenReadController;
    }

    public CustomLabelManager getLabelManager() {
        if (mLabelManager == null && Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL) {
            throw new RuntimeException("mLabelManager has not been initialized");
        }

        return mLabelManager;
    }
    
    public Analytics getAnalytics() {
        if (mAnalytics == null) {
            throw new RuntimeException("mAnalytics has not been initialized");
        }
        
        return mAnalytics;
    }

    /**
     * Obtains the shared instance of ScreenSpeak's {@link ShakeDetector}
     *
     * @return the shared {@link ShakeDetector} instance, or null if not initialized.
     */
    public ShakeDetector getShakeDetector() {
        return mShakeDetector;
    }

    /** Save the currently focused node so that focus can be returned to it later. */
    public void saveFocusedNode() {
        mSavedNode.recycle();

        AccessibilityNodeInfoCompat node = mCursorController.getCursor();
        if (node != null) {
            mSavedNode.saveNodeState(node, mCursorController.getGranularityAt(node));
            node.recycle();
        }
    }

    /**
     * Reset the accessibility focus to the node that was focused during the last call to
     * {@link #saveFocusedNode()}
     */
    public void resetFocusedNode() {
        resetFocusedNode(0);
    }

    public void resetFocusedNode(long delay) {
        final Handler handler = new Handler();
            handler.postDelayed(new Runnable() {
                @SuppressLint("InlinedApi")
                @Override
                public void run() {
                    AccessibilityNodeInfoCompat node = mSavedNode.getNode();
                    if (node == null) {
                        return;
                    }

                    AccessibilityNodeInfoCompat refreshed =
                            AccessibilityNodeInfoUtils.refreshNode(node);

                    if (refreshed != null) {
                        if (!refreshed.isAccessibilityFocused()) {
                            mCursorController.setGranularity(mSavedNode.getGranularity(),
                                    refreshed, false);
                            SavedNode.Selection selection = mSavedNode.getSelection();
                            if (selection != null) {
                                Bundle args = new Bundle();
                                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT,
                                        selection.start);
                                args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT,
                                        selection.end);
                                PerformActionUtils.performAction(refreshed,
                                        AccessibilityNodeInfoCompat.ACTION_SET_SELECTION, args);
                            }

                            PerformActionUtils.performAction(refreshed,
                                    AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
                        }

                        refreshed.recycle();
                    }

                    mSavedNode.recycle();
                }
            }, delay);
    }

    private void showGlobalContextMenu() {
        if (mSupportsTouchScreen) {
            mMenuManager.showMenu(R.menu.global_context_menu);
        }
    }

    private void showLocalContextMenu() {
        if (mSupportsTouchScreen) {
            mMenuManager.showMenu(R.menu.local_context_menu);
        }
    }

    @Override
    public void onInterrupt() {
        interruptAllFeedback();
    }

    public void interruptAllFeedback() {
        // Don't interrupt feedback if the tutorial is active.
        if (AccessibilityTutorialActivity.isTutorialActive()) {
            return;
        }

        // Instruct ChromeVox to stop speech and halt any automatic actions.
        if (mCursorController != null) {
            final AccessibilityNodeInfoCompat currentNode = mCursorController.getCursor();
            if (currentNode != null && WebInterfaceUtils.hasLegacyWebContent(currentNode)) {
                if (WebInterfaceUtils.isScriptInjectionEnabled(this)) {
                    WebInterfaceUtils.performSpecialAction(
                            currentNode, WebInterfaceUtils.ACTION_STOP_SPEECH);
                }
            }
        }

        if (mFullScreenReadController != null) {
            mFullScreenReadController.interrupt();
        }

        if (mSpeechController != null) {
            mSpeechController.interrupt();
        }

        if (mFeedbackController != null) {
            mFeedbackController.interrupt();
        }
    }

    @Override
    protected void onServiceConnected() {
        if (LogUtils.LOG_LEVEL <= Log.VERBOSE) {
            Log.v(LOGTAG, "System bound to service.");
        }
        resumeInfrastructure();

        // Handle any update actions.
        final ScreenSpeakUpdateHelper helper = new ScreenSpeakUpdateHelper(this);
        helper.showPendingNotifications();
        helper.checkUpdate();

        final ContentResolver resolver = getContentResolver();
        if (!ScreenSpeakPreferencesActivity.isTouchExplorationEnabled(resolver) || !showTutorial()) {
                startCallStateMonitor();
        }

        if (mPrefs.getBoolean(getString(R.string.pref_suspended), false)) {
            suspendScreenSpeak();
        } else {
            mSpeechController.speak(getString(R.string.screenspeak_on),
                    SpeechController.QUEUE_MODE_UNINTERRUPTIBLE, 0, null);
        }
    }

    /**
     * @return The current state of the ScreenSpeak service, or
     *         {@code INACTIVE} if the service is not initialized.
     */
    public static int getServiceState() {
        final ScreenSpeakService service = getInstance();
        if (service == null) {
            return SERVICE_STATE_INACTIVE;
        }

        return service.mServiceState;
    }

    /**
     * @return {@code true} if ScreenSpeak is running and initialized,
     *         {@code false} otherwise.
     */
    public static boolean isServiceActive() {
        return (getServiceState() == SERVICE_STATE_ACTIVE);
    }

    /**
     * Returns the active ScreenSpeak instance, or {@code null} if not available.
     */
    public static ScreenSpeakService getInstance() {
        return sInstance;
    }


    /**
     * Initializes the controllers, managers, and processors. This should only
     * be called once from {@link #onCreate}.
     */
    private void initializeInfrastructure() {
        // Initialize static instances that do not have dependencies.
        NodeSpeechRuleProcessor.initialize(this);

        final PackageManager packageManager = getPackageManager();
        final boolean deviceIsPhone = packageManager.hasSystemFeature(
                PackageManager.FEATURE_TELEPHONY);
        //TODO we still need it keep true for TV until TouchExplore and Accessibility focus is not
        //unpaired
        //mSupportsTouchScreen = packageManager.hasSystemFeature(PackageManager.FEATURE_TOUCHSCREEN);

        // Only initialize telephony and call state for phones.
        if (deviceIsPhone) {
            mCallStateMonitor = new CallStateMonitor(this);
            mAccessibilityEventProcessor.setCallStateMonitor(mCallStateMonitor);
        }

        mCursorController = new CursorControllerApp(this);
        addEventListener(mCursorController);

        mFeedbackController = new FeedbackControllerApp(this);
        mFullScreenReadController = new FullScreenReadControllerApp(mFeedbackController,
                mCursorController, this);
        addEventListener(mFullScreenReadController);
        mSpeechController = new SpeechController(this, mFeedbackController);
        mShakeDetector = new ShakeDetector(mFullScreenReadController, this);

        mMenuManager = new MenuManagerWrapper();
        updateMenuManager(mSpeechController, mFeedbackController); // Sets mMenuManager

        mRingerModeAndScreenMonitor = new RingerModeAndScreenMonitor(mFeedbackController,
                mMenuManager, mShakeDetector, mSpeechController, this);
        mAccessibilityEventProcessor.setRingerModeAndScreenMonitor(mRingerModeAndScreenMonitor);

        mGestureController = new GestureControllerApp(this,
                mCursorController, mFeedbackController, mFullScreenReadController, mMenuManager);

        mSideTapManager = new SideTapManager(this, mGestureController);
        addEventListener(mSideTapManager);
        mFeedbackController.addHapticFeedbackListener(mSideTapManager);

        mTextCursorController = new TextCursorControllerApp();
        addEventListener(mTextCursorController);

        // Add event processors. These will process incoming AccessibilityEvents
        // in the order they are added.
        ProcessorEventQueue processorEventQueue = new ProcessorEventQueue(mSpeechController, this);
        processorEventQueue.setTestingListener(mAccessibilityEventProcessor.getTestingListener());
        mAccessibilityEventProcessor.setProcessorEventQueue(processorEventQueue);

        addEventListener(processorEventQueue);
        addEventListener(
                new ProcessorScrollPosition(mFullScreenReadController, mSpeechController, this));
        addEventListener(new ProcessorAccessibilityHints(this, mSpeechController, mCursorController));
        addEventListener(new ProcessorPhoneticLetters(this, mSpeechController));

        mProcessorFollowFocus = new ProcessorFocusAndSingleTap(
                mCursorController, mFeedbackController, mSpeechController, this);
        addEventListener(mProcessorFollowFocus);
        if (mCursorController != null) {
            mCursorController.addScrollListener(mProcessorFollowFocus);
        }

        mVolumeMonitor = new VolumeMonitor(mSpeechController, this);
        mBatteryMonitor = new BatteryMonitor(this, mSpeechController,
                (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE));

        if (Build.VERSION.SDK_INT >= PackageRemovalReceiver.MIN_API_LEVEL) {
            // TODO(KM): Move this into the custom label manager code
            mPackageReceiver = new PackageRemovalReceiver();
        }

        if (Build.VERSION.SDK_INT >= ProcessorGestureVibrator.MIN_API_LEVEL) {
            addEventListener(new ProcessorGestureVibrator(mFeedbackController));
        }

        addEventListener(new ProcessorWebContent(this));

        DimScreenControllerApp dimScreenController = new DimScreenControllerApp(this);
        mDimScreenController = dimScreenController;

        if (Build.VERSION.SDK_INT >= ProcessorVolumeStream.MIN_API_LEVEL) {
            ProcessorVolumeStream processorVolumeStream =
                    new ProcessorVolumeStream(mFeedbackController, mCursorController,
                            mDimScreenController, this);
            addEventListener(processorVolumeStream);
            mKeyEventListeners.add(processorVolumeStream);
        }

        if (Build.VERSION.SDK_INT >= CustomLabelManager.MIN_API_LEVEL) {
            mLabelManager = new CustomLabelManager(this);
        }

        if (Build.VERSION.SDK_INT >= KeyComboManager.MIN_API_LEVEL) {
            mKeyComboManager =  new KeyComboManager(this);
            mKeyComboManager.addListener(mKeyComboListener);
            // Search mode should receive key combos immediately after the ScreenSpeakService.
            if (Build.VERSION.SDK_INT >= KeyboardSearchManager.MIN_API_LEVEL) {
                mKeyboardSearchManager = new KeyboardSearchManager(this, mLabelManager);
                mKeyEventListeners.add(mKeyboardSearchManager);
                addEventListener(mKeyboardSearchManager);
                mKeyComboManager.addListener(mKeyboardSearchManager);
            }
            mKeyComboManager.addListener(mCursorController);
            mKeyEventListeners.add(mKeyComboManager);
        }

        addEventListener(mSavedNode);

        mOrientationMonitor = new OrientationMonitor(mSpeechController, this);
        mOrientationMonitor.addOnOrientationChangedListener(dimScreenController);

        mAnalytics = new ScreenSpeakAnalytics(this);
    }

    private void updateMenuManager(SpeechController speechController,
                                   FeedbackController cachedFeedbackController) {
        if (speechController == null) throw new IllegalStateException();
        if (cachedFeedbackController == null) throw new IllegalStateException();

        mMenuManager.dismissAll();
        MenuManager menuManager;

        if (SharedPreferencesUtils.getBooleanPref(mPrefs, getResources(),
                R.string.pref_show_context_menu_as_list_key, R.bool.pref_show_menu_as_list)) {
            menuManager = new ListMenuManager(this);
        } else {
            // Set up the radial menu manager and ScreenSpeak-specific client.
            final ScreenSpeakRadialMenuClient radialMenuClient = new ScreenSpeakRadialMenuClient(this);
            RadialMenuManager radialMenuManager =
                    new RadialMenuManager(
                            mSupportsTouchScreen, this, speechController, cachedFeedbackController);
            radialMenuManager.setClient(radialMenuClient);
            menuManager = radialMenuManager;
        }

        mMenuManager.setMenuManager(menuManager);
    }

    public MenuManager getMenuManager() {
        return mMenuManager;
    }

    /**
     * Registers listeners, sets service info, loads preferences. This should be
     * called from {@link #onServiceConnected} and when ScreenSpeak resumes from a
     * suspended state.
     */
    private void resumeInfrastructure() {
        if (isServiceActive()) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG, "Attempted to resume while not suspended");
            }
            return;
        }

        setServiceState(SERVICE_STATE_ACTIVE);
        stopForeground(true);

        final AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK;
        info.feedbackType |= AccessibilityServiceInfo.FEEDBACK_SPOKEN;
        info.feedbackType |= AccessibilityServiceInfo.FEEDBACK_AUDIBLE;
        info.feedbackType |= AccessibilityServiceInfo.FEEDBACK_HAPTIC;
        info.flags |= AccessibilityServiceInfo.DEFAULT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
            info.flags |= AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP_MR1) {
            info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        }
        info.notificationTimeout = 0;

        // Ensure the initial touch exploration request mode is correct.
        if (mSupportsTouchScreen && SharedPreferencesUtils.getBooleanPref(
                mPrefs, getResources(), R.string.pref_explore_by_touch_key,
                R.bool.pref_explore_by_touch_default)) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        }

        setServiceInfo(info);

        if (mRingerModeAndScreenMonitor != null) {
            registerReceiver(mRingerModeAndScreenMonitor, mRingerModeAndScreenMonitor.getFilter());
            // It could now be confused with the current screen state
            mRingerModeAndScreenMonitor.updateScreenState();
        }

        if (mVolumeMonitor != null) {
            registerReceiver(mVolumeMonitor, mVolumeMonitor.getFilter());
        }

        if (mBatteryMonitor != null) {
            registerReceiver(mBatteryMonitor, mBatteryMonitor.getFilter());
        }

        if (mPackageReceiver != null) {
            registerReceiver(mPackageReceiver, mPackageReceiver.getFilter());
            if (mLabelManager != null) {
                mLabelManager.ensureDataConsistency();
            }
        }

        if (mSideTapManager != null) {
            registerReceiver(mSideTapManager, SideTapManager.getFilter());
        }

        mPrefs.registerOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);

        // Add the broadcast listener for gestures.
        final IntentFilter filter = new IntentFilter();
        filter.addAction(ACTION_PERFORM_GESTURE_ACTION);
        registerReceiver(mActiveReceiver, filter, PERMISSION_SCREENSPEAK, null);

        // Enable the proxy activity for long-press search.
        final PackageManager packageManager = getPackageManager();
        final ComponentName shortcutProxy = new ComponentName(this, ShortcutProxyActivity.class);
        packageManager.setComponentEnabledSetting(shortcutProxy,
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP);

        reloadPreferences();

        if (mDimScreenController.isDimmingEnabled()) {
            mDimScreenController.makeScreenDim();
        }
    }

    /**
     * Registers listeners, sets service info, loads preferences. This should be called from
     * {@link #onServiceConnected} and when ScreenSpeak resumes from a suspended state.
     */
    private void suspendInfrastructure() {
        if (!isServiceActive()) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG, "Attempted to suspend while already suspended");
            }
            return;
        }

        mDimScreenController.makeScreenBright();

        interruptAllFeedback();
        setServiceState(SERVICE_STATE_SUSPENDED);

        // Some apps depend on these being set to false when ScreenSpeak is disabled.
        if (mSupportsTouchScreen) {
            requestTouchExploration(false);
        }

        if (SUPPORTS_WEB_SCRIPT_TOGGLE) {
            requestWebScripts(false);
        }

        mPrefs.unregisterOnSharedPreferenceChangeListener(mSharedPreferenceChangeListener);

        unregisterReceiver(mActiveReceiver);

        if (mCallStateMonitor != null) {
            mCallStateMonitor.stopMonitor();
        }

        if (mRingerModeAndScreenMonitor != null) {
            unregisterReceiver(mRingerModeAndScreenMonitor);
        }

        if (mMenuManager != null) {
            mMenuManager.clearCache();
        }

        if (mVolumeMonitor != null) {
            unregisterReceiver(mVolumeMonitor);
            mVolumeMonitor.releaseControl();
        }

        if (mBatteryMonitor != null) {
            unregisterReceiver(mBatteryMonitor);
        }

        if (mPackageReceiver != null) {
            unregisterReceiver(mPackageReceiver);
        }

        if (mShakeDetector != null) {
            mShakeDetector.setEnabled(false);
        }

        // The tap detector is enabled through reloadPreferences
        if (mSideTapManager != null) {
            unregisterReceiver(mSideTapManager);
            mSideTapManager.onSuspendInfrastructure();
        }

        // Disable the proxy activity for long-press search.
        final PackageManager packageManager = getPackageManager();
        final ComponentName shortcutProxy = new ComponentName(this, ShortcutProxyActivity.class);
        packageManager.setComponentEnabledSetting(shortcutProxy,
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP);

        // Remove any pending notifications that shouldn't persist.
        final NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        nm.cancelAll();
    }

    /**
     * Shuts down the infrastructure in case it has been initialized.
     */
    private void shutdownInfrastructure() {
        // we put it first to be sure that screen dimming would be removed even if code bellow
        // will crash by any reason. Because leaving user with dimmed screen is super bad
        mDimScreenController.shutdown();

        if (mCursorController != null) {
            mCursorController.shutdown();
        }

        if (mFullScreenReadController != null) {
            mFullScreenReadController.shutdown();
        }

        if (mLabelManager != null) {
            mLabelManager.shutdown();
        }

        mFeedbackController.shutdown();
        mSpeechController.shutdown();
    }

    /**
     * Adds an event listener.
     *
     * @param listener The listener to add.
     */
    public void addEventListener(AccessibilityEventListener listener) {
        mAccessibilityEventProcessor.addAccessibilityEventListener(listener);
    }

    /**
     * Posts a {@link Runnable} to removes an event listener. This is safe to
     * call from inside {@link AccessibilityEventListener#onAccessibilityEvent(AccessibilityEvent)}.
     *
     * @param listener The listener to remove.
     */
    public void postRemoveEventListener(final AccessibilityEventListener listener) {
        mAccessibilityEventProcessor.postRemoveAccessibilityEventListener(listener);
    }

    /**
     * Reloads service preferences.
     */
    private void reloadPreferences() {
        final Resources res = getResources();

        mAccessibilityEventProcessor.setSpeakCallerId(SharedPreferencesUtils.getBooleanPref(
                mPrefs, res, R.string.pref_caller_id_key, R.bool.pref_caller_id_default));
        mAccessibilityEventProcessor.setSpeakWhenScreenOff(SharedPreferencesUtils.getBooleanPref(
                mPrefs, res, R.string.pref_screenoff_key, R.bool.pref_screenoff_default));

        mAutomaticResume = mPrefs.getString(res.getString(R.string.pref_resume_screenspeak_key),
                getString(R.string.resume_screen_on));

        final boolean silenceOnProximity = SharedPreferencesUtils.getBooleanPref(
                mPrefs, res, R.string.pref_proximity_key, R.bool.pref_proximity_default);
        mSpeechController.setSilenceOnProximity(silenceOnProximity);

        LogUtils.setLogLevel(
                SharedPreferencesUtils.getIntFromStringPref(mPrefs, res, R.string.pref_log_level_key, R.string.pref_log_level_default));

        if (mProcessorFollowFocus != null) {
            final boolean useSingleTap = SharedPreferencesUtils.getBooleanPref(
                    mPrefs, res, R.string.pref_single_tap_key, R.bool.pref_single_tap_default);

            mProcessorFollowFocus.setSingleTapEnabled(useSingleTap);

            // Update the "X to select" long-hover hint.
            NodeHintRule.NodeHintHelper.updateActionResId(useSingleTap);
        }

        if (mShakeDetector != null) {
            final int shakeThreshold = SharedPreferencesUtils.getIntFromStringPref(
                    mPrefs, res, R.string.pref_shake_to_read_threshold_key,
                    R.string.pref_shake_to_read_threshold_default);
            final boolean useShake = (shakeThreshold > 0) && ((mCallStateMonitor == null) || (
                    mCallStateMonitor.getCurrentCallState() == TelephonyManager.CALL_STATE_IDLE));

            mShakeDetector.setEnabled(useShake);
        }

        if (mSideTapManager != null) {
            mSideTapManager.onReloadPreferences();
        }

        if (mSupportsTouchScreen) {
            final boolean touchExploration = SharedPreferencesUtils.getBooleanPref(mPrefs, res,
                    R.string.pref_explore_by_touch_key, R.bool.pref_explore_by_touch_default);
            requestTouchExploration(touchExploration);
        }

        if (SUPPORTS_WEB_SCRIPT_TOGGLE) {
            final boolean requestWebScripts = SharedPreferencesUtils.getBooleanPref(mPrefs, res,
                    R.string.pref_web_scripts_key, R.bool.pref_web_scripts_default);
            requestWebScripts(requestWebScripts);
        }

        updateMenuManager(mSpeechController, mFeedbackController);
    }

    /**
     * Attempts to change the state of touch exploration.
     * <p>
     * Should only be called if {@link #mSupportsTouchScreen} is true.
     *
     * @param requestedState {@code true} to request exploration.
     */
    private void requestTouchExploration(boolean requestedState) {
        final AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG,
                        "Failed to change touch exploration request state, service info was null");
            }
            return;
        }

        final boolean currentState = (
                (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE) != 0);
        if (currentState == requestedState) {
            return;
        }

        if (requestedState) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        }

        setServiceInfo(info);
    }

    /**
     * Launches the touch exploration tutorial if necessary.
     */
    public boolean showTutorial() {
        if (!mPrefs.getBoolean(PREF_FIRST_TIME_USER, true)) {
            return false;
        }

        final Editor editor = mPrefs.edit();
        editor.putBoolean(PREF_FIRST_TIME_USER, false);
        editor.apply();

        final int touchscreenState = getResources().getConfiguration().touchscreen;

        if (touchscreenState != Configuration.TOUCHSCREEN_NOTOUCH
                && mSupportsTouchScreen) {
            final Intent tutorial = new Intent(this, AccessibilityTutorialActivity.class);
            tutorial.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            tutorial.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(tutorial);
            return true;
        }

        return false;
    }

    public void startCallStateMonitor() {
        if (mCallStateMonitor == null || mCallStateMonitor.isStarted()) {
            return;
        }

        mCallStateMonitor.startMonitor();
    }

    /**
     * Attempts to change the state of web script injection.
     * <p>
     * Should only be called if {@link #SUPPORTS_WEB_SCRIPT_TOGGLE} is true.
     *
     * @param requestedState {@code true} to request script injection,
     *            {@code false} otherwise.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
    private void requestWebScripts(boolean requestedState) {
        final AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) {
            if (LogUtils.LOG_LEVEL <= Log.ERROR) {
                Log.e(LOGTAG, "Failed to change web script injection request state, service info "
                        + "was null");
            }
            return;
        }

        final boolean currentState = (
                (info.flags & AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY)
                != 0);
        if (currentState == requestedState) {
            return;
        }

        if (requestedState) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
        } else {
            info.flags &= ~AccessibilityServiceInfo.FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY;
        }

        setServiceInfo(info);
    }

    private final KeyComboManager.KeyComboListener mKeyComboListener =
            new KeyComboManager.KeyComboListener() {
        @Override
        public boolean onComboPerformed(int id) {
            switch (id) {
                case KeyComboManager.ACTION_SUSPEND:
                    requestSuspendScreenSpeak();
                    return true;
                case KeyComboManager.ACTION_BACK:
                    ScreenSpeakService.this.performGlobalAction(GLOBAL_ACTION_BACK);
                    return true;
                case KeyComboManager.ACTION_HOME:
                    ScreenSpeakService.this.performGlobalAction(GLOBAL_ACTION_HOME);
                    return true;
                case KeyComboManager.ACTION_NOTIFICATION:
                    ScreenSpeakService.this.performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS);
                    return true;
                case KeyComboManager.ACTION_RECENTS:
                    ScreenSpeakService.this.performGlobalAction(GLOBAL_ACTION_RECENTS);
                    return true;
                case KeyComboManager.ACTION_GRANULARITY_INCREASE:
                    mCursorController.nextGranularity();
                    return true;
                case KeyComboManager.ACTION_GRANULARITY_DECREASE:
                    mCursorController.previousGranularity();
                    return true;
                case KeyComboManager.ACTION_READ_FROM_TOP:
                    mFullScreenReadController.startReadingFromBeginning();
                    return true;
                case KeyComboManager.ACTION_READ_FROM_NEXT_ITEM:
                    mFullScreenReadController.startReadingFromNextNode();
                    return true;
                case KeyComboManager.ACTION_GLOBAL_CONTEXT_MENU:
                    showGlobalContextMenu();
                    return true;
                case KeyComboManager.ACTION_LOCAL_CONTEXT_MENU:
                    showLocalContextMenu();
                    return true;
            }

            return false;
        }
    };

    /**
     * Reloads preferences whenever their values change.
     */
    private final OnSharedPreferenceChangeListener
            mSharedPreferenceChangeListener = new OnSharedPreferenceChangeListener() {
        @Override
        public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
            if (LogUtils.LOG_LEVEL <= Log.DEBUG) {
                Log.d(LOGTAG, "A shared preference changed: " + key);
            }
            reloadPreferences();
        }
    };

    /**
     * Broadcast receiver for actions that happen while the service is active.
     */
    private final BroadcastReceiver mActiveReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (ACTION_PERFORM_GESTURE_ACTION.equals(action)) {
                mGestureController.onGesture(
                        intent.getIntExtra(EXTRA_GESTURE_ACTION,
                                R.string.shortcut_value_unassigned));
            }
        }
    };

    /**
     * Broadcast receiver for actions that happen while the service is inactive.
     */
    private final BroadcastReceiver mSuspendedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();

            if (ACTION_RESUME_FEEDBACK.equals(action)) {
                resumeScreenSpeak();
            } else if (Intent.ACTION_SCREEN_ON.equals(action)) {
                if (mAutomaticResume.equals(getString(R.string.resume_screen_keyguard))) {
                    final KeyguardManager keyguard = (KeyguardManager) getSystemService(
                            Context.KEYGUARD_SERVICE);
                    if (keyguard.inKeyguardRestrictedInputMode()) {
                        resumeScreenSpeak();
                    }
                } else if (mAutomaticResume.equals(getString(R.string.resume_screen_on))) {
                    resumeScreenSpeak();
                }
            }
        }
    };

    public void onBootCompleted() {
        if (!isServiceActive() &&
                !mAutomaticResume.equals(getString(R.string.resume_screen_manual))) {
            resumeScreenSpeak();
        }
    }

    @Override
    public void uncaughtException(Thread thread, Throwable ex) {
        try {
            if (mDimScreenController != null) {
                mDimScreenController.shutdown();
            }

            if (mMenuManager != null && mMenuManager.isMenuShowing()) {
                mMenuManager.dismissAll();
            }

            if (mSuspendDialog != null) {
                mSuspendDialog.dismiss();
            }
        } catch (Exception e) {
            // Do nothing.
        } finally {
            if (mSystemUeh != null) {
                mSystemUeh.uncaughtException(thread, ex);
            }
        }
    }

    public void setTestingListener(ScreenSpeakListener testingListener) {
        mAccessibilityEventProcessor.setTestingListener(testingListener);
    }

    /**
     * Interface for receiving callbacks when the state of the ScreenSpeak service
     * changes.
     * <p>
     * Implementing controllers should note that this may be invoked even after
     * the controller was explicitly shut down by ScreenSpeak.
     * <p>
     * {@link ScreenSpeakService#addServiceStateListener(ServiceStateListener)}
     * {@link ScreenSpeakService#removeServiceStateListener(ServiceStateListener)}
     * {@link ScreenSpeakService#SERVICE_STATE_INACTIVE}
     * {@link ScreenSpeakService#SERVICE_STATE_ACTIVE}
     * {@link ScreenSpeakService#SERVICE_STATE_SUSPENDED}
     */
    public interface ServiceStateListener {
        void onServiceStateChanged(int newState);
    }

    /**
     * Interface for key event listeners.
     */
    public interface KeyEventListener {
        boolean onKeyEvent(KeyEvent event);
        boolean processWhenServiceSuspended();
    }
}
