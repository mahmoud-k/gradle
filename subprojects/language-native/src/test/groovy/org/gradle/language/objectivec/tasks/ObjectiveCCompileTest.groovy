/*
 * Copyright 2015 the original author or authors.
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

package org.gradle.language.objectivec.tasks
import org.gradle.api.internal.file.collections.SimpleFileCollection
import org.gradle.api.tasks.WorkResult
import org.gradle.language.base.internal.compile.Compiler
import org.gradle.nativeplatform.platform.internal.ArchitectureInternal
import org.gradle.nativeplatform.platform.internal.NativePlatformInternal
import org.gradle.nativeplatform.platform.internal.OperatingSystemInternal
import org.gradle.nativeplatform.toolchain.internal.NativeToolChainInternal
import org.gradle.nativeplatform.toolchain.internal.PlatformToolProvider
import org.gradle.nativeplatform.toolchain.internal.PreCompiledHeader
import org.gradle.nativeplatform.toolchain.internal.compilespec.ObjectiveCCompileSpec
import org.gradle.test.fixtures.file.TestNameTestDirectoryProvider
import org.gradle.util.TestUtil
import org.junit.Rule
import spock.lang.Specification

class ObjectiveCCompileTest extends Specification {
    @Rule
    TestNameTestDirectoryProvider testDir = new TestNameTestDirectoryProvider()

    ObjectiveCCompile objCCompile = TestUtil.create(testDir).task(ObjectiveCCompile)
    def toolChain = Mock(NativeToolChainInternal)
    def platform = Mock(NativePlatformInternal)
    def platformToolChain = Mock(PlatformToolProvider)
    Compiler<ObjectiveCCompileSpec> objCCompiler = Mock(Compiler)
    def pch = Mock(PreCompiledHeader)

    def "executes using the C Compiler"() {
        def sourceFile = testDir.createFile("sourceFile")
        def result = Mock(WorkResult)
        when:
        objCCompile.toolChain = toolChain
        objCCompile.targetPlatform = platform
        objCCompile.compilerArgs = ["arg"]
        objCCompile.macros = [def: "value"]
        objCCompile.objectFileDir = testDir.file("outputFile")
        objCCompile.source sourceFile
        objCCompile.setPreCompiledHeader pch
        objCCompile.execute()

        then:
        _ * toolChain.outputType >> "objc"
        platform.getArchitecture() >> Mock(ArchitectureInternal) { getName() >> "arch" }
        platform.getOperatingSystem() >> Mock(OperatingSystemInternal) { getName() >> "os" }
        1 * toolChain.select(platform) >> platformToolChain
        1 * platformToolChain.newCompiler({ ObjectiveCCompileSpec.class.isAssignableFrom(it) }) >> objCCompiler
        1 * pch.includeString >> "header"
        2 * pch.prefixHeaderFile >> testDir.file("prefixHeader").createFile()
        1 * pch.objectFile >> testDir.file("pchObjectFile").createFile()
        2 * pch.pchObjects >> new SimpleFileCollection()
        1 * objCCompiler.execute({ ObjectiveCCompileSpec spec ->
            assert spec.sourceFiles*.name == ["sourceFile"]
            assert spec.args == ['arg']
            assert spec.allArgs == ['arg']
            assert spec.macros == [def: 'value']
            assert spec.objectFileDir.name == "outputFile"
            assert spec.preCompiledHeader == "header"
            assert spec.prefixHeaderFile.name == "prefixHeader"
            assert spec.preCompiledHeaderObjectFile.name == "pchObjectFile"
            true
        }) >> result
        1 * result.didWork >> true
        0 * _._

        and:
        objCCompile.didWork
    }
}
