/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.screenspeak.menurules;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.support.v4.view.ViewPager;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.Menu;
import android.view.MenuItem;

import com.android.screenspeak.R;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.contextmenu.ContextMenuItem;
import com.android.screenspeak.contextmenu.ContextMenuItemBuilder;
import com.android.utils.AccessibilityNodeInfoUtils;
import com.android.utils.NodeFilter;
import com.android.utils.PerformActionUtils;

import java.util.LinkedList;
import java.util.List;

/**
 * Rule for generating menu items related to ViewPager layouts.
 */
@TargetApi(Build.VERSION_CODES.ICE_CREAM_SANDWICH)
public class RuleViewPager implements NodeMenuRule {
    private static final NodeFilter FILTER_PAGED = new NodeFilter() {
        @Override
        public boolean accept(AccessibilityNodeInfoCompat node) {
            return AccessibilityNodeInfoUtils.nodeMatchesClassByType(node, ViewPager.class);
        }
    };

    @Override
    public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
        AccessibilityNodeInfoCompat rootNode = null;
        AccessibilityNodeInfoCompat pagerNode = null;

        try {
            rootNode = AccessibilityNodeInfoUtils.getRoot(node);
            if (rootNode == null) {
                return false;
            }

            pagerNode = AccessibilityNodeInfoUtils.searchFromBfs(rootNode, FILTER_PAGED);
            return pagerNode != null;

        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(rootNode, pagerNode);
        }
    }

    @Override
    public List<ContextMenuItem> getMenuItemsForNode(
            ScreenSpeakService service, ContextMenuItemBuilder menuItemBuilder,
            AccessibilityNodeInfoCompat node) {
        final LinkedList<ContextMenuItem> items = new LinkedList<>();

        AccessibilityNodeInfoCompat rootNode = null;
        AccessibilityNodeInfoCompat pagerNode = null;

        try {
            rootNode = AccessibilityNodeInfoUtils.getRoot(node);
            if (rootNode == null) {
                return items;
            }

            pagerNode = AccessibilityNodeInfoUtils.searchFromBfs(rootNode, FILTER_PAGED);
            if (pagerNode == null) {
                return items;
            }

            if (AccessibilityNodeInfoUtils.supportsAnyAction(
                    pagerNode, AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD)) {
                final ContextMenuItem prevPage = menuItemBuilder.createMenuItem(service,
                        Menu.NONE, R.id.viewpager_breakout_prev_page, Menu.NONE,
                        service.getString(R.string.title_viewpager_breakout_prev_page));
                items.add(prevPage);
            }

            if (AccessibilityNodeInfoUtils.supportsAnyAction(
                    pagerNode, AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD)) {
                final ContextMenuItem nextPage = menuItemBuilder.createMenuItem(service,
                        Menu.NONE, R.id.viewpager_breakout_next_page, Menu.NONE,
                        service.getString(R.string.title_viewpager_breakout_next_page));
                items.add(nextPage);
            }

            if (items.isEmpty()) {
                return items;
            }

            final AccessibilityNodeInfoCompat pagerNodeClone =
                    AccessibilityNodeInfoCompat.obtain(pagerNode);
            final ViewPagerItemClickListener itemClickListener =
                    new ViewPagerItemClickListener(pagerNodeClone);
            for (ContextMenuItem item : items) {
                item.setOnMenuItemClickListener(itemClickListener);
            }

            return items;
        } finally {
            AccessibilityNodeInfoUtils.recycleNodes(rootNode, pagerNode);
        }
    }

    @Override
    public CharSequence getUserFriendlyMenuName(Context context) {
        return context.getString(R.string.title_viewpager_controls);
    }

    @Override
    public boolean canCollapseMenu() {
        return true;
    }

    private static class ViewPagerItemClickListener implements MenuItem.OnMenuItemClickListener {
        private final AccessibilityNodeInfoCompat mNode;

        public ViewPagerItemClickListener(AccessibilityNodeInfoCompat node) {
            mNode = node;
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            if (item == null) {
                mNode.recycle();
                return true;
            }

            final int itemId = item.getItemId();
            if (itemId == R.id.viewpager_breakout_prev_page) {
                PerformActionUtils.performAction(mNode,
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_BACKWARD);
            } else if (itemId == R.id.viewpager_breakout_next_page) {
                PerformActionUtils.performAction(mNode,
                        AccessibilityNodeInfoCompat.ACTION_SCROLL_FORWARD);
            } else {
                return false;
            }

            mNode.recycle();
            return true;
        }
    }
}
