/*
 * Copyright 2011 the original author or authors.
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
package org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser;

import com.google.common.collect.Lists;
import org.apache.ivy.core.module.descriptor.Configuration;
import org.apache.ivy.core.module.descriptor.Configuration.Visibility;
import org.apache.ivy.core.module.descriptor.DefaultExcludeRule;
import org.apache.ivy.core.module.descriptor.License;
import org.apache.ivy.core.module.id.ArtifactId;
import org.apache.ivy.core.module.id.ModuleId;
import org.apache.ivy.core.module.id.ModuleRevisionId;
import org.apache.ivy.plugins.matcher.ExactPatternMatcher;
import org.apache.ivy.plugins.matcher.PatternMatcher;
import org.gradle.api.artifacts.ModuleVersionSelector;
import org.gradle.api.internal.artifacts.DefaultModuleVersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.PomReader.PomDependencyData;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.data.PomDependencyMgt;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelector;
import org.gradle.api.internal.artifacts.ivyservice.ivyresolve.strategy.VersionSelectorScheme;
import org.gradle.internal.component.external.descriptor.Dependency;
import org.gradle.internal.component.external.descriptor.ModuleDescriptorState;
import org.gradle.internal.component.external.descriptor.MutableModuleDescriptorState;
import org.gradle.internal.component.external.model.DefaultModuleComponentIdentifier;
import org.gradle.internal.component.model.DefaultIvyArtifactName;
import org.gradle.internal.component.model.IvyArtifactName;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This a straight copy of org.apache.ivy.plugins.parser.m2.PomModuleDescriptorBuilder, with minor changes: 1) Do not create artifact for empty classifier. (Previously did so for all non-null
 * classifiers)
 */
public class GradlePomModuleDescriptorBuilder {
    public static final Configuration[] MAVEN2_CONFIGURATIONS = new Configuration[]{
            new Configuration("default", Visibility.PUBLIC,
                    "runtime dependencies and master artifact can be used with this conf",
                    new String[]{"runtime", "master"}, true, null),
            new Configuration("master", Visibility.PUBLIC,
                    "contains only the artifact published by this module itself, "
                            + "with no transitive dependencies",
                    new String[0], true, null),
            new Configuration("compile", Visibility.PUBLIC,
                    "this is the default scope, used if none is specified. "
                            + "Compile dependencies are available in all classpaths.",
                    new String[0], true, null),
            new Configuration("provided", Visibility.PUBLIC,
                    "this is much like compile, but indicates you expect the JDK or a container "
                            + "to provide it. "
                            + "It is only available on the compilation classpath, and is not transitive.",
                    new String[0], true, null),
            new Configuration("runtime", Visibility.PUBLIC,
                    "this scope indicates that the dependency is not required for compilation, "
                            + "but is for execution. It is in the runtime and test classpaths, "
                            + "but not the compile classpath.",
                    new String[]{"compile"}, true, null),
            new Configuration("test", Visibility.PRIVATE,
                    "this scope indicates that the dependency is not required for normal use of "
                            + "the application, and is only available for the test compilation and "
                            + "execution phases.",
                    new String[]{"runtime"}, true, null),
            new Configuration("system", Visibility.PUBLIC,
                    "this scope is similar to provided except that you have to provide the JAR "
                            + "which contains it explicitly. The artifact is always available and is not "
                            + "looked up in a repository.",
                    new String[0], true, null),
            new Configuration("sources", Visibility.PUBLIC,
                    "this configuration contains the source artifact of this module, if any.",
                    new String[0], true, null),
            new Configuration("javadoc", Visibility.PUBLIC,
                    "this configuration contains the javadoc artifact of this module, if any.",
                    new String[0], true, null),
            new Configuration("optional", Visibility.PUBLIC,
                    "contains all optional dependencies", new String[0], true, null)
    };

