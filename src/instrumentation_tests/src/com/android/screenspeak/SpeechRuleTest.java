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

package com.android.screenspeak;

import android.test.suitebuilder.annotation.SmallTest;
import com.android.screenspeak.formatter.EventSpeechRule;
import com.android.screenspeak.formatter.EventSpeechRule.AccessibilityEventFormatter;
import com.android.screenspeak.formatter.EventSpeechRuleProcessor;

import android.text.TextUtils;
import android.view.accessibility.AccessibilityEvent;

import com.google.android.marvin.screenspeak.ScreenSpeakService;
import com.googlecode.eyesfree.testing.ScreenSpeakInstrumentationTestCase;
import com.android.utils.StringBuilderUtils;

import org.w3c.dom.Document;
import org.xml.sax.InputSource;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

/**
 * This class is a test case for the {@link EventSpeechRule} class.
 */
public class SpeechRuleTest extends ScreenSpeakInstrumentationTestCase {
    private ScreenSpeakService mScreenSpeak;

    private static final String TEMPLATE_SPEECH_STRATEGY =
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                    "  <ss:speechstrategy " +
                    "      xmlns:ss=\"http://www.google.android.marvin.screenspeak.com/speechstrategy\" " +
                    "      xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" " +
                    "      xsi:schemaLocation=\"http://www.google.android.marvin.screenspeak.com/speechstrategy speechstrategy.xsd \">" +
                    "%1s" +
                    "</ss:speechstrategy>";

    @Override
    protected void setUp() throws Exception {
        super.setUp();

        mScreenSpeak = getService();
    }

    /**
     * Test if the {@link EventSpeechRule} returns an empty list if it is passed
     * a <code>null</code> document.
     */
    @SmallTest
    public void testCreateSpeechRules_fromNullDocument() throws Exception {
        final List<EventSpeechRule> speechRules = EventSpeechRule.createSpeechRules(
                mScreenSpeak, null);

        assertNotNull("Must always return an instance.", speechRules);
        assertTrue("The list must be empty.", speechRules.isEmpty());
    }

    /**
     * Test if an event is filtered correctly when all its properties are used.
     */
    @SmallTest
    public void testCreateSpeechRules_filteringByTextProperty() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "   <ss:filter>" +
                        "       <ss:text>first blank second</ss:text>" +
                        "   </ss:filter>" +
                        "   <ss:formatter>" +
                        "       <ss:template>template</ss:template>" +
                        "   </ss:formatter>" +
                        "</ss:rule>";

        // Create an event that matches our only rule.
        final AccessibilityEvent event = AccessibilityEvent.obtain();
        event.getText().add("first blank second");

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        final Utterance utterance = new Utterance();
        final boolean processed = processor.processEvent(event, utterance);

        assertTrue("The event must match the filter", processed);
        assertFalse("An utterance must be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
    }

    /**
     * Test if an event is filtered correctly when all its properties are used.
     */
    @SmallTest
    public void testCreateSpeechRules_filteringByAllEventProperties() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "   <ss:filter>" +
                        "       <ss:addedCount>1</ss:addedCount>" +
                        "       <ss:beforeText>beforeText</ss:beforeText>" +
                        "       <ss:checked>true</ss:checked>" +
                        "       <ss:className>foo.bar.baz.Test</ss:className>" +
                        "       <ss:contentDescription>contentDescription</ss:contentDescription>" +
                        "       <ss:currentItemIndex>2</ss:currentItemIndex>" +
                        "       <ss:enabled>true</ss:enabled>" +
                        "       <ss:eventType>TYPE_NOTIFICATION_STATE_CHANGED</ss:eventType>" +
                        "       <ss:fromIndex>1</ss:fromIndex>" +
                        "       <ss:fullScreen>true</ss:fullScreen>" +
                        "       <ss:itemCount>10</ss:itemCount>" +
                        "       <ss:packageName>foo.bar.baz</ss:packageName>" +
                        "       <ss:password>true</ss:password>" +
                        "       <ss:removedCount>2</ss:removedCount>" +
                        "       <ss:contentDescriptionOrText>contentDescription</ss:contentDescriptionOrText>" +
                        "   </ss:filter>" +
                        "   <ss:formatter>" +
                        "       <ss:template>text template</ss:template>" +
                        "   </ss:formatter>" +
                        "</ss:rule>";

