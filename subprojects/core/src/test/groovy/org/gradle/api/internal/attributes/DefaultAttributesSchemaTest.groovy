/*
 * Copyright 2016 the original author or authors.
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

package org.gradle.api.internal.attributes

import org.gradle.api.attributes.Attribute
import org.gradle.api.attributes.AttributeCompatibilityRule
import org.gradle.api.attributes.AttributeDisambiguationRule
import org.gradle.api.attributes.CompatibilityCheckDetails
import org.gradle.api.attributes.MultipleCandidatesDetails
import org.gradle.internal.component.model.ComponentAttributeMatcher
import org.gradle.util.TestUtil
import spock.lang.Specification

class DefaultAttributesSchemaTest extends Specification {
    def schema = new DefaultAttributesSchema(new ComponentAttributeMatcher(), TestUtil.instantiatorFactory())
    def factory = new DefaultImmutableAttributesFactory()

    def "fails if no strategy is declared for custom type"() {
        when:
        schema.getMatchingStrategy(Attribute.of('map', Map))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'Unable to find matching strategy for map'
    }

    def "is eventually incompatible by default"() {
        given:
        def strategy = schema.attribute(Attribute.of(Map)) {}
        def details = Mock(CompatibilityCheckDetails)

        when:
        details.getConsumerValue() >> AttributeValue.of([a: 'foo', b: 'bar'])
        details.getProducerValue() >> AttributeValue.missing()
        strategy.compatibilityRules.execute(details)

        then:
        1 * details.incompatible()
        0 * details._
    }

    def "equality strategy takes precedence over default"() {
        given:
        def strategy = schema.attribute(Attribute.of(Map))
        def details = Mock(CompatibilityCheckDetails)

        when:
        details.getConsumerValue() >> AttributeValue.of([a: 'foo', b: 'bar'])
        details.getProducerValue() >> AttributeValue.of([a: 'foo', b: 'baz'])
        strategy.compatibilityRules.execute(details)

        then:
        1 * details.incompatible()
        0 * details._
    }

    def "can set a basic equality match strategy"() {
        given:
        def strategy = schema.attribute(Attribute.of(Map))
        def details = Mock(CompatibilityCheckDetails)

        when:
        details.getConsumerValue() >> AttributeValue.of([a: 'foo', b: 'bar'])
        details.getProducerValue() >> AttributeValue.of([a: 'foo', b: 'bar'])
        strategy.compatibilityRules.execute(details)

        then:
        1 * details.compatible()
        0 * details.incompatible()

        when:
        details.getConsumerValue() >> AttributeValue.of([a: 'foo', b: 'bar'])
        details.getProducerValue() >> AttributeValue.of([a: 'foo', b: 'baz'])
        strategy.compatibilityRules.execute(details)

        then:
        0 * details.compatible()
        1 * details.incompatible()
    }

    def "strategy is per attribute"() {
        given:
        schema.attribute(Attribute.of('a', Map))

        when:
        schema.getMatchingStrategy(Attribute.of('someOther', Map))

        then:
        def e = thrown(IllegalArgumentException)
        e.message == 'Unable to find matching strategy for someOther'

        when:
        schema.getMatchingStrategy(Attribute.of('map', Map))

        then:
        e = thrown(IllegalArgumentException)
        e.message == 'Unable to find matching strategy for map'
    }

    static class CustomCompatibilityRule implements AttributeCompatibilityRule<Map> {
        @Override
        void execute(CompatibilityCheckDetails<Map> details) {
            def producerValue = details.producerValue
            def consumerValue = details.consumerValue
            if (producerValue.size() == consumerValue.size()) {
                // arbitrary, just for testing purposes
                details.compatible()
            }
        }
    }

    static class CustomSelectionRule implements AttributeDisambiguationRule<Map> {
        @Override
        void execute(MultipleCandidatesDetails<Map> details) {
            details.closestMatch(details.candidateValues.first())
        }
    }

    @SuppressWarnings('VariableName')
    def "can set a custom matching strategy"() {
        def attr = Attribute.of(Map)

        given:
        schema.attribute(attr) {
            it.compatibilityRules.add(CustomCompatibilityRule)
            it.disambiguationRules.add(CustomSelectionRule)
        }
        def strategy = schema.getMatchingStrategy(attr)
        def checkDetails = Mock(CompatibilityCheckDetails)
        def candidateDetails = Mock(MultipleCandidatesDetails)

        def aFoo_bBar = [a: 'foo', b: 'bar']
        def cFoo_dBar = [c: 'foo', d: 'bar']

        when:
        checkDetails.getConsumerValue() >> aFoo_bBar
        checkDetails.getProducerValue() >> aFoo_bBar
        strategy.compatibilityRules.execute(checkDetails)

        then:
        1 * checkDetails.compatible()
        0 * checkDetails.incompatible()

        when:
        checkDetails.getConsumerValue() >> aFoo_bBar
        checkDetails.getProducerValue() >> cFoo_dBar
        strategy.compatibilityRules.execute(checkDetails)

        then:
        1 * checkDetails.compatible()
        0 * checkDetails.incompatible()

        when:
        candidateDetails.candidateValues >> [aFoo_bBar, cFoo_dBar]
        strategy.disambiguationRules.execute(candidateDetails)

        then:
        1 * candidateDetails.closestMatch(aFoo_bBar)
        0 * candidateDetails._

    }

    def "returns rules from this when merging with a producer that contains subset of attribute definitions and same compatible-when-missing flags"() {
        def producer = new DefaultAttributesSchema(new ComponentAttributeMatcher(), TestUtil.instantiatorFactory())

        def attr1 = Attribute.of("a", String)
        def attr2 = Attribute.of("b", Integer)

        schema.attribute(attr1)
        schema.attribute(attr2).compatibilityRules.assumeCompatibleWhenMissing()

        expect:
        def merged = schema.mergeFrom(producer)
        merged.hasAttribute(attr1)
        merged.hasAttribute(attr2)
        merged.getCompatibilityRules(attr1).is(schema.getMatchingStrategy(attr1).compatibilityRules)
        merged.getDisambiguationRules(attr1).is(schema.getMatchingStrategy(attr1).disambiguationRules)

        producer.attribute(attr1)
        producer.attribute(attr2).compatibilityRules.assumeCompatibleWhenMissing()

        def merged2 = schema.mergeFrom(producer)
        merged2.is(merged)

        producer.attribute(attr1).compatibilityRules.assumeCompatibleWhenMissing()

        def merged3 = schema.mergeFrom(producer)
        merged3 != merged
    }

    def "merges compatible-when-missing flags"() {
        def producer = new DefaultAttributesSchema(new ComponentAttributeMatcher(), TestUtil.instantiatorFactory())

        def attr1 = Attribute.of("a", String)
        def attr2 = Attribute.of("b", Integer)

        schema.attribute(attr1)
        schema.attribute(attr2).compatibilityRules.assumeCompatibleWhenMissing()

        producer.attribute(attr1)
        producer.attribute(attr2)

        expect:
        def merged = schema.mergeFrom(producer)
        merged.hasAttribute(attr1)
        merged.hasAttribute(attr2)
        !merged.isCompatibleWhenMissing(attr1)
        merged.isCompatibleWhenMissing(attr2)

        producer.attribute(attr1).compatibilityRules.assumeCompatibleWhenMissing()

        def merged2 = schema.mergeFrom(producer)
        merged2.isCompatibleWhenMissing(attr1)
        merged2.isCompatibleWhenMissing(attr2)
    }

    def "merging creates schema with additional attributes defined by producer"() {
        def producer = new DefaultAttributesSchema(new ComponentAttributeMatcher(), TestUtil.instantiatorFactory())

        def attr1 = Attribute.of("a", String)
        def attr2 = Attribute.of("b", Integer)
        def attr3 = Attribute.of("c", Boolean)

        schema.attribute(attr1)
        schema.attribute(attr2)
        producer.attribute(attr2)
        producer.attribute(attr3)

        expect:
        def merged = schema.mergeFrom(producer)
        merged.hasAttribute(attr1)
        merged.hasAttribute(attr2)
        merged.hasAttribute(attr3)
        merged.getCompatibilityRules(attr1).is(schema.getMatchingStrategy(attr1).compatibilityRules)
        merged.getDisambiguationRules(attr1).is(schema.getMatchingStrategy(attr1).disambiguationRules)

        merged.getCompatibilityRules(attr3).is(producer.getMatchingStrategy(attr3).compatibilityRules)
        merged.getDisambiguationRules(attr3).is(producer.getMatchingStrategy(attr3).disambiguationRules)
    }
}