    static final Map<String, ConfMapper> MAVEN2_CONF_MAPPING = new HashMap<String, ConfMapper>();
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile("(.+)-\\d{8}\\.\\d{6}-\\d+");
    private static final String EXTRA_ATTRIBUTE_CLASSIFIER = "m:classifier";

    interface ConfMapper {
        void addMappingConfs(Dependency dd, boolean isOptional);
    }

    static {
        MAVEN2_CONF_MAPPING.put("compile", new ConfMapper() {
            public void addMappingConfs(Dependency dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    //dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");

                } else {
                    dd.addDependencyConfiguration("compile", "compile(*)");
                    //dd.addDependencyConfiguration("compile", "provided(*)");
                    dd.addDependencyConfiguration("compile", "master(*)");
                    dd.addDependencyConfiguration("runtime", "runtime(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("provided", new ConfMapper() {
            public void addMappingConfs(Dependency dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "runtime(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");
                } else {
                    dd.addDependencyConfiguration("provided", "compile(*)");
                    dd.addDependencyConfiguration("provided", "provided(*)");
                    dd.addDependencyConfiguration("provided", "runtime(*)");
                    dd.addDependencyConfiguration("provided", "master(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("runtime", new ConfMapper() {
            public void addMappingConfs(Dependency dd, boolean isOptional) {
                if (isOptional) {
                    dd.addDependencyConfiguration("optional", "compile(*)");
                    dd.addDependencyConfiguration("optional", "provided(*)");
                    dd.addDependencyConfiguration("optional", "master(*)");

                } else {
                    dd.addDependencyConfiguration("runtime", "compile(*)");
                    dd.addDependencyConfiguration("runtime", "runtime(*)");
                    dd.addDependencyConfiguration("runtime", "master(*)");
                }
            }
        });
        MAVEN2_CONF_MAPPING.put("test", new ConfMapper() {
            public void addMappingConfs(Dependency dd, boolean isOptional) {
                //optional doesn't make sense in the test scope
                dd.addDependencyConfiguration("test", "runtime(*)");
                dd.addDependencyConfiguration("test", "master(*)");
            }
        });
        MAVEN2_CONF_MAPPING.put("system", new ConfMapper() {
            public void addMappingConfs(Dependency dd, boolean isOptional) {
                //optional doesn't make sense in the system scope
                dd.addDependencyConfiguration("system", "master(*)");
            }
        });
    }

    private final VersionSelectorScheme defaultVersionSelectorScheme;
    private final VersionSelectorScheme mavenVersionSelectorScheme;

    private MutableModuleDescriptorState descriptor;

    private final PomReader pomReader;

    public GradlePomModuleDescriptorBuilder(PomReader pomReader, VersionSelectorScheme gradleVersionSelectorScheme, VersionSelectorScheme mavenVersionSelectorScheme) {
        this.defaultVersionSelectorScheme = gradleVersionSelectorScheme;
        this.mavenVersionSelectorScheme = mavenVersionSelectorScheme;
        this.pomReader = pomReader;
    }

    public ModuleDescriptorState getModuleDescriptor() {
        return descriptor;
    }

    public void setModuleRevId(String group, String module, String version) {
        String effectiveVersion = version;
        if (version != null) {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(version);
            if (matcher.matches()) {
                effectiveVersion = matcher.group(1) + "-SNAPSHOT";
            }
        }

        String status = effectiveVersion != null && effectiveVersion.endsWith("SNAPSHOT") ? "integration" : "release";

        descriptor = new MutableModuleDescriptorState(DefaultModuleComponentIdentifier.newId(group, module, effectiveVersion), status, false);
        for (Configuration maven2Configuration : MAVEN2_CONFIGURATIONS) {
            descriptor.addConfiguration(maven2Configuration.getName(), maven2Configuration.isTransitive(), maven2Configuration.getVisibility() == Visibility.PUBLIC, Lists.newArrayList(maven2Configuration.getExtends()));
        }
    }

    public void setHomePage(String homePage) {
    }

    public void setDescription(String description) {
        descriptor.setDescription(description);
    }

    public void setLicenses(Iterable<License> licenses) {
    }

    public void addDependency(PomDependencyData dep) {
        String scope = dep.getScope();
        if ((scope != null) && (scope.length() > 0) && !MAVEN2_CONF_MAPPING.containsKey(scope)) {
            // unknown scope, defaulting to 'compile'
            scope = "compile";
        }

        String version = determineVersion(dep);
        String mappedVersion = convertVersionFromMavenSyntax(version);
        ModuleVersionSelector selector = DefaultModuleVersionSelector.newSelector(dep.getGroupId(), dep.getArtifactId(), mappedVersion);

        // Some POMs depend on themselves, don't add this dependency: Ivy doesn't allow this!
        // Example: http://repo2.maven.org/maven2/net/jini/jsk-platform/2.1/jsk-platform-2.1.pom
        if (selector.getGroup().equals(descriptor.getComponentIdentifier().getGroup())
            && selector.getName().equals(descriptor.getComponentIdentifier().getModule())) {
            return;
        }

        Dependency dependency = descriptor.addDependency(selector);
        scope = (scope == null || scope.length() == 0) ? getDefaultScope(dep) : scope;
        ConfMapper mapping = MAVEN2_CONF_MAPPING.get(scope);
        mapping.addMappingConfs(dependency, dep.isOptional());
        boolean hasClassifier = dep.getClassifier() != null && dep.getClassifier().length() > 0;
        boolean hasNonJarType = dep.getType() != null && !"jar".equals(dep.getType());
        if (hasClassifier || hasNonJarType) {
            String type = "jar";
            if (dep.getType() != null) {
                type = dep.getType();
            }
            String ext = determineExtension(type);
            String classifier = hasClassifier ? dep.getClassifier() : getClassifierForType(type);

            // here we have to assume a type and ext for the artifact, so this is a limitation
            // compared to how m2 behave with classifiers
            String optionalizedScope = dep.isOptional() ? "optional" : scope;

            IvyArtifactName artifactName = new DefaultIvyArtifactName(selector.getName(), type, ext, classifier);
            dependency.addArtifact(artifactName, Collections.singleton(optionalizedScope));
        }

        // experimentation shows the following, excluded modules are
        // inherited from parent POMs if either of the following is true:
        // the <exclusions> element is missing or the <exclusions> element
        // is present, but empty.
        List<ModuleId> excluded = dep.getExcludedModules();
        if (excluded.isEmpty()) {
            excluded = getDependencyMgtExclusions(dep);
        }
        for (ModuleId excludedModule : excluded) {
            DefaultExcludeRule rule = new DefaultExcludeRule(new ArtifactId(
                excludedModule, PatternMatcher.ANY_EXPRESSION,
                PatternMatcher.ANY_EXPRESSION,
                PatternMatcher.ANY_EXPRESSION),
                ExactPatternMatcher.INSTANCE, null);
            for (String conf : dependency.getConfMappings().keySet()) {
                rule.addConfiguration(conf);
            }
            dependency.addExcludeRule(rule);
        }
    }

    private String convertVersionFromMavenSyntax(String version) {
        VersionSelector versionSelector = mavenVersionSelectorScheme.parseSelector(version);
        return defaultVersionSelectorScheme.renderSelector(versionSelector);
    }

    /**
     * Determines extension of dependency.
     *
     * @param type Type
     * @return Extension
     */
    private String determineExtension(String type) {
        return JarDependencyType.isJarExtension(type) ? "jar" : type;
    }

    /**
     * Handles special types of dependencies. If one of the following types matches, a specific type of classifier is set.
     *
     * - test-jar (see <a href="http://maven.apache.org/guides/mini/guide-attached-tests.html">Maven documentation</a>)
     * - ejb-client (see <a href="http://maven.apache.org/plugins/maven-ejb-plugin/examples/ejb-client-dependency.html">Maven documentation</a>)
     *
     * @param type Type
     */
    private String getClassifierForType(String type) {
        if(JarDependencyType.TEST_JAR.getName().equals(type)) {
            return "tests";
        } else if(JarDependencyType.EJB_CLIENT.getName().equals(type)) {
            return "client";
        }
        return null;
    }

    private enum JarDependencyType {
        TEST_JAR("test-jar"), EJB_CLIENT("ejb-client"), EJB("ejb"), BUNDLE("bundle"), MAVEN_PLUGIN("maven-plugin"), ECLIPSE_PLUGIN("eclipse-plugin");

        private static final Map<String, JarDependencyType> TYPES;

        static {
            TYPES = new HashMap<String, JarDependencyType>();

            for(JarDependencyType type : values()) {
                TYPES.put(type.name, type);
            }
        }

        private final String name;

        private JarDependencyType(String name) {
            this.name = name;
        }

        public String getName() {
            return name;
        }

        public static boolean isJarExtension(String type) {
            return TYPES.containsKey(type);
        }
    }

    /**
     * Determines the version of a dependency. Uses the specified version if declared for the as coordinate. If the version is not declared, try to resolve it from the dependency management section.
     * In case the version cannot be resolved with any of these methods, throw an exception of type {@see org.gradle.api.internal.artifacts.ivyservice.ivyresolve.parser.UnresolvedDependencyVersionException}.
     *
     * @param dependency Dependency
     * @return Resolved dependency version
     */
    private String determineVersion(PomDependencyData dependency) {
        String version = dependency.getVersion();
        version = (version == null || version.length() == 0) ? getDefaultVersion(dependency) : version;

        if (version == null) {
            throw new UnresolvedDependencyVersionException(dependency.getId());
        }

        return version;
    }

    public void addDependencyForRelocation(ModuleRevisionId moduleRevisionId) {
        ModuleVersionSelector selector = DefaultModuleVersionSelector.newSelector(moduleRevisionId);

        // Some POMs depend on themselves through their parent POM, don't add this dependency
        // since Ivy doesn't allow this!
        // Example: http://repo2.maven.org/maven2/com/atomikos/atomikos-util/3.6.4/atomikos-util-3.6.4.pom
        if (selector.getGroup().equals(descriptor.getComponentIdentifier().getGroup())
            && selector.getName().equals(descriptor.getComponentIdentifier().getModule())) {
            return;
        }

        Dependency dependency = descriptor.addDependency(selector);
        Configuration[] m2Confs = GradlePomModuleDescriptorBuilder.MAVEN2_CONFIGURATIONS;
        // Map dependency on all public configurations
        for (Configuration m2Conf : m2Confs) {
            if (Visibility.PUBLIC.equals(m2Conf.getVisibility())) {
                dependency.addDependencyConfiguration(m2Conf.getName(), m2Conf.getName());
            }
        }
    }

    private String getDefaultVersion(PomDependencyData dep) {
        PomDependencyMgt pomDependencyMgt = findDependencyDefault(dep);
        if (pomDependencyMgt != null) {
            return pomDependencyMgt.getVersion();
        }
        return null;
    }

    private String getDefaultScope(PomDependencyData dep) {
        PomDependencyMgt pomDependencyMgt = findDependencyDefault(dep);
        String result = null;
        if (pomDependencyMgt != null) {
            result = pomDependencyMgt.getScope();
        }
        if ((result == null) || !MAVEN2_CONF_MAPPING.containsKey(result)) {
            result = "compile";
        }
        return result;
    }

    private List<ModuleId> getDependencyMgtExclusions(PomDependencyData dep) {
        PomDependencyMgt pomDependencyMgt = findDependencyDefault(dep);
        if (pomDependencyMgt != null) {
            return pomDependencyMgt.getExcludedModules();
        }

        return Collections.emptyList();
    }

    private PomDependencyMgt findDependencyDefault(PomDependencyData dependency) {
        return pomReader.findDependencyDefaults(dependency.getId());
    }
}
