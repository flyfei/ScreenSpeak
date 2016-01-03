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

package com.android.screenspeak.eventprocessor;

import android.util.Pair;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.os.Message;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.support.v4.view.accessibility.AccessibilityRecordCompat;
import android.util.Log;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebView;
import android.widget.EditText;
import com.android.screenspeak.R;
import com.android.screenspeak.SpeechController;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.controller.CursorController;
import com.android.screenspeak.controller.FeedbackController;
import com.android.screenspeak.tutorial.AccessibilityTutorialActivity;
import com.android.utils.AccessibilityEventListener;
import com.android.utils.AccessibilityNodeInfoUtils;
import com.android.utils.LogUtils;
import com.android.utils.NodeFilter;
import com.android.utils.PerformActionUtils;
import com.android.utils.WeakReferenceHandler;
import com.android.utils.WebInterfaceUtils;
import com.android.utils.compat.accessibilityservice.AccessibilityServiceCompatUtils;
import com.android.utils.traversal.OrderedTraversalStrategy;
import com.android.utils.traversal.TraversalStrategy;

import java.util.ArrayDeque;
import java.util.Iterator;

/**
 * Places focus in response to various {@link AccessibilityEvent} types,
 * including hover events, list scrolling, and placing input focus. Also handles
 * single-tap activation in response to touch interaction events.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class ProcessorFocusAndSingleTap implements AccessibilityEventListener,
        CursorController.ScrollListener {
    /** Single-tap requires JellyBean (API 17). */
    public static final int MIN_API_LEVEL_SINGLE_TAP = Build.VERSION_CODES.JELLY_BEAN_MR1;

    /** Whether refocusing is enabled. Requires API 17. */
    private static final boolean SUPPORTS_INTERACTION_EVENTS =
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1);

    /** The timeout after which an event is no longer considered a tap. */
    private static final long TAP_TIMEOUT = ViewConfiguration.getJumpTapTimeout();

    private static final int MAX_CACHED_FOCUSED_RECORD_QUEUE = 10;

    private static final int MOVING_UNDEFINED_DIRECTION = 0;
    private static final int MOVING_FORWARDS = 1;
    private static final int MOVING_BACKWARDS = -1;

    private final ScreenSpeakService mService;
    private final SpeechController mSpeechController;
    private final CursorController mCursorController;
    private final AccessibilityManager mAccessibilityManager;

    // The previous AccessibilityRecordCompat that failed to focus, but it is potentially
    // focusable when view scrolls, or window state changes.
    private final ArrayDeque<Pair<AccessibilityRecordCompat, Integer>>
        mCachedPotentiallyFocusableRecordQueue = new ArrayDeque<>(MAX_CACHED_FOCUSED_RECORD_QUEUE);

    private int mLastScrollAction = 0;
    private int mLastScrollFromIndex = -1;
    private int mLastScrollToIndex = -1;
    private int mLastScrollX = -1;
    private int mLastScrollY = -1;

    /**
     * Whether single-tap activation is enabled, always {@code false} on
     * versions prior to Jelly Bean MR1.
     */
    private boolean mSingleTapEnabled;

    /** The first focused item touched during the current touch interaction. */
    private AccessibilityNodeInfoCompat mFirstFocusedItem;

    private AccessibilityNodeInfoCompat mActionScrolledNode;
    private AccessibilityNodeInfoCompat mLastFocusedItem;

    /** The number of items focused during the current touch interaction. */
    private int mFocusedItems;

    /** Whether the current interaction may result in refocusing. */
    private boolean mMaybeRefocus;

    /** Whether the current interaction may result in a single tap. */
    private boolean mMaybeSingleTap;

    private FirstWindowFocusManager mFirstWindowFocusManager;

    public ProcessorFocusAndSingleTap(CursorController cursorController,
                                      FeedbackController feedbackController,
                                      SpeechController speechController,
                                      ScreenSpeakService service) {
        if (cursorController == null) throw new IllegalStateException();
        if (feedbackController == null) throw new IllegalStateException();
        if (speechController == null) throw new IllegalStateException();

        mService = service;
        mSpeechController = speechController;
        mCursorController = cursorController;
        mCursorController.addScrollListener(this);
        mHandler = new FollowFocusHandler(this, feedbackController);
        mAccessibilityManager = (AccessibilityManager) service.getSystemService(
                Context.ACCESSIBILITY_SERVICE);
        mFirstWindowFocusManager = new FirstWindowFocusManager();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (!mAccessibilityManager.isTouchExplorationEnabled()) {
            // Don't manage focus when touch exploration is disabled.
            return;
        }

        final AccessibilityRecordCompat record = AccessibilityEventCompat.asRecord(event);

        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_CLICKED:
                // Prevent conflicts between lift-to-type and single tap. This
                // is only necessary when a CLICKED event occurs during a touch
                // interaction sequence (e.g. before an INTERACTION_END event),
                // but it isn't harmful to call more often.
                cancelSingleTap();
                break;
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                if (!mFirstWindowFocusManager.shouldProcessFocusEvent(event)) {
                    return;
                }
                boolean isViewFocusedEvent =
                        (AccessibilityEvent.TYPE_VIEW_FOCUSED == event.getEventType());
                if (!setFocusOnView(record, isViewFocusedEvent)) {
                    // It is possible that the only speakable child of source node is invisible
                    // at the moment, but could be made visible when view scrolls, or window state
                    // changes. Cache it now. And try to focus on the cached record on:
                    // VIEW_SCROLLED, WINDOW_CONTENT_CHANGED, WINDOW_STATE_CHANGED.
                    // The above 3 are the events that could affect view visibility.
                    if(mCachedPotentiallyFocusableRecordQueue.size() ==
                            MAX_CACHED_FOCUSED_RECORD_QUEUE) {
                        mCachedPotentiallyFocusableRecordQueue.remove().first.recycle();
                    }

                    mCachedPotentiallyFocusableRecordQueue.add(
                            new Pair<>(AccessibilityRecordCompat.obtain(record),
                                    event.getEventType()));
                } else {
                    emptyCachedPotentialFocusQueue();
                }
                break;
            case AccessibilityEvent.TYPE_VIEW_HOVER_ENTER:
                final AccessibilityNodeInfoCompat touchedNode = record.getSource();
                try {
                    if ((touchedNode != null) && !setFocusFromViewHoverEnter(touchedNode)) {
                        mHandler.sendEmptyTouchAreaFeedbackDelayed(touchedNode);
                    }
                } finally {
                    AccessibilityNodeInfoUtils.recycleNodes(touchedNode);
                }

                break;
            case AccessibilityEvent.TYPE_VIEW_ACCESSIBILITY_FOCUSED:
                mHandler.cancelEmptyTouchAreaFeedback();
                AccessibilityNodeInfo source = event.getSource();
                if (source != null) {
                    AccessibilityNodeInfoCompat compatSource =
                            new AccessibilityNodeInfoCompat(source);
                    mLastFocusedItem = AccessibilityNodeInfoCompat.obtain(compatSource);
                }
                break;
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED:
                mFirstWindowFocusManager.registerWindowChange(event);
                handleWindowStateChange(event);
                break;
            case AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED:
                handleWindowContentChanged();
                break;
            case AccessibilityEvent.TYPE_VIEW_SCROLLED:
                handleViewScrolled(event, record);
                break;
            case AccessibilityEventCompat.TYPE_TOUCH_INTERACTION_START:
                // This event type only exists on API 17+ (JB MR1).
                handleTouchInteractionStart();
                break;
            case AccessibilityEventCompat.TYPE_TOUCH_INTERACTION_END:
                // This event type only exists on API 17+ (JB MR1).
                handleTouchInteractionEnd();
                break;
        }
    }

    private void emptyCachedPotentialFocusQueue() {
        if (mCachedPotentiallyFocusableRecordQueue.isEmpty()) {
            return;
        }

        for (Pair<AccessibilityRecordCompat, Integer> focusableRecord :
                mCachedPotentiallyFocusableRecordQueue) {
            focusableRecord.first.recycle();
        }
        mCachedPotentiallyFocusableRecordQueue.clear();
    }

    /**
     * Sets whether single-tap activation is enabled. If it is, the follow focus
     * processor needs to avoid re-focusing items that are already focused.
     *
     * @param enabled Whether single-tap activation is enabled.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    public void setSingleTapEnabled(boolean enabled) {
        mSingleTapEnabled = enabled;
    }

    private void handleWindowStateChange(AccessibilityEvent event) {
        if (mLastFocusedItem != null) {
            mLastFocusedItem.recycle();
            mLastFocusedItem = null;
        }

        clearScrollAction();
        mLastScrollFromIndex = -1;
        mLastScrollToIndex = -1;

        // Since we may get WINDOW_STATE_CHANGE events from the keyboard even
        // though the active window is still another app, only clear focus if
        // the event's window ID matches the cursor's window ID.
        final AccessibilityNodeInfoCompat cursor = mCursorController.getCursor();
        if ((cursor != null) && (cursor.getWindowId() == event.getWindowId())) {
            ensureFocusConsistency();
        }
        if(cursor != null) {
            cursor.recycle();
        }
        tryFocusCachedRecord();
    }

    private void handleWindowContentChanged() {
        mHandler.followContentChangedDelayed();

        tryFocusCachedRecord();
    }

    private void handleViewScrolled(AccessibilityEvent event, AccessibilityRecordCompat record) {
        AccessibilityNodeInfoCompat source = null;

        int movingDirection;
        boolean wasScrollAction = false;
        if (mActionScrolledNode != null) {
            source = record.getSource();
            if (source == null) return;
            if (source.equals(mActionScrolledNode)) {
                movingDirection = getScrollActionDirection(mLastScrollAction);
                wasScrollAction = mLastScrollAction != 0;
                clearScrollAction();
            } else {
                movingDirection = getScrollDirection(event);
            }
        } else {
            movingDirection = getScrollDirection(event);
        }

        followScrollEvent(source, record, movingDirection, wasScrollAction);

        mLastScrollFromIndex = record.getFromIndex();
        mLastScrollToIndex = record.getToIndex();
        mLastScrollX = record.getScrollX();
        mLastScrollY = record.getScrollY();

        tryFocusCachedRecord();
    }

    private int getScrollDirection(AccessibilityEvent event) {
        //check scroll of AdapterViews
        if (event.getFromIndex() > mLastScrollFromIndex ||
                event.getToIndex() > mLastScrollToIndex) {
            return MOVING_FORWARDS;
        } else if(event.getFromIndex() < mLastScrollFromIndex ||
                event.getToIndex() < mLastScrollToIndex) {
            return MOVING_BACKWARDS;
        }

        //check scroll of ScrollViews
        if (event.getScrollX() > mLastScrollX || event.getScrollY() > mLastScrollY) {
            return MOVING_FORWARDS;
        } else if (event.getScrollX() < mLastScrollX || event.getScrollY() < mLastScrollY) {
            return MOVING_BACKWARDS;
        }

        return MOVING_UNDEFINED_DIRECTION;
    }

    private int getScrollActionDirection(int scrollAction) {
        if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) {
            return MOVING_FORWARDS;
        }

        if (scrollAction == AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD) {
            return MOVING_BACKWARDS;
        }

        return MOVING_UNDEFINED_DIRECTION;
    }

    private void clearScrollAction() {
        mLastScrollAction = 0;
        if (mActionScrolledNode != null) {
            mActionScrolledNode.recycle();
        }

        mActionScrolledNode = null;
    }

    private void tryFocusCachedRecord() {
        if (mCachedPotentiallyFocusableRecordQueue.isEmpty()) {
            return;
        }

        Iterator<Pair<AccessibilityRecordCompat, Integer>> iterator =
                mCachedPotentiallyFocusableRecordQueue.descendingIterator();

        while(iterator.hasNext()) {
            Pair<AccessibilityRecordCompat, Integer> focusableRecord = iterator.next();
            AccessibilityRecordCompat record = focusableRecord.first;
            int eventType = focusableRecord.second;
            if (setFocusOnView(record,
                    eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED)) {
                emptyCachedPotentialFocusQueue();
                return;
            }
        }
    }

    private void followScrollEvent(AccessibilityNodeInfoCompat source,
                                   AccessibilityRecordCompat record,
                                   int movingDirection,
                                   boolean wasScrollAction) {
        AccessibilityNodeInfoCompat root = null;
        AccessibilityNodeInfoCompat accessibilityFocused = null;

        try {
            // First, see if we've already placed accessibility focus.
            root = AccessibilityServiceCompatUtils.getRootInAccessibilityFocusedWindow(mService);
            if (root == null) {
                return;
            }

            accessibilityFocused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            boolean validAccessibilityFocus = AccessibilityNodeInfoUtils.shouldFocusNode(
                    accessibilityFocused);
            // there are cases when scrollable container was scrolled and application set
            // focus on node that is on new container page. We should keep this focus
            boolean hasInputFocus = accessibilityFocused != null
                    && accessibilityFocused.isFocused();

            if (validAccessibilityFocus && (hasInputFocus || !wasScrollAction)) {
                // focused on valid node and scrolled not by scroll action
                // keep focus
                return;
            }

            if (validAccessibilityFocus) {
                // focused on valid node and scrolled by scroll action
                // focus on next focusable node
                if (source == null) {
                    source = record.getSource();
                    if (source == null) return;
                }
                if (!AccessibilityNodeInfoUtils.hasAncestor(accessibilityFocused, source)) {
                    return;
                }
                TraversalStrategy traversal = new OrderedTraversalStrategy(root);
                try {
                    focusNextFocusedNode(traversal, accessibilityFocused, movingDirection);
                } finally {
                    traversal.recycle();
                }
            } else {
                if (mLastFocusedItem == null) {
                    // there was no focus - don't set focus
                    return;
                }

                if (source == null) {
                    source = record.getSource();
                    if (source == null) return;
                }
                if (mLastFocusedItem.equals(source) ||
                        AccessibilityNodeInfoUtils.hasAncestor(mLastFocusedItem, source)) {

                    // There is no focus now, but it was on source node's child before
                    // Try focusing the appropriate child node.
                    if (tryFocusingChild(source, movingDirection)) {
                        return;
                    }

                    // Finally, try focusing the scrollable node itself.
                    tryFocusing(source);
                }
            }
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(root, accessibilityFocused);
        }
    }

    private boolean focusNextFocusedNode(TraversalStrategy traversal,
                                         AccessibilityNodeInfoCompat node,
                                         int direction) {
        if (node == null) {
            return false;
        }

        NodeFilter filter = new NodeFilter() {
            @Override
            public boolean accept(AccessibilityNodeInfoCompat node) {
                return node != null && AccessibilityNodeInfoUtils.shouldFocusNode(node) &&
                        PerformActionUtils.performAction(node,
                                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS);
            }
        };

        AccessibilityNodeInfoCompat candidateFocus = AccessibilityNodeInfoUtils.searchFocus(
                traversal, node, direction, filter);

        return candidateFocus != null;
    }

    /**
     * @param record the AccessbilityRecord for the event
     * @param isViewFocusedEvent true if the event is TYPE_VIEW_FOCUSED, otherwise it is
     * TYPE_VIEW_SELECTED.
     */
    private boolean setFocusOnView(AccessibilityRecordCompat record, boolean isViewFocusedEvent) {
        AccessibilityNodeInfoCompat source = null;
        AccessibilityNodeInfoCompat existing = null;
        AccessibilityNodeInfoCompat child = null;

        try {
            source = record.getSource();
            if (source == null) {
                return false;
            }

            if (record.getItemCount() > 0) {
                final int index = (record.getCurrentItemIndex() - record.getFromIndex());
                if (index >= 0 && index < source.getChildCount()) {
                    child = source.getChild(index);
                    if (child != null) {
                        if (AccessibilityNodeInfoUtils.isTopLevelScrollItem(child) &&
                                tryFocusing(child)) {
                            return true;
                        }
                    }
                }
            }

            if (!isViewFocusedEvent) {
                return false;
            }

            // Logic below is only specific to TYPE_VIEW_FOCUSED event
            // Try focusing the source node.
            if (tryFocusing(source)) {
                return true;
            }

            // If we fail and the source node already contains focus, abort.
            existing = source.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (existing != null) {
                return false;
            }

            // If we fail to focus a node, perhaps because it is a focusable
            // but non-speaking container, we should still attempt to place
            // focus on a speaking child within the container.
            child = AccessibilityNodeInfoUtils.searchFromBfs(source,
                    AccessibilityNodeInfoUtils.FILTER_SHOULD_FOCUS);
            return child != null && tryFocusing(child);

        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(source, existing, child);
        }
    }

    /**
     * Attempts to place focus within a new window.
     */
    private boolean ensureFocusConsistency() {
        AccessibilityNodeInfoCompat root = null;
        AccessibilityNodeInfoCompat focused = null;

        try {
            root = AccessibilityServiceCompatUtils.getRootInAccessibilityFocusedWindow(mService);
            if (root == null) {
                return false;
            }

            // First, see if we've already placed accessibility focus.
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_ACCESSIBILITY);
            if (focused != null) {
                if (AccessibilityNodeInfoUtils.shouldFocusNode(focused)) {
                    return true;
                }

                LogUtils.log(Log.VERBOSE, "Clearing focus from invalid node");
                PerformActionUtils.performAction(focused,
                        AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS);
            }

            return false;
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(root, focused);
        }
    }

    /**
     * Handles the beginning of a new touch interaction event.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void handleTouchInteractionStart() {
        if (mFirstFocusedItem != null) {
            mFirstFocusedItem.recycle();
            mFirstFocusedItem = null;
        }

        if (mSpeechController.isSpeaking()) {
            mMaybeRefocus = false;

            final AccessibilityNodeInfoCompat currentNode = mCursorController.getCursor();
            // Don't silence speech on first touch if the tutorial is active
            // or if a WebView is active. This works around an issue where
            // the IME is unintentionally dismissed by WebView's
            // performAction implementation.
            if (!AccessibilityTutorialActivity.isTutorialActive()
                    && !AccessibilityNodeInfoUtils.nodeMatchesClassByType(currentNode,
                            WebView.class)) {
                mService.interruptAllFeedback();
            }
            AccessibilityNodeInfoUtils.recycleNodes(currentNode);
        } else {
            mMaybeRefocus = true;
        }

        mMaybeSingleTap = true;
        mFocusedItems = 0;
    }

    /**
     * Handles the end of an ongoing touch interaction event.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void handleTouchInteractionEnd() {
        if (mFirstFocusedItem == null) {
            return;
        }

        if (mSingleTapEnabled && mMaybeSingleTap) {
            mHandler.cancelRefocusTimeout(false);
            performClick(mFirstFocusedItem);
        }

        mFirstFocusedItem.recycle();
        mFirstFocusedItem = null;
    }

    /**
     * Attempts to place focus on an accessibility-focusable node, starting from
     * the {@code touchedNode}.
     */
    private boolean setFocusFromViewHoverEnter(AccessibilityNodeInfoCompat touchedNode) {
        AccessibilityNodeInfoCompat focusable = null;

        try {
            focusable = AccessibilityNodeInfoUtils.findFocusFromHover(touchedNode);
            if (focusable == null) {
                return false;
            }

            if (SUPPORTS_INTERACTION_EVENTS && (mFirstFocusedItem == null) && (mFocusedItems == 0)
                    && focusable.isAccessibilityFocused()) {
                mFirstFocusedItem = AccessibilityNodeInfoCompat.obtain(focusable);

                if (mSingleTapEnabled) {
                    mHandler.refocusAfterTimeout(focusable);
                    return false;
                }

                return attemptRefocusNode(focusable);
            }

            if (!tryFocusing(focusable)) {
                return false;
            }

            // If something received focus, single tap cannot occur.
            if (mSingleTapEnabled) {
                cancelSingleTap();
            }

            mFocusedItems++;

            return true;
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(focusable);
        }
    }

    /**
     * Ensures that a single-tap will not occur when the current touch
     * interaction ends.
     */
    @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
    private void cancelSingleTap() {
        mMaybeSingleTap = false;
    }

    private boolean attemptRefocusNode(AccessibilityNodeInfoCompat node) {
        if (!mMaybeRefocus || mSpeechController.isSpeaking()) {
            return false;
        }

        // Never refocus web content, it will just read the title again.
        return !WebInterfaceUtils.supportsWebActions(node)
                && PerformActionUtils.performAction(node,
                    AccessibilityNodeInfo.ACTION_CLEAR_ACCESSIBILITY_FOCUS)
                && tryFocusing(node);

    }

    private void followContentChangedEvent() {
        ensureFocusConsistency();
    }

    /**
     * If {@code wasMovingForward} is true, moves to the first focusable child.
     * Otherwise, moves to the last focusable child.
     */
    private boolean tryFocusingChild(AccessibilityNodeInfoCompat parent, int movingDirection) {
        AccessibilityNodeInfoCompat child = null;

        try {
            child = findChildFromNode(parent, movingDirection);
            return child != null && tryFocusing(child);

        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(child);
        }
    }

    /**
     * Returns the first focusable child found while traversing the child of the
     * specified node in a specific direction. Only traverses direct children.
     *
     * @param root The node to search within.
     * @param direction The direction to search, one of
     *            {@link ProcessorFocusAndSingleTap#MOVING_BACKWARDS} or
     *            {@link ProcessorFocusAndSingleTap#MOVING_FORWARDS}.
     * @return The first focusable child encountered in the specified direction.
     */
    private AccessibilityNodeInfoCompat findChildFromNode(AccessibilityNodeInfoCompat root,
                                                          int direction) {
        if (root == null || root.getChildCount() == 0) {
            return null;
        }

        final TraversalStrategy traversalStrategy = new OrderedTraversalStrategy(root);
        int traversalDirection = direction == MOVING_BACKWARDS ?
                TraversalStrategy.SEARCH_FOCUS_BACKWARD : TraversalStrategy.SEARCH_FOCUS_FORWARD;

        AccessibilityNodeInfoCompat pivotNode;
        if (traversalDirection == TraversalStrategy.SEARCH_FOCUS_BACKWARD) {
            pivotNode = traversalStrategy.focusLast(root);
        } else {
            pivotNode = AccessibilityNodeInfoCompat.obtain(root);
        }

        NodeFilter filter = new NodeFilter() {
            @Override
            public boolean accept(AccessibilityNodeInfoCompat node) {
                return node != null && AccessibilityNodeInfoUtils.shouldFocusNode(node,
                        traversalStrategy.getSpeakingNodesCache());
            }
        };

        try {
            return AccessibilityNodeInfoUtils.searchFocus(traversalStrategy, pivotNode, direction,
                    filter);
        } finally {
            if (pivotNode != null) {
                pivotNode.recycle();
            }
        }
    }

    private boolean tryFocusing(AccessibilityNodeInfoCompat source) {
        if (source == null) {
            return false;
        }

        if (!AccessibilityNodeInfoUtils.shouldFocusNode(source)) {
            return false;
        }

        if (!PerformActionUtils.performAction(source,
                AccessibilityNodeInfoCompat.ACTION_ACCESSIBILITY_FOCUS)) {
            return false;
        }

        mHandler.interruptFollowDelayed();
        return true;
    }

    private void performClick(AccessibilityNodeInfoCompat node) {
        // Performing a click on an EditText does not show the IME, so we need
        // to place input focus on it. If the IME was already connected and is
        // hidden, there is nothing we can do.
        if (AccessibilityNodeInfoUtils.nodeMatchesClassByType(node, EditText.class)) {
            PerformActionUtils.performAction(node, AccessibilityNodeInfoCompat.ACTION_FOCUS);
            return;
        }

        // If a user quickly touch explores in web content (event stream <
        // TAP_TIMEOUT), we'll send an unintentional ACTION_CLICK. Switch
        // off clicking on web content for now.
        if (WebInterfaceUtils.supportsWebActions(node)) {
            return;
        }

        PerformActionUtils.performAction(node, AccessibilityNodeInfoCompat.ACTION_CLICK);
    }

    /**
     * Listens for scroll events.
     *
     * @param action The type of scroll event received.
     */
    @Override
    public void onScroll(AccessibilityNodeInfoCompat scrolledNode, int action) {
        if (scrolledNode == null) {
            clearScrollAction();
        }

        switch (action) {
            case AccessibilityNodeInfo.ACTION_SCROLL_FORWARD:
            case AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD:
                mLastScrollAction = action;
                if (mActionScrolledNode != null) {
                    mActionScrolledNode.recycle();
                }

                if (scrolledNode != null) {
                    mActionScrolledNode = AccessibilityNodeInfoCompat.obtain(scrolledNode);
                }
                break;
        }
    }

    private final FollowFocusHandler mHandler;

    private static class FollowFocusHandler
            extends WeakReferenceHandler<ProcessorFocusAndSingleTap> {
        private static final int FOCUS_AFTER_CONTENT_CHANGED = 2;
        private static final int REFOCUS_AFTER_TIMEOUT = 3;
        private static final int EMPTY_TOUCH_AREA = 5;

        /** Delay after a scroll event before checking focus. */
        private static final long FOCUS_AFTER_CONTENT_CHANGED_DELAY = 500;

        /** Delay for indicating the user has explored into an unfocusable area. */
        private static final long EMPTY_TOUCH_AREA_DELAY = 100;

        private AccessibilityNodeInfoCompat mCachedFocusedNode;
        private AccessibilityNodeInfoCompat mCachedTouchedNode;
        private final FeedbackController mFeedbackController;
        boolean mHasContentChangeMessage = false;

        public FollowFocusHandler(ProcessorFocusAndSingleTap parent,
                                  FeedbackController feedbackController) {
            super(parent);
            mFeedbackController = feedbackController;
        }

        @Override
        public void handleMessage(Message msg, ProcessorFocusAndSingleTap parent) {
            switch (msg.what) {
                case FOCUS_AFTER_CONTENT_CHANGED:
                    mHasContentChangeMessage = false;
                    parent.followContentChangedEvent();
                    break;
                case REFOCUS_AFTER_TIMEOUT:
                    parent.cancelSingleTap();
                    cancelRefocusTimeout(true);
                    break;
                case EMPTY_TOUCH_AREA:
                    if (!AccessibilityNodeInfoUtils.isSelfOrAncestorFocused(mCachedTouchedNode)) {
                        mFeedbackController.playHaptic(R.array.view_hovered_pattern);
                        mFeedbackController.playAuditory(R.raw.view_entered, 1.3f, 1);
                    }

                    break;
            }
        }

        /**
         * Ensure that focus is placed after content change actions, but use a delay to
         * avoid consuming too many resources.
         */
        public void followContentChangedDelayed() {
            if (!mHasContentChangeMessage) {
                mHasContentChangeMessage = true;
                sendMessageDelayed(obtainMessage(FOCUS_AFTER_CONTENT_CHANGED),
                        FOCUS_AFTER_CONTENT_CHANGED_DELAY);
            }
        }

        /**
         * Attempts to refocus the specified node after a timeout period, unless
         * {@link #cancelRefocusTimeout} is called first.
         *
         * @param source The node to refocus after a timeout.
         */
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void refocusAfterTimeout(AccessibilityNodeInfoCompat source) {
            removeMessages(REFOCUS_AFTER_TIMEOUT);

            if (mCachedFocusedNode != null) {
                mCachedFocusedNode.recycle();
                mCachedFocusedNode = null;
            }

            mCachedFocusedNode = AccessibilityNodeInfoCompat.obtain(source);

            final Message msg = obtainMessage(REFOCUS_AFTER_TIMEOUT);
            sendMessageDelayed(msg, TAP_TIMEOUT);
        }

        /**
         * Provides feedback indicating an empty or unfocusable area after a
         * delay.
         */
        public void sendEmptyTouchAreaFeedbackDelayed(AccessibilityNodeInfoCompat touchedNode) {
            cancelEmptyTouchAreaFeedback();
            mCachedTouchedNode = AccessibilityNodeInfoCompat.obtain(touchedNode);

            final Message msg = obtainMessage(EMPTY_TOUCH_AREA);
            sendMessageDelayed(msg, EMPTY_TOUCH_AREA_DELAY);
        }

        /**
         * Cancels a refocus timeout initiated by {@link #refocusAfterTimeout}
         * and optionally refocuses the target node immediately.
         *
         * @param shouldRefocus Whether to refocus the target node immediately.
         */
        @TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR1)
        public void cancelRefocusTimeout(boolean shouldRefocus) {
            removeMessages(REFOCUS_AFTER_TIMEOUT);

            final ProcessorFocusAndSingleTap parent = getParent();
            if (parent == null) {
                return;
            }

            if (shouldRefocus && (mCachedFocusedNode != null)) {
                parent.attemptRefocusNode(mCachedFocusedNode);
            }

            if (mCachedFocusedNode != null) {
                mCachedFocusedNode.recycle();
                mCachedFocusedNode = null;
            }
        }

        /**
         * Interrupt any pending follow-focus messages.
         */
        public void interruptFollowDelayed() {
            mHasContentChangeMessage = false;
            removeMessages(FOCUS_AFTER_CONTENT_CHANGED);
        }

        /**
         * Cancel any pending messages for delivering feedback indicating an
         * empty or unfocusable area.
         */
        public void cancelEmptyTouchAreaFeedback() {
            removeMessages(EMPTY_TOUCH_AREA);

            if (mCachedTouchedNode != null) {
                mCachedTouchedNode.recycle();
                mCachedTouchedNode = null;
            }
        }
    }

    private static class FirstWindowFocusManager {
        private static final int MISS_FOCUS_DELAY = 300;
        private long mLastWindowStateChangeEventTime;
        private long mLastWindowId;
        private boolean mIsFirstFocusInWindow;

        public void registerWindowChange(AccessibilityEvent event) {
            mLastWindowStateChangeEventTime = event.getEventTime();
            if (mLastWindowId != event.getWindowId()) {
                mLastWindowId = event.getWindowId();
                mIsFirstFocusInWindow = true;
            }
        }

        public boolean shouldProcessFocusEvent(AccessibilityEvent event) {
            boolean isFirstFocus = mIsFirstFocusInWindow;
            mIsFirstFocusInWindow = false;

            if (mLastWindowId != event.getWindowId()) {
                mLastWindowId = event.getWindowId();
                return false;
            }

            return !isFirstFocus ||
                    event.getEventTime() - mLastWindowStateChangeEventTime > MISS_FOCUS_DELAY;
        }
    }
}