        // Create an event that matches our only rule.
        final AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setAddedCount(1);
        event.setBeforeText("beforeText");
        event.setChecked(true);
        event.setClassName("foo.bar.baz.Test");
        event.setContentDescription("contentDescription");
        event.setCurrentItemIndex(2);
        event.setEnabled(true);
        event.setEventType(AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED);
        event.setFromIndex(1);
        event.setFullScreen(true);
        event.setItemCount(10);
        event.setPackageName("foo.bar.baz");
        event.setPassword(true);
        event.setRemovedCount(2);

        // The event text isn't checked by the rule since it's overridden by the
        // content description.
        event.getText().add("first blank second");

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        final Utterance utterance = new Utterance();
        final boolean processed = processor.processEvent(event, utterance);

        assertTrue("The event must match the filter", processed);
        assertFalse("An utterance must be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
        assertTrue(
                "The utterance must contain the template text of the formatter", StringBuilderUtils
                        .getAggregateText(utterance.getSpoken()).toString()
                        .contains("text template"));
    }

    /**
     * Test if the utterance for speaking an event is properly constructed if
     * the speech rule defines a template which is to be populated by event
     * property values.
     */
    @SmallTest
    public void testCreateSpeechRules_useRuleWithPropertyValuesFormatter() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "  <ss:formatter>" +
                        "    <ss:template>@string/template_long_clicked</ss:template>" +
                        "    <ss:property>packageName</ss:property>" +
                        "  </ss:formatter>" +
                        "</ss:rule>";

        // Create an event with just a package name.
        final AccessibilityEvent event = AccessibilityEvent.obtain();
        event.setPackageName("foo.bar.baz");

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        final Utterance utterance = new Utterance();
        final boolean processed = processor.processEvent(event, utterance);

        assertTrue("The event must match the filter", processed);
        assertFalse("An utterance must be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
    }

    /**
     * Test if the utterance for speaking an event is properly constructed if
     * the speech rule defines a template which is a plural resource and
     * quantity.
     */
    @SmallTest
    public void testCreateSpeechRules_useRuleWithQuantityFormatter() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "  <ss:formatter>" +
                        "    <ss:template>@plurals/template_containers</ss:template>" +
                        "    <ss:quantity>itemCount</ss:quantity>" +
                        "    <ss:property>contentDescriptionOrText</ss:property>" +
                        "    <ss:property>itemCount</ss:property>" +
                        "  </ss:formatter>" +
                        "</ss:rule>";

        // Create an event to match the "one" quantity of @plurals/template_containers
        final AccessibilityEvent oneEvent = AccessibilityEvent.obtain();
        oneEvent.setPackageName("foo.bar.baz");
        oneEvent.setContentDescription("List");
        oneEvent.setItemCount(1);

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        Utterance utterance = new Utterance();
        boolean processed = processor.processEvent(oneEvent, utterance);

        assertTrue("The singleEvent must match the filter", processed);
        assertFalse("An utterance must be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
        assertFalse(
                "The utterance produced by singleEvent must use the \"one\" plural option from the template resource",
                StringBuilderUtils.getAggregateText(utterance.getSpoken()).toString().contains(
                        "items"));

        // Create an event to match the "other" quantity of @plurals/template_containers
        final AccessibilityEvent otherEvent = AccessibilityEvent.obtain();
        otherEvent.setPackageName("foo.bar.baz");
        otherEvent.setContentDescription("List");
        otherEvent.setItemCount(6);

        utterance = new Utterance();
        processed = processor.processEvent(otherEvent, utterance);

        assertTrue("The singleEvent must match the filter", processed);
        assertFalse("An utterance must be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
        assertTrue(
                "The utterance produced by singleEvent must use the \"one\" plural option from the template resource",
                StringBuilderUtils.getAggregateText(utterance.getSpoken()).toString().contains(
                        "items"));

    }

    /**
     * Test if an event is dropped on the floor if no formatter is specified.
     */
    @SmallTest
    public void testCreateSpeechRules_dropEventIfNoFormatter() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "  <ss:filter>" +
                        "    <ss:eventType>TYPE_VIEW_CLICKED</ss:eventType>" +
                        "  </ss:filter>" +
                        "</ss:rule>";

        // Create an empty CLICKED event.
        final AccessibilityEvent event = AccessibilityEvent.obtain(
                AccessibilityEvent.TYPE_VIEW_CLICKED);

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        final Utterance utterance = new Utterance();
        final boolean processed = processor.processEvent(event, utterance);

        assertTrue("The event must match the filter", processed);
        assertTrue("No utterance should be produced",
                TextUtils.isEmpty(StringBuilderUtils.getAggregateText(utterance.getSpoken())));
    }

