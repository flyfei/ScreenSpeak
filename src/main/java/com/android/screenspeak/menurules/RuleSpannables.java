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

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextUtils;
import android.text.style.URLSpan;
import android.view.Menu;
import android.view.MenuItem;

import com.android.screenspeak.R;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.contextmenu.ContextMenuItem;
import com.android.screenspeak.contextmenu.ContextMenuItemBuilder;

import java.util.LinkedList;
import java.util.List;

/**
 * Menu population rule for views with Spannable link contents.
 */
public class RuleSpannables implements NodeMenuRule {

    @Override
    public boolean accept(Context context, AccessibilityNodeInfoCompat node) {
        final CharSequence text = node.getText();
        if (!TextUtils.isEmpty(text) && (text instanceof SpannableString)) {
            final SpannableString spannable = (SpannableString) node.getText();
            final URLSpan[] urlSpans = spannable.getSpans(0, spannable.length(), URLSpan.class);
            if (urlSpans.length > 0) {
                return true;
            }
        }

        return false;
    }

    @Override
    public List<ContextMenuItem> getMenuItemsForNode(
            ScreenSpeakService service, ContextMenuItemBuilder menuItemBuilder,
            AccessibilityNodeInfoCompat node) {
        final SpannableString spannable = (SpannableString) node.getText();
        final URLSpan[] urlSpans = spannable.getSpans(0, spannable.length(), URLSpan.class);
        final LinkedList<ContextMenuItem> result = new LinkedList<>();

        if ((urlSpans == null) || (urlSpans.length == 0)) {
            return result;
        }

        for (int i = 0; i < urlSpans.length; i++) {
            final URLSpan urlSpan = urlSpans[i];
            final String url = urlSpan.getURL();
            final int start = spannable.getSpanStart(urlSpan);
            final int end = spannable.getSpanEnd(urlSpan);
            final CharSequence label = spannable.subSequence(start, end);
            if (TextUtils.isEmpty(url) || TextUtils.isEmpty(label)) {
                continue;
            }

            final Uri uri = Uri.parse(url);
            if (uri.isRelative()) {
                // Generally, only absolute URIs are resolvable to an activity
                continue;
            }

            final ContextMenuItem item = menuItemBuilder.createMenuItem(service, Menu.NONE, i,
                    Menu.NONE, label);
            item.setOnMenuItemClickListener(new SpannableMenuClickListener(service, uri));
            result.add(item);
        }

        return result;
    }

    @Override
    public CharSequence getUserFriendlyMenuName(Context context) {
        return context.getString(R.string.links);
    }

    @Override
    public boolean canCollapseMenu() {
        return true;
    }

    /**
     * Click listener for menu items representing {@link Spannable}s.
     */
    private static class SpannableMenuClickListener implements MenuItem.OnMenuItemClickListener {

        final Context mContext;
        final Uri mUri;

        public SpannableMenuClickListener(Context context, Uri uri) {
            mContext = context;
            mUri = uri;
        }

        @Override
        public boolean onMenuItemClick(MenuItem item) {
            if (mContext == null) {
                return false;
            }

            final Intent intent = new Intent(Intent.ACTION_VIEW, mUri);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            try {
                mContext.startActivity(intent);
            } catch (ActivityNotFoundException e) {
                return false;
            }

            return true;
        }
    }
}
