/*
 * Copyright (C) 2013 The Android Open Source Project
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
import android.content.pm.PackageManager.NameNotFoundException;
import android.os.Build;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.text.TextUtils;
import android.util.Pair;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ImageView;
import com.android.screenspeak.FeedbackItem;
import com.android.screenspeak.R;
import com.android.screenspeak.SpeechController;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.contextmenu.ContextMenuItem;
import com.android.screenspeak.contextmenu.ContextMenuItemBuilder;
import com.android.utils.AccessibilityNodeInfoUtils;
import com.android.utils.labeling.CustomLabelManager;
import com.android.utils.labeling.Label;
import com.android.utils.labeling.LabelOperationUtils;

import java.util.LinkedList;
import java.util.List;

/**
 * Processes {@link ImageView} nodes without text.
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN_MR2)
class RuleUnlabeledImage implements NodeMenuRule {

    @Override
    public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
        final boolean isImage = AccessibilityNodeInfoUtils.nodeMatchesClassByType(node,
                ImageView.class);
        final boolean hasDescription = !TextUtils.isEmpty(
                AccessibilityNodeInfoUtils.getNodeText(node));

        return (isImage && !hasDescription);
    }

    @Override
    public List<ContextMenuItem> getMenuItemsForNode(ScreenSpeakService service,
                     ContextMenuItemBuilder menuItemBuilder, AccessibilityNodeInfoCompat node) {
        List<ContextMenuItem> items = new LinkedList<>();
        CustomLabelManager labelManager = service.getLabelManager();
        if (labelManager == null) {
            return items;
        }

        AccessibilityNodeInfoCompat nodeCopy = AccessibilityNodeInfoCompat.obtain(node);
        Label viewLabel = labelManager.getLabelForViewIdFromCache(nodeCopy.getViewIdResourceName());
        if (viewLabel == null) {
            final ContextMenuItem addLabel = menuItemBuilder.createMenuItem(service,
                    Menu.NONE, R.id.labeling_breakout_add_label, Menu.NONE,
                    service.getString(R.string.label_dialog_title_add));
            items.add(addLabel);
        } else {
            ContextMenuItem editLabel = menuItemBuilder.createMenuItem(service,
                    Menu.NONE, R.id.labeling_breakout_edit_label, Menu.NONE,
                    service.getString(R.string.label_dialog_title_edit));
            ContextMenuItem removeLabel = menuItemBuilder.createMenuItem(service,
                    Menu.NONE, R.id.labeling_breakout_remove_label, Menu.NONE,
                    service.getString(R.string.label_dialog_title_remove));
            items.add(editLabel);
            items.add(removeLabel);
        }

        for (ContextMenuItem item : items) {
            item.setOnMenuItemClickListener(
                    new UnlabeledImageMenuItemClickListener(service, nodeCopy, viewLabel));
        }

        return items;
    }

    @Override
    public CharSequence getUserFriendlyMenuName(Context context) {
        return context.getString(R.string.title_labeling_controls);
    }

    @Override
    public boolean canCollapseMenu() {
        return true;
    }

    private static class UnlabeledImageMenuItemClickListener
            implements MenuItem.OnMenuItemClickListener {
        private final ScreenSpeakService mContext;
        private final AccessibilityNodeInfoCompat mNode;
        private final Label mExistingLabel;

        public UnlabeledImageMenuItemClickListener(
                ScreenSpeakService service, AccessibilityNodeInfoCompat node, Label label) {
            mContext = service;
            mNode = node;
            mExistingLabel = label;
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            if (item == null) {
                mNode.recycle();
                return true;
            }

            mContext.saveFocusedNode();
            final int itemId = item.getItemId();

            if (itemId == R.id.labeling_breakout_add_label) {
                if (!canAddLabel()) {
                    mContext.getSpeechController().speak(
                            mContext.getString(R.string.cannot_add_label),
                            SpeechController.QUEUE_MODE_FLUSH_ALL,
                            FeedbackItem.FLAG_NO_HISTORY, null);
                    return false;
                }

                return LabelOperationUtils.startActivityAddLabelForNode(mContext, mNode);
            } else if (itemId == R.id.labeling_breakout_edit_label) {
                return LabelOperationUtils.startActivityEditLabel(mContext, mExistingLabel);
            } else if (itemId == R.id.labeling_breakout_remove_label) {
                return LabelOperationUtils.startActivityRemoveLabel(mContext, mExistingLabel);
            }

            mNode.recycle();
            return true;
        }

        private boolean canAddLabel() {
            final Pair<String, String> parsedId = CustomLabelManager.splitResourceName(
                    mNode.getViewIdResourceName());
            final boolean hasParseableId = (parsedId != null);

            // TODO(CB): There are a number of views that have a
            // different resource namespace than their parent application. It's
            // likely we'll need to refine the database structure to accommodate
            // these while also allowing the user to modify them through ScreenSpeak
            // settings. For now, we'll simply not allow labeling of such views.
            boolean isFromKnownApp = false;
            if (hasParseableId) {
                try {
                    mContext.getPackageManager().getPackageInfo(parsedId.first, 0);
                    isFromKnownApp = true;
                } catch (NameNotFoundException e) {
                    // Do nothing.
                }
            }

            return (hasParseableId && isFromKnownApp);
        }
    }
}