    /**
     * Test if the utterance for speaking an event is properly constructed if
     * the a speech rule defines a template which is to be populated by event
     * property values.
     */
    @SmallTest
    public void testCreateSpeechRules_multipleRuleParsing() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "  <ss:formatter>" +
                        "    <ss:property>packageName</ss:property>" +
                        "  </ss:formatter>" +
                        "</ss:rule>" +
                        "<ss:rule>" +
                        "  <ss:formatter>" +
                        "    <ss:property>packageName</ss:property>" +
                        "  </ss:formatter>" +
                        "</ss:rule>";

        loadSpeechRulesAssertingCorrectness(strategy, 2);
    }

    /**
     * Test if the utterance for speaking an event is properly constructed if a
     * {@link AccessibilityEventFormatter} is being used.
     */
    @SmallTest
    public void testCreateSpeechRules_customFormatter() throws Exception {
        final String speechStrategyContent =
                "<ss:rule>" +
                        "  <ss:formatter>" +
                        "    <ss:custom>com.android.screenspeak.formatter.TextFormatters$ChangedTextFormatter</ss:custom>" +
                        "  </ss:formatter>" +
                        "</ss:rule>";

        loadSpeechRulesAssertingCorrectness(speechStrategyContent, 1);
    }

    /**
     * Test that the meta-data of a speech rule is properly parsed and passed to
     * a formatted utterance.
     */
    @SmallTest
    public void testCreateSpeechRules_metadata() throws Exception {
        final String strategy =
                "<ss:rule>" +
                        "  <ss:metadata>" +
                        "    <ss:queuing>UNINTERRUPTIBLE</ss:queuing>" +
                        "  </ss:metadata>" +
                        "</ss:rule>";

        // Create an empty event.
        final AccessibilityEvent event = AccessibilityEvent.obtain();

        final EventSpeechRuleProcessor processor = createProcessorWithStrategy(strategy, 1);
        final Utterance utterance = new Utterance();
        final boolean processed = processor.processEvent(event, utterance);

        assertTrue("The event must match the filter", processed);
        assertEquals("The meta-data must have its queuing poperty set", 2,
                utterance.getMetadata().get(Utterance.KEY_METADATA_QUEUING));
    }

    private EventSpeechRuleProcessor createProcessorWithStrategy(String strategy, int ruleCount)
            throws Exception {
        final EventSpeechRuleProcessor processor = new EventSpeechRuleProcessor(mScreenSpeak);
        final List<EventSpeechRule> speechRules = loadSpeechRulesAssertingCorrectness(
                strategy, ruleCount);

        processor.addSpeechStrategy(speechRules);

        return processor;
    }

    /**
     * Loads the {@link EventSpeechRule}s from a document obtained by parsing a
     * XML string generated by populating the {@link #TEMPLATE_SPEECH_STRATEGY}
     * with <code>speechStrategyContent</code>. The method asserts the document is parsed and the
     * parsed speech rules list has <code>expectedSize</code>.
     *
     * @return The parsed speech rules.
     */
    private List<EventSpeechRule> loadSpeechRulesAssertingCorrectness(
            String strategy, int expectedSize) throws Exception {
        final String xmlStrategy = String.format(TEMPLATE_SPEECH_STRATEGY, strategy);
        final DocumentBuilder builder = DocumentBuilderFactory.newInstance().newDocumentBuilder();
        final Document document = builder.parse(new InputSource(new StringReader(xmlStrategy)));

        assertNotNull("Test case setup requires properly parsed document.", document);

        final ArrayList<EventSpeechRule> speechRules = EventSpeechRule.createSpeechRules(
                mScreenSpeak, document);

        assertEquals("There must be " + expectedSize + " rules", expectedSize, speechRules.size());

        return speechRules;
    }
}
