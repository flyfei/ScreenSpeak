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

package com.android.screenspeak.formatter;

import android.annotation.TargetApi;
import android.os.Build;
import android.support.v4.view.accessibility.AccessibilityEventCompat;
import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

import com.android.screenspeak.R;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.Utterance;
import com.android.utils.AccessibilityEventUtils;

/**
 * Provides formatting for {@link AccessibilityEvent#TYPE_VIEW_FOCUSED} and
 * {@link AccessibilityEventCompat#TYPE_VIEW_HOVER_ENTER} events on JellyBean.
 * <p>
 * For events that don't have source nodes, reads the event text aloud;
 * otherwise, just provides the corresponding vibration and earcon feedback.
 * </p>
 */
@TargetApi(Build.VERSION_CODES.JELLY_BEAN)
public class FallbackFormatter implements EventSpeechRule.AccessibilityEventFormatter {
    @Override
    public boolean format(AccessibilityEvent event, ScreenSpeakService context, Utterance utterance) {
        final AccessibilityNodeInfo source = event.getSource();

        // Drop events that have source nodes.
        if (source != null) {
            source.recycle();
            return false;
        }

        // Add earcons and patterns since the event doesn't have a source node
        switch (event.getEventType()) {
            case AccessibilityEvent.TYPE_VIEW_FOCUSED:
                utterance.addHaptic(R.array.view_focused_or_selected_pattern);
                utterance.addAuditory(R.raw.focus_actionable);
                break;
            case AccessibilityEvent.TYPE_VIEW_SELECTED:
                utterance.addHaptic(R.array.view_focused_or_selected_pattern);
                utterance.addAuditory(R.raw.focus_actionable);
                break;
            case AccessibilityEventCompat.TYPE_VIEW_HOVER_ENTER:
                utterance.addHaptic(R.array.view_hovered_pattern);
                utterance.addAuditory(R.raw.focus);
                break;
        }

        final CharSequence text = AccessibilityEventUtils.getEventTextOrDescription(event);
        if (!TextUtils.isEmpty(text)) {
            utterance.addSpoken(text);
        }

        return true;
    }

}
