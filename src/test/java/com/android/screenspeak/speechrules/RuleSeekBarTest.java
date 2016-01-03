/*
 * Copyright (C) 2015 Google Inc.
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

package com.android.screenspeak.speechrules;

import android.annotation.TargetApi;
import android.content.Context;
import android.os.Build;
import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.view.accessibility.AccessibilityEvent;
import com.android.switchaccess.test.ShadowAccessibilityNodeInfo;
import com.android.switchaccess.test.ShadowAccessibilityNodeInfoCompat;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Tests for RuleSeekBar
 */
@Config(emulateSdk = 18,
        shadows = {ShadowAccessibilityNodeInfo.class,
                ShadowAccessibilityNodeInfoCompat.class})
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RunWith(RobolectricTestRunner.class)
public class RuleSeekBarTest {

    private Context mContext = RuntimeEnvironment.application.getApplicationContext();
    private AccessibilityNodeInfoCompat mNodeInfo;

    @Before
    public void setUp() {
        ShadowAccessibilityNodeInfoCompat.resetObtainedInstances();
        mNodeInfo = AccessibilityNodeInfoCompat.obtain();
    }

    @After
    public void tearDown() {
        try {
            mNodeInfo.recycle();
            assertFalse(ShadowAccessibilityNodeInfoCompat.areThereUnrecycledNodes(true));
        } finally {
            ShadowAccessibilityNodeInfoCompat.resetObtainedInstances();
        }
    }

    @Test
    public void testNotFocusedNode_shouldReturnEmptyText() {
        mNodeInfo.setAccessibilityFocused(false);
        RuleSeekBar rule = new RuleSeekBar();
        CharSequence text = rule.format(mContext, mNodeInfo, null);
        assertTrue("".equalsIgnoreCase(text.toString()));
    }

    @Test
    public void testFocusedNode_shouldReturnSeekBarPosition() {
        mNodeInfo.setAccessibilityFocused(true);
        mNodeInfo.setText("Volume");
        RuleSeekBar rule = new RuleSeekBar();
        AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setItemCount(100);
        event.setCurrentItemIndex(50);
        CharSequence text = rule.format(mContext, mNodeInfo, event);
        assertTrue("Volume seek control. 50 percent.".equalsIgnoreCase(text.toString()));
    }
}

