/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.screenspeak.formatter;

import android.support.v4.view.accessibility.AccessibilityNodeInfoCompat;
import android.text.TextUtils;
import android.view.View;
import android.view.accessibility.AccessibilityEvent;
import com.android.screenspeak.FeedbackItem;
import com.android.screenspeak.SpeechController;
import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.android.screenspeak.Utterance;
import com.android.screenspeak.speechrules.NodeSpeechRuleProcessor;

/**
 * Formatter that will speak a live view update event.
 */

public final class LiveViewFormatter implements EventSpeechRule.AccessibilityEventFilter,
        EventSpeechRule.AccessibilityEventFormatter {

    @Override
    public boolean accept(AccessibilityEvent event, ScreenSpeakService context) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) return false;

        AccessibilityNodeInfoCompat node = new AccessibilityNodeInfoCompat(event.getSource());
        if (node.getInfo() == null) {
            return false;
        }

        int liveRegion = node.getLiveRegion();
        switch (liveRegion) {
            case View.ACCESSIBILITY_LIVE_REGION_POLITE:
                return true;
            case View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE:
                return true;
            case View.ACCESSIBILITY_LIVE_REGION_NONE:
                return false;
            default:
                return false;
        }
    }

    @Override
    public boolean format(AccessibilityEvent event, ScreenSpeakService context, Utterance utterance) {
        AccessibilityNodeInfoCompat node = new AccessibilityNodeInfoCompat(event.getSource());
        if (node.getInfo() == null) {
            return false;
        }

        int liveRegion = node.getLiveRegion();
        switch (liveRegion) {
            case View.ACCESSIBILITY_LIVE_REGION_POLITE:
                break;
            case View.ACCESSIBILITY_LIVE_REGION_ASSERTIVE:
                utterance.getMetadata().putInt(Utterance.KEY_METADATA_QUEUING,
                        SpeechController.QUEUE_MODE_INTERRUPT);
                break;
            case View.ACCESSIBILITY_LIVE_REGION_NONE:
                return false;
            default:
                return false;
        }

        CharSequence text = NodeSpeechRuleProcessor.getInstance()
                .getDescriptionForNode(node, event);

        if (TextUtils.isEmpty(text)) {
            return false;
        }

        utterance.addSpoken(text);
        utterance.addSpokenFlag(FeedbackItem.FLAG_SKIP_DUPLICATE);
        return true;
    }
}
