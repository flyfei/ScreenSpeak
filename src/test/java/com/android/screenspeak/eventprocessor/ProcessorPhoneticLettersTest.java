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

package com.android.screenspeak.eventprocessor;

import android.annotation.TargetApi;
import android.os.Build;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;

/**
 * Tests for ProcessorPhoneticLetters
 */
@Config(emulateSdk = 18)
@TargetApi(Build.VERSION_CODES.LOLLIPOP)
@RunWith(RobolectricTestRunner.class)
public class ProcessorPhoneticLettersTest {

    @Test
    public void testParseLanguageTag() throws Exception {
        assertEquals(Locale.ENGLISH, ProcessorPhoneticLetters.parseLanguageTag("en"));
        assertEquals(Locale.US, ProcessorPhoneticLetters.parseLanguageTag("en_US"));
        assertEquals(Locale.US, ProcessorPhoneticLetters.parseLanguageTag("en_US_POSIX"));

        assertEquals(Locale.JAPANESE, ProcessorPhoneticLetters.parseLanguageTag("ja"));
        assertEquals(Locale.JAPAN, ProcessorPhoneticLetters.parseLanguageTag("ja_JP"));
        assertEquals(new Locale("de", "CH"), ProcessorPhoneticLetters.parseLanguageTag("de_CH_1996"));
    }
}
