/*
 * Copyright (C) 2015 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.screenspeak.contextmenu;

import android.content.Context;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.MenuInflater;
import com.android.screenspeak.R;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.menurules.NodeMenuRuleProcessor;

public class ListMenuPreparer {

    private Context mContext;

    public ListMenuPreparer(Context context) {
        mContext = context;
    }

    public void prepareMenu(ListMenu menu, int menuId) {
        if (menuId == R.menu.global_context_menu) {
            new MenuInflater(mContext).inflate(R.menu.global_context_menu, menu);
            menu.removeItem(R.id.quick_navigation);
            menu.setTitle(mContext.getString(R.string.global_context_menu_title));
        } else if (menuId == R.menu.local_context_menu) {
            ScreenSpeakService service = ScreenSpeakService.getInstance();
            if (service == null) {
                return;
            }

            final AccessibilityNodeInfoCompat currentNode = service.getCursorController()
                    .getCursor();
            if (currentNode == null) {
                return;
            }

            NodeMenuRuleProcessor menuRuleProcessor = new NodeMenuRuleProcessor(service);
            menuRuleProcessor.prepareMenuForNode(menu, currentNode);
            currentNode.recycle();
            menu.setTitle(mContext.getString(R.string.local_context_menu_title));
        }
    }
}
