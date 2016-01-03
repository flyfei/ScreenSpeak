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
import android.view.View;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.EditText;
import com.android.switchaccess.test.ShadowAccessibilityNodeInfo;
import com.android.switchaccess.test.ShadowAccessibilityNodeInfoCompat;
import com.android.switchaccess.test.ShadowAccessibilityWindowInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.internal.ShadowExtractor;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;


/**
 * Tests for RuleEditText
 */
@Config(emulateSdk = 18,
        shadows = {ShadowAccessibilityNodeInfo.class,
                ShadowAccessibilityNodeInfoCompat.class,
                ShadowAccessibilityWindowInfo.class})
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RunWith(RobolectricTestRunner.class)
public class RuleEditTextTest {

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
    public void testNotEditTextNodes_shouldNotBeAccepted() {
        mNodeInfo.setFocused(false);
        mNodeInfo.setClassName(View.class.getName());
        RuleEditText rule = new RuleEditTextWithInputWindow();
        assertFalse(rule.accept(mNodeInfo, null));
    }

    @Test
    public void testEditTextNodes_shouldBeAccepted() {
        mNodeInfo.setFocused(false);
        mNodeInfo.setClassName(EditText.class.getName());
        RuleEditText rule = new RuleEditTextWithInputWindow();
        assertTrue(rule.accept(mNodeInfo, null));
    }

    @Test
    public void testNotFocusedEditText_shouldNotBeCurrentlyEditing() {
        mNodeInfo.setFocused(false);
        mNodeInfo.setClassName(EditText.class.getName());
        mNodeInfo.setText("aaa");
        RuleEditText rule = new RuleEditTextWithInputWindow();
        CharSequence text = rule.format(mContext, mNodeInfo, null);
        assertTrue("Edit box. aaa".equalsIgnoreCase(text.toString()));
    }

    @Test
    public void testFocusedEditTextNoInputWindow_shouldNotBeCurrentlyEditing() {
        mNodeInfo.setFocused(true);
        mNodeInfo.setClassName(EditText.class.getName());
        mNodeInfo.setText("aaa");
        RuleEditText rule = new RuleEditTextNoInputWindow();
        CharSequence text = rule.format(mContext, mNodeInfo, null);
        assertTrue("Edit box. aaa".equalsIgnoreCase(text.toString()));
    }

    @Test
    public void testFocusedEditTextInputWindow_shouldBeCurrentlyEditing() {
        mNodeInfo.setFocused(true);
        mNodeInfo.setClassName(EditText.class.getName());
        mNodeInfo.setText("aaa");

        List<AccessibilityWindowInfo> windows = new ArrayList<>();
        windows.add(AccessibilityWindowInfo.obtain());
        ShadowAccessibilityWindowInfo shadowWindow =
                (ShadowAccessibilityWindowInfo) ShadowExtractor.extract(windows.get(0));
        shadowWindow.setType(AccessibilityWindowInfo.TYPE_INPUT_METHOD);
        RuleEditText rule = new RuleEditTextWithInputWindow();
        CharSequence text = rule.format(mContext, mNodeInfo, null);
        assertTrue("Edit box, currently editing. aaa".equalsIgnoreCase(text.toString()));
    }

    private static class RuleEditTextWithInputWindow extends RuleEditText {
        @Override
        boolean isInputWindowOnScreen() {
            return true;
        }
        @Override
        boolean hasWindowSupport() {
            return true;
        }
    }
    private static class RuleEditTextNoInputWindow extends RuleEditText {
        @Override
        boolean isInputWindowOnScreen() {
            return false;
        }
        @Override
        boolean hasWindowSupport() {
            return true;
        }
    }
}

