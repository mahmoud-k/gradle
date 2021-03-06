/*
 * Copyright 2009 the original author or authors.
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
package org.gradle.api.internal.project

import groovy.xml.MarkupBuilder
import org.apache.tools.ant.Target
import org.apache.tools.ant.Task
import org.gradle.api.AntBuilder.AntMessagePriority
import org.gradle.api.Project
import org.gradle.api.internal.project.ant.AntLoggingAdapter
import org.gradle.api.tasks.ant.AntTarget
import org.gradle.util.TestUtil
import spock.lang.Specification

import java.lang.reflect.Field

import static org.hamcrest.Matchers.*
import static org.junit.Assert.assertThat

class DefaultAntBuilderTest extends Specification {
    private final Project project = TestUtil.createRootProject()
    private final AntLoggingAdapter loggingAdapter = Mock(AntLoggingAdapter)
    private final def ant = new DefaultAntBuilder(project, loggingAdapter)

    def "ant properties are available as properties of builder"() {
        when:
        ant.property(name: 'prop1', value: 'value1')

        then:
        ant.prop1 == 'value1'

        when:
        ant.prop2 = 'value2'

        then:
        ant.antProject.properties.prop2 == 'value2'
    }

    def "throws MissingPropertyException for unknown property"() {
        when:
        ant.unknown

        then:
        thrown(MissingPropertyException)
    }

    def "ant properties are available as map"() {
        when:
        ant.property(name: 'prop1', value: 'value1')

        then:
        ant.properties.prop1 == 'value1'

        when:
        ant.properties.prop2 = 'value2'

        then:
        ant.antProject.properties.prop2 == 'value2'
    }

    def "ant references are available as map"() {
        when:
        def path = ant.path(id: 'ref1', location: 'path')

        then:
        ant.references.ref1 == path

        when:
        ant.references.prop2 = 'value2'

        then:
        ant.antProject.references.prop2 == 'value2'
    }

    def "import adds task for each ant target"() {
        File buildFile = new File(project.projectDir, 'build.xml')
        buildFile.withWriter { Writer writer ->
            def xml = new MarkupBuilder(writer)
            xml.project {
                target(name: 'target1', depends: 'target2, target3')
                target(name: 'target2')
                target(name: 'target3')
            }
        }

        when:
        ant.importBuild(buildFile)

        then:
        def task = project.tasks.target1
        task instanceof AntTarget
        task.target.name == 'target1'

        and:
        def task2 = project.tasks.target2
        task2 instanceof AntTarget
        task2.target.name == 'target2'

        and:
        def task3 = project.tasks.target3
        task3 instanceof AntTarget
        task3.target.name == 'target3'
    }

    def "can nest elements"() {
        when:
        ant.otherProp = 'true'
        ant.condition(property: 'prop', value: 'someValue') {
            or {
                and {
                    isSet(property: 'otherProp')
                    not { isSet(property: 'missing') }
                }
            }
        }

        then:
        ant.prop == 'someValue'
    }

    def "sets context classloader during execution"() {
        setup:
        ClassLoader original = Thread.currentThread().getContextClassLoader()
        ClassLoader cl = new URLClassLoader([] as URL[])
        Thread.currentThread().setContextClassLoader(cl)

        when:
        ant.taskdef(name: 'test', classname: TestTask.class.getName())
        ant.test()

        then:
        noExceptionThrown()

        cleanup:
        Thread.currentThread().setContextClassLoader(original)
    }

    def "discards tasks after execution"() {
        when:
        ant.echo(message: 'message')
        ant.echo(message: 'message')
        ant.echo(message: 'message')

        then:
        ant.antProject.targets.size() == 0

        when:
        Field field = groovy.util.AntBuilder.class.getDeclaredField('collectorTarget')
        field.accessible = true
        Target target = field.get(ant)
        field = target.class.getDeclaredField('children')
        field.accessible = true
        List children = field.get(target)

        then:
        children.isEmpty()
    }

    def "can rename imported tasks"() {
        File buildFile = new File(project.projectDir, 'build.xml')
        buildFile.withWriter { Writer writer ->
            def xml = new MarkupBuilder(writer)
            xml.project {
                target(name: 'target1', depends: 'target2, target3')
                target(name: 'target2')
                target(name: 'target3')
            }
        }

        when:
        ant.importBuild(buildFile) { taskName ->
            'a-' + taskName
        }

        then:
        def task = project.tasks.'a-target1'
        task instanceof AntTarget
        task.target.name == 'target1'
        task.taskDependencies.getDependencies(task).name.sort() == ["a-target2", "a-target3"]
    }

    def "setting lifecycle log level on builder sets it on logging adapter" () {
        when:
        ant.lifecycleLogLevel = AntMessagePriority.INFO

        then:
        1 * loggingAdapter.setLifecycleLogLevel(AntMessagePriority.INFO)
    }

    def "can set lifecycle log level using string representation" () {
        when:
        ant.lifecycleLogLevel = stringLevel

        then:
        1 * loggingAdapter.setLifecycleLogLevel(enumLevel)

        where:
        stringLevel | enumLevel
        "DEBUG"     | AntMessagePriority.DEBUG
        "VERBOSE"   | AntMessagePriority.VERBOSE
        "INFO"      | AntMessagePriority.INFO
        "WARN"      | AntMessagePriority.WARN
        "ERROR"     | AntMessagePriority.ERROR
    }

    def "getting lifecycle log level on builder gets it from logging adapter" () {
        when:
        def level = ant.getLifecycleLogLevel()

        then:
        1 * loggingAdapter.getLifecycleLogLevel() >> AntMessagePriority.DEBUG

        and:
        level == AntMessagePriority.DEBUG
    }
}

public class TestTask extends Task {
    @Override
    void execute() {
        assertThat(Thread.currentThread().getContextClassLoader(), sameInstance(org.apache.tools.ant.Project.class.getClassLoader()))
    }
}


