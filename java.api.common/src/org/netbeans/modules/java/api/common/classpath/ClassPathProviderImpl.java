/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Oracle and/or its affiliates. All rights reserved.
 *
 * Oracle and Java are registered trademarks of Oracle and/or its affiliates.
 * Other names may be trademarks of their respective owners.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common
 * Development and Distribution License("CDDL") (collectively, the
 * "License"). You may not use this file except in compliance with the
 * License. You can obtain a copy of the License at
 * http://www.netbeans.org/cddl-gplv2.html
 * or nbbuild/licenses/CDDL-GPL-2-CP. See the License for the
 * specific language governing permissions and limitations under the
 * License.  When distributing the software, include this License Header
 * Notice in each file and include the License file at
 * nbbuild/licenses/CDDL-GPL-2-CP.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the GPL Version 2 section of the License file that
 * accompanied this code. If applicable, add the following below the
 * License Header, with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Contributor(s):
 *
 * The Original Software is NetBeans. The Initial Developer of the Original
 * Software is Sun Microsystems, Inc. Portions Copyright 1997-2010 Sun
 * Microsystems, Inc. All Rights Reserved.
 *
 * If you wish your version of this file to be governed by only the CDDL
 * or only the GPL Version 2, indicate your decision by adding
 * "[Contributor] elects to include this software in this distribution
 * under the [CDDL or GPL Version 2] license." If you do not indicate a
 * single choice of license, a recipient has the option to distribute
 * your version of this file under either the CDDL, the GPL Version 2 or
 * to extend the choice of license to its licensees as provided above.
 * However, if you add GPL Version 2 code and therefore, elected the GPL
 * Version 2 license, then the option applies only if the new code is
 * made subject to such option by the copyright holder.
 */
package org.netbeans.modules.java.api.common.classpath;

import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;
import java.io.File;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Map;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.annotations.common.NullAllowed;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.project.FileOwnerQuery;
import org.netbeans.api.project.Project;
import org.netbeans.api.project.ProjectManager;
import org.netbeans.api.project.SourceGroup;
import org.netbeans.modules.java.api.common.SourceRoots;
import org.netbeans.modules.java.api.common.project.ProjectProperties;
import org.netbeans.modules.java.api.common.util.CommonProjectUtils;
import org.netbeans.spi.java.classpath.ClassPathFactory;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.ClassPathProvider;
import org.netbeans.spi.java.project.classpath.support.ProjectClassPathSupport;
import org.netbeans.spi.project.support.ant.AntProjectHelper;
import org.netbeans.spi.project.support.ant.PropertyEvaluator;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.modules.SpecificationVersion;
import org.openide.util.Mutex;
import org.openide.util.Parameters;
import org.openide.util.Union2;
import org.openide.util.WeakListeners;

/**
 * Defines the various class paths for a J2SE project.
 * @since org.netbeans.modules.java.api.common/1 1.5
 */
public final class ClassPathProviderImpl implements ClassPathProvider {

    private static final String buildGeneratedDir = "build.generated.sources.dir"; // NOI18N
    private static final String[] processorTestClasspath = new String[]{"javac.test.processorpath"};  //NOI18N

    private final AntProjectHelper helper;
    private final File projectDirectory;
    private final PropertyEvaluator evaluator;
    private final SourceRoots sourceRoots;
    private final SourceRoots testSourceRoots;
    private final String buildClassesDir;
    private final String distJar;
    private final String buildTestClassesDir;
    private final String[] javacClasspath;
    private final String[] processorClasspath;
    private final String[] javacTestClasspath;
    private final String[] runClasspath;
    private final String[] runTestClasspath;
    private final String[] endorsedClasspath;
    private final String[] modulePath;
    private final String[] testModulePath;
    private final String[] moduleExecutePath;
    private final String[] testModuleExecutePath;
    private final Union2<String,String[]> platform;
    private final String javacSource;
    private final Project project;
    /**
     * ClassPaths cache.
     * Index -> CP mapping
     * 0  -  source path
     * 1  -  test source path
     * 2  -  class path
     * 3  -  test class path
     * 4  -  execute class path
     * 5  -  test execute class path
     * 6  -  execute class path for dist.jar
     * 7  -  boot class path
     * 8  -  endorsed class path
     * 9  -  processor path
     * 10  - test processor path
     * 11  - module boot path
     * 12  - module compile path
     * 13  - test module compile path
     * 14  - module class path
     * 15  - test module class path
     * 16  - module execute path
     * 17  - test module execute path
     * 18  - module execute path for dist.jar
     * 19  - module execute class path
     * 20  - test module execute class path
     * 21  - module execute class path for dist.jar
     * 22  - JDK8 class path                        - internal only
     * 23  - JDK8 test class path                   - internal only
     * 24  - JDK8 execute class path                - internal only
     * 25  - JDK8 test execute class path           - internal only
     * 26  - JDK8 execute class path for dist.jar   - internal only
     */
    private final ClassPath[/*@GuardedBy("this")*/] cache = new ClassPath[27];
    private final Map</*@GuardedBy("this")*/String,FileObject> dirCache = new ConcurrentHashMap<>();


    public ClassPathProviderImpl(AntProjectHelper helper, PropertyEvaluator evaluator, SourceRoots sourceRoots,
                                 SourceRoots testSourceRoots) {
        this(
            helper,
            evaluator,
            sourceRoots,
            testSourceRoots,
            Builder.DEFAULT_BUILD_CLASSES_DIR,
            Builder.DEFAULT_DIST_JAR,
            Builder.DEFAULT_BUILD_TEST_CLASSES_DIR,
            Builder.DEFAULT_JAVAC_CLASS_PATH,
            Builder.DEFAULT_JAVAC_TEST_CLASS_PATH,
            Builder.DEFAULT_RUN_CLASS_PATH,
            Builder.DEFAULT_RUN_TEST_CLASS_PATH);
    }

    public ClassPathProviderImpl(AntProjectHelper helper, PropertyEvaluator evaluator,
            SourceRoots sourceRoots, SourceRoots testSourceRoots,
            String buildClassesDir, String distJar, String buildTestClassesDir,
            String[] javacClasspath, String[] javacTestClasspath, String[] runClasspath,
            String[] runTestClasspath) {
        this(
            helper,
            evaluator,
            sourceRoots,
            testSourceRoots,
            buildClassesDir,
            distJar,
            buildTestClassesDir,
            javacClasspath,
            javacTestClasspath,
            runClasspath,
            runTestClasspath,
            Builder.DEFAULT_ENDORSED_CLASSPATH);
    }
    /**
     * Constructor allowing customization of endorsedClasspath property names.
     * @since org.netbeans.modules.java.api.common/0 1.11
     */
    public ClassPathProviderImpl(AntProjectHelper helper, PropertyEvaluator evaluator,
            SourceRoots sourceRoots, SourceRoots testSourceRoots,
            String buildClassesDir, String distJar, String buildTestClassesDir,
            String[] javacClasspath, String[] javacTestClasspath, String[] runClasspath,
            String[] runTestClasspath, String[] endorsedClasspath) {
        this(
            helper,
            evaluator,
            sourceRoots,
            testSourceRoots,
            buildClassesDir,
            distJar,
            buildTestClassesDir,
            javacClasspath,
            Builder.DEFAULT_PROCESSOR_PATH,
            javacTestClasspath,
            runClasspath,
            runTestClasspath,
            endorsedClasspath);
    }

    /**
     * Constructor allowing customization of processorPath.
     * @since org.netbeans.modules.java.api.common/0 1.14
     */
    public ClassPathProviderImpl(AntProjectHelper helper, PropertyEvaluator evaluator,
            SourceRoots sourceRoots, SourceRoots testSourceRoots,
            String buildClassesDir, String distJar, String buildTestClassesDir,
            String[] javacClasspath, String[] processorPath, String[] javacTestClasspath, String[] runClasspath,
            String[] runTestClasspath, String[] endorsedClasspath) {
        this(
            helper,
            evaluator,
            sourceRoots,
            testSourceRoots,
            buildClassesDir,
            distJar,
            buildTestClassesDir,
            javacClasspath,
            processorPath,
            javacTestClasspath,
            runClasspath,
            runTestClasspath,
            endorsedClasspath,
            Builder.DEFAULT_MODULE_PATH,
            Builder.DEFAULT_TEST_MODULE_PATH,
            Builder.DEFAULT_MODULE_EXECUTE_PATH,
            Builder.DEFAULT_TEST_MODULE_EXECUTE_PATH,
            Union2.<String,String[]>createFirst(CommonProjectUtils.J2SE_PLATFORM_TYPE),
            Builder.DEFAULT_JAVAC_SOURCE,
            null);
    }

    private ClassPathProviderImpl(
        @NonNull final AntProjectHelper helper,
        @NonNull final PropertyEvaluator evaluator,
        @NonNull final SourceRoots sourceRoots,
        @NonNull final SourceRoots testSourceRoots,
        @NonNull final String buildClassesDir,
        @NonNull final String distJar,
        @NonNull final String buildTestClassesDir,
        @NonNull final String[] javacClasspath,
        @NonNull final String[] processorPath,
        @NonNull final String[] javacTestClasspath,
        @NonNull final String[] runClasspath,
        @NonNull final String[] runTestClasspath,
        @NonNull final String[] endorsedClasspath,
        @NonNull final String[] modulePath,
        @NonNull final String[] testModulePath,
        @NonNull final String[] moduleExecutePath,
        @NonNull final String[] testModuleExecutePath,
        @NonNull final Union2<String,String[]> platform,
        @NonNull final String javacSource,
        @NullAllowed final Project project) {
        Parameters.notNull("helper", helper);   //NOI18N
        Parameters.notNull("evaluator", evaluator); //NOI18N
        Parameters.notNull("sourceRoots", sourceRoots); //NOI18N
        Parameters.notNull("testSourceRoots", testSourceRoots); //NOI18N
        Parameters.notNull("buildClassesDir", buildClassesDir); //NOI18N
        Parameters.notNull("distJar", distJar); //NOI18N
        Parameters.notNull("buildTestClassesDir", buildTestClassesDir); //NOI18N
        Parameters.notNull("javacClasspath", javacClasspath);   //NOI18N
        Parameters.notNull("processorPath", processorPath); //NOI18N
        Parameters.notNull("javacTestClasspath", javacTestClasspath);   //NOI18N
        Parameters.notNull("runClasspath", runClasspath);   //NOI18N
        Parameters.notNull("runTestClasspath", runTestClasspath);   //NOI18N
        Parameters.notNull("endorsedClasspath", endorsedClasspath); //NOI18N
        Parameters.notNull("modulePath", modulePath);   //NOI18N
        Parameters.notNull("testModulePath", testModulePath);   //NOI18N
        Parameters.notNull("moduleExecutePath", moduleExecutePath); //NOI18N
        Parameters.notNull("testModuleExecutePath", testModuleExecutePath); //NOI18N
        Parameters.notNull("platform", platform); //NOI18N
        Parameters.notNull("javacSource", javacSource); //NOI18N
        this.helper = helper;
        this.projectDirectory = FileUtil.toFile(helper.getProjectDirectory());
        assert this.projectDirectory != null;
        this.evaluator = evaluator;
        this.sourceRoots = sourceRoots;
        this.testSourceRoots = testSourceRoots;
        this.buildClassesDir = buildClassesDir;
        this.distJar = distJar;
        this.buildTestClassesDir = buildTestClassesDir;
        this.javacClasspath = javacClasspath;
        this.processorClasspath = processorPath;
        this.javacTestClasspath = javacTestClasspath;
        this.runClasspath = runClasspath;
        this.runTestClasspath = runTestClasspath;
        this.endorsedClasspath = endorsedClasspath;
        this.modulePath = modulePath;
        this.testModulePath = testModulePath;
        this.moduleExecutePath = moduleExecutePath;
        this.testModuleExecutePath = testModuleExecutePath;
        this.platform = platform;
        this.javacSource = javacSource;
        this.project = project;
    }

    /**
     * Builder to create ClassPathProviderImpl.
     * @since 1.59
     */
    public static final class Builder {

        private static final String DEFAULT_BUILD_CLASSES_DIR = "build.classes.dir";   //NOI18N
        private static final String DEFAULT_BUILD_TEST_CLASSES_DIR = "build.test.classes.dir"; // NOI18N
        private static final String DEFAULT_DIST_JAR = "dist.jar"; // NOI18N
        private static final String[] DEFAULT_JAVAC_CLASS_PATH = new String[]{"javac.classpath"};    //NOI18N
        private static final String[] DEFAULT_PROCESSOR_PATH = new String[]{ProjectProperties.JAVAC_PROCESSORPATH};    //NOI18N
        private static final String[] DEFAULT_JAVAC_TEST_CLASS_PATH = new String[]{"javac.test.classpath"};  //NOI18N
        private static final String[] DEFAULT_RUN_CLASS_PATH = new String[]{"run.classpath"};    //NOI18N
        private static final String[] DEFAULT_RUN_TEST_CLASS_PATH = new String[]{"run.test.classpath"};  //NOI18N
        private static final String[] DEFAULT_ENDORSED_CLASSPATH = new String[]{ProjectProperties.ENDORSED_CLASSPATH};  //NOI18N
        private static final String[] DEFAULT_MODULE_PATH = new String[]{ProjectProperties.JAVAC_MODULEPATH};
        private static final String[] DEFAULT_TEST_MODULE_PATH = new String[] {ProjectProperties.JAVAC_TEST_MODULEPATH};
        private static final String[] DEFAULT_MODULE_EXECUTE_PATH = new String[]{ProjectProperties.RUN_MODULEPATH};
        private static final String[] DEFAULT_TEST_MODULE_EXECUTE_PATH = new String[]{ProjectProperties.RUN_TEST_MODULEPATH};
        private static final String DEFAULT_JAVAC_SOURCE = ProjectProperties.JAVAC_SOURCE;

        private final AntProjectHelper helper;
        private final PropertyEvaluator evaluator;
        private final SourceRoots sourceRoots;
        private final SourceRoots testSourceRoots;

        private String platformType = CommonProjectUtils.J2SE_PLATFORM_TYPE;
        private String buildClassesDir = DEFAULT_BUILD_CLASSES_DIR;
        private String buildTestClassesDir = DEFAULT_BUILD_TEST_CLASSES_DIR;
        private String distJar = DEFAULT_DIST_JAR;
        private String[] javacClasspath = DEFAULT_JAVAC_CLASS_PATH;
        private String[] processorPath = DEFAULT_PROCESSOR_PATH;
        private String[] javacTestClasspath = DEFAULT_JAVAC_TEST_CLASS_PATH;
        private String[] runClasspath = DEFAULT_RUN_CLASS_PATH;
        private String[] runTestClasspath = DEFAULT_RUN_TEST_CLASS_PATH;
        private String[] endorsedClasspath = DEFAULT_ENDORSED_CLASSPATH;
        private String[] modulePath = DEFAULT_MODULE_PATH;
        private String[] testModulePath = DEFAULT_TEST_MODULE_PATH;
        private String[] moduleExecutePath = DEFAULT_MODULE_EXECUTE_PATH;
        private String[] testModuleExecutePath = DEFAULT_TEST_MODULE_EXECUTE_PATH;
        private String[] bootClasspathProperties;
        private String javacSource = DEFAULT_JAVAC_SOURCE;
        private Project project;

        private Builder(
            @NonNull final AntProjectHelper helper,
            @NonNull final PropertyEvaluator evaluator,
            @NonNull final SourceRoots sourceRoots,
            @NonNull final SourceRoots testSourceRoots) {
            Parameters.notNull("helper", helper);   //NOI18N
            Parameters.notNull("evaluator", evaluator); //NOI18N
            Parameters.notNull("sourceRoots", sourceRoots); //NOI18N
            Parameters.notNull("testSourceRoots", testSourceRoots); //NOI18N
            this.helper = helper;
            this.evaluator = evaluator;
            this.sourceRoots = sourceRoots;
            this.testSourceRoots = testSourceRoots;
        }

        /**
         * Sets a {@link JavaPlatform} type for boot classpath lookup.
         * @param platformType the type of {@link JavaPlatform}, by default "j2se"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setPlatformType(@NonNull final String platformType) {
            Parameters.notNull("platformType", platformType);   //NOI18N
            this.platformType = platformType;
            return this;
        }

        /**
         * Sets a property name containing build classes directory.
         * @param buildClassesDirProperty the name of property containing the build classes directory, by default "build.classes.dir"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setBuildClassesDirProperty(@NonNull final String buildClassesDirProperty) {
            Parameters.notNull("buildClassesDirProperty", buildClassesDirProperty); //NOI18N
            this.buildClassesDir = buildClassesDirProperty;
            return this;
        }

        /**
         * Sets a property name containing build test classes directory.
         * @param buildTestClassesDirProperty the name of property containing the build test classes directory, by default "build.test.classes.dir"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setBuildTestClassesDirProperty(@NonNull final String buildTestClassesDirProperty) {
            Parameters.notNull("buildTestClassesDirProperty", buildTestClassesDirProperty); //NOI18N
            this.buildTestClassesDir = buildTestClassesDirProperty;
            return this;
        }

        /**
         * Sets a property name containing the distribution jar.
         * @param distJarProperty the name of property containing the distribution jar reference, by default "dist.jar"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setDistJarProperty(@NonNull final String distJarProperty) {
            Parameters.notNull("distJarProperty", distJarProperty); //NOI18N
            this.distJar = distJarProperty;
            return this;
        }

        /**
         * Sets javac classpath properties for source roots.
         * @param javacClassPathProperties the names of properties containing the compiler classpath for sources, by default "javac.classpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setJavacClassPathProperties(@NonNull final String[] javacClassPathProperties) {
            Parameters.notNull("javacClassPathProperties", javacClassPathProperties);   //NOI18N
            this.javacClasspath = Arrays.copyOf(javacClassPathProperties, javacClassPathProperties.length);
            return this;
        }

        /**
         * Sets javac processor path properties for source roots.
         * @param processorPathProperties the names of properties containing the compiler processor path for sources, by default "javac.processorpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setProcessorPathProperties(@NonNull final String[] processorPathProperties) {
            Parameters.notNull("processorPathProperties", processorPathProperties);
            this.processorPath = Arrays.copyOf(processorPathProperties, processorPathProperties.length);
            return this;
        }

        /**
         * Sets javac classpath properties for test roots.
         * @param javacTestClasspathProperties  the names of properties containing the compiler classpath for tests, by default "javac.test.classpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setJavacTestClasspathProperties(@NonNull final String[] javacTestClasspathProperties) {
            Parameters.notNull("javacTestClasspathProperties", javacTestClasspathProperties);   //NOI18N
            this.javacTestClasspath = Arrays.copyOf(javacTestClasspathProperties, javacTestClasspathProperties.length);
            return this;
        }

        /**
         * Sets runtime classpath properties for source roots.
         * @param runClasspathProperties the names of properties containing the runtime classpath for sources, by default "run.classpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setRunClasspathProperties(@NonNull final String[] runClasspathProperties) {
            Parameters.notNull("runClasspathProperties", runClasspathProperties);   //NOI18N
            this.runClasspath = Arrays.copyOf(runClasspathProperties, runClasspathProperties.length);
            return this;
        }

        /**
         * Sets runtime classpath properties for test roots.
         * @param runTestClasspathProperties  the names of properties containing the runtime classpath for tests, by default "run.test.classpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setRunTestClasspathProperties(@NonNull final String[] runTestClasspathProperties) {
            Parameters.notNull("runTestClasspathProperties", runTestClasspathProperties);   //NOI18N
            this.runTestClasspath = Arrays.copyOf(runTestClasspathProperties, runTestClasspathProperties.length);
            return this;
        }

        /**
         * Sets endorsed classpath properties.
         * @param endorsedClasspathProperties the names of properties containing the endorsed classpath, by default "endorsed.classpath"
         * @return {@link Builder}
         */
        @NonNull
        public Builder setEndorsedClasspathProperties(@NonNull final String[] endorsedClasspathProperties) {
            Parameters.notNull("endorsedClasspathProperties", endorsedClasspathProperties); //NOI18N
            this.endorsedClasspath = Arrays.copyOf(endorsedClasspathProperties, endorsedClasspathProperties.length);
            return this;
        }

        /**
         * Sets module path properties.
         * @param modulePathProperties  the names of properties containing the module path, by default {@link ProjectProperties#JAVAC_MODULEPATH}
         * @return {@link Builder}
         * @since 1.77
         */
        @NonNull
        public Builder setModulepathProperties(@NonNull final String[] modulePathProperties) {
            Parameters.notNull("modulePathProperties", modulePathProperties);
            this.modulePath = Arrays.copyOf(modulePathProperties, modulePathProperties.length);
            return this;
        }

        /**
         * Sets test module path properties.
         * @param modulePathProperties  the names of properties containing the test module path, by default {@link ProjectProperties#JAVAC_TEST_MODULEPATH}
         * @return {@link Builder}
         * @since 1.77
         */
        @NonNull
        public Builder setTestModulepathProperties(@NonNull final String[] modulePathProperties) {
            Parameters.notNull("modulePathProperties", modulePathProperties);
            this.testModulePath = Arrays.copyOf(modulePathProperties, modulePathProperties.length);
            return this;
        }
        
        /**
         * Sets runtime module path properties.
         * @param modulePathProperties  the names of properties containing the runtime module path, by default {@link ProjectProperties#RUN_MODULEPATH}
         * @return {@link Builder}
         * @since 1.86
         */
        @NonNull
        public Builder setRunModulepathProperties(@NonNull final String[] modulePathProperties) {
            Parameters.notNull("modulePathProperties", modulePathProperties);
            this.moduleExecutePath = Arrays.copyOf(modulePathProperties, modulePathProperties.length);
            return this;
        }

        /**
         * Sets test runtime module path properties.
         * @param modulePathProperties  the names of properties containing the test runtime module path, by default {@link ProjectProperties#RUN_TEST_MODULEPATH}
         * @return {@link Builder}
         * @since 1.86
         */
        @NonNull
        public Builder setRunTestModulepathProperties(@NonNull final String[] modulePathProperties) {
            Parameters.notNull("modulePathProperties", modulePathProperties);
            this.testModuleExecutePath = Arrays.copyOf(modulePathProperties, modulePathProperties.length);
            return this;
        }

        /**
         * Sets boot classpath properties.
         * Some project types do not use {@link JavaPlatform#getBootstrapLibraries()} as boot classpath but
         * have a project property specifying the boot classpath. Setting the boot classpath properties
         * causes that the {@link Project}'s boot classpath is not taken from {@link Project}'s {@link JavaPlatform}
         * but from these properties.
         * @param bootClasspathProperties  the names of properties containing the boot classpath
         * @return {@link Builder}
         * @since 1.67
         */
        @NonNull
        public Builder setBootClasspathProperties(@NonNull final String... bootClasspathProperties) {
            Parameters.notNull("bootClasspathProperties", bootClasspathProperties); //NOI18N
            this.bootClasspathProperties = Arrays.copyOf(bootClasspathProperties, bootClasspathProperties.length);
            return this;
        }
        
        @NonNull
        public Builder setProject(@NonNull final Project project) {
            Parameters.notNull("project", project); //NOI18N
            this.project = project;
            return this;
        }

        /**
         * Sets javac source level property.
         * @param javacSource the name of the property containing the javac source level
         * @return {@link Builder}
         * @since 1.76
         */
        @NonNull
        public Builder setJavacSourceProperty(@NonNull final String javacSource) {
            Parameters.notNull("javacSource", javacSource); //NOI18N
            this.javacSource = javacSource;
            return this;
        }

        /**
         * Creates a configured {@link ClassPathProviderImpl}.
         * @return the {@link ClassPathProviderImpl}
         */
        @NonNull
        public ClassPathProviderImpl build() {
            final Union2<String,String[]> platform =
                bootClasspathProperties == null ?
                    Union2.<String,String[]>createFirst(platformType) :
                    Union2.<String,String[]>createSecond(bootClasspathProperties);
            return new ClassPathProviderImpl (
                helper,
                evaluator,
                sourceRoots,
                testSourceRoots,
                buildClassesDir,
                distJar,
                buildTestClassesDir,
                javacClasspath,
                processorPath,
                javacTestClasspath,
                runClasspath,
                runTestClasspath,
                endorsedClasspath,
                modulePath,
                testModulePath,
                moduleExecutePath,
                testModuleExecutePath,
                platform,
                javacSource,
                project);
        }

        @NonNull
        public static Builder create(
            @NonNull final AntProjectHelper helper,
            @NonNull final PropertyEvaluator evaluator,
            @NonNull final SourceRoots sourceRoots,
            @NonNull final SourceRoots testSourceRoots) {
            return new Builder(helper, evaluator, sourceRoots, testSourceRoots);
        }

    }


    private FileObject getDir(@NonNull final String propname) {
        FileObject fo = dirCache.get(propname);
        if (fo == null || !fo.isValid()) {
            String prop = evaluator.getProperty(propname);
            if (prop != null) {
                fo = helper.resolveFileObject(prop);
                if (fo != null) {
                    dirCache.put (propname, fo);
                }
            }
        }
        return fo;
    }

    private FileObject[] getPrimarySrcPath() {
        return this.sourceRoots.getRoots();
    }
    
    private FileObject[] getTestSrcDir() {
        return this.testSourceRoots.getRoots();
    }
    
    private FileObject getBuildClassesDir() {
        return getDir(buildClassesDir);
    }

    private FileObject getBuildGeneratedDir() {
        return getDir(buildGeneratedDir);
    }

    private FileObject getDistJar() {
        return getDir(distJar);
    }
    
    private FileObject getBuildTestClassesDir() {
        return getDir(buildTestClassesDir);
    }
    
    private FileObject getAnnotationProcessingSourceOutputDir() {
        return getDir(ProjectProperties.ANNOTATION_PROCESSING_SOURCE_OUTPUT);
    }

    /**
     * Find what a given file represents.
     * @param file a file in the project
     * @return one of: <dl>
     *         <dt>0</dt> <dd>normal source</dd>
     *         <dt>1</dt> <dd>test source</dd>
     *         <dt>2</dt> <dd>built class (unpacked)</dd>
     *         <dt>3</dt> <dd>built test class</dd>
     *         <dt>4</dt> <dd>built class (in dist JAR)</dd>
     *         <dt>-1</dt> <dd>something else</dd>
     *         </dl>
     */
    private int getType(FileObject file) {
        FileObject[] srcPath = getPrimarySrcPath();
        for (int i=0; i < srcPath.length; i++) {
            FileObject root = srcPath[i];
            if (root.equals(file) || FileUtil.isParentOf(root, file)) {
                return 0;
            }
        }        
        srcPath = getTestSrcDir();
        for (int i=0; i< srcPath.length; i++) {
            FileObject root = srcPath[i];
            if (root.equals(file) || FileUtil.isParentOf(root, file)) {
                return 1;
            }
        }
        FileObject dir = getBuildClassesDir();
        if (dir != null && (dir.equals(file) || FileUtil.isParentOf(dir, file))) {
            return 2;
        }
        dir = getDistJar(); // not really a dir at all, of course
        if (dir != null && dir.equals(FileUtil.getArchiveFile(file))) {
            // XXX check whether this is really the root
            return 4;
        }
        dir = getBuildTestClassesDir();
        if (dir != null && (dir.equals(file) || FileUtil.isParentOf(dir,file))) {
            return 3;
        }
        dir = getBuildGeneratedDir();
        if (dir != null && FileUtil.isParentOf(dir, file) /* but dir != file */) { // #105645
            dir = getAnnotationProcessingSourceOutputDir();
            if (dir != null && (dir.equals(file) || FileUtil.isParentOf(dir, file))) { //not the annotation processing source output
                return -1;
            }
            return 0;
        }
        return -1;
    }
    
    private ClassPath getCompileTimeClasspath(FileObject file) {
        int type = getType(file);
        return this.getCompileTimeClasspath(type);
    }
    
    private ClassPath getCompileTimeClasspath(int type) {
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(2+type, () -> {
                    return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(createSourceLevelSelector(
                            ()->getJava8ClassPath(type),
                            ()->ClassPathFactory.createClassPath(
                                ModuleClassPaths.createModuleInfoBasedPath(
                                    getModuleCompilePath(type),
                                    type == 0 ? sourceRoots : testSourceRoots,
                                    getModuleBootPath(),
                                    getJava8ClassPath(type),
                                    null))));
                });
            default:
                return null;
        }
    }

    private ClassPath getProcessorClasspath(FileObject file) {
        int type = getType(file);
        return this.getProcessorClasspath(type);
    }

    private ClassPath getProcessorClasspath(int type) {
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(9+type, () -> {
                    return ClassPathFactory.createClassPath(ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                            projectDirectory, evaluator, type == 0 ? processorClasspath : processorTestClasspath));
                });
            default:
                return null;
        }
    }

    private ClassPath getRunTimeClasspath(FileObject file) {
        int type = getType(file);
        if (type < 0 || type > 4) {
            // Unregistered file, or in a JAR.
            // For jar:file:$projdir/dist/*.jar!/**/*.class, it is misleading to use
            // run.classpath since that does not actually contain the file!
            // (It contains file:$projdir/build/classes/ instead.)
            return null;
        }
        return getRunTimeClasspath(type);
    }
    
    private ClassPath getRunTimeClasspath(final int type) {
        int index;
        switch (type) {
            case 0:
            case 2:
                index = 4;
                break;
            case 1:
            case 3:
                index = 5;
                break;
            case 4:
                index = 6;
                break;
            default:
                return null;
        }
        return computeIfAbsent(index, () -> {
            return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(
                    createSourceLevelSelector(
                            ()->getJava8RunTimeClassPath(type),
                            ()->ClassPathFactory.createClassPath(
                                ModuleClassPaths.createModuleInfoBasedPath(
                                    getModuleExecutePath(type),
                                    index != 5 ? sourceRoots : testSourceRoots,
                                    getModuleBootPath(),
                                    getJava8RunTimeClassPath(type),
                                    getFilter(type)))));
        });
    }

    private ClassPath getEndorsedClasspath() {
        return computeIfAbsent(8, () -> {
            return ClassPathFactory.createClassPath(ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                    projectDirectory, evaluator, endorsedClasspath));
        });
    }

    private ClassPath getSourcepath(FileObject file) {
        int type = getType(file);
        return this.getSourcepath(type);
    }

    @CheckForNull
    private ClassPath getSourcepath(final int type) {
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(type, () -> {
                    return ClassPathFactory.createClassPath(ClassPathSupportFactory.createSourcePathImplementation (
                            type == 0 ? this.sourceRoots : this.testSourceRoots, helper, evaluator));
                });
            default:
                return null;
        }
    }

    private ClassPath getBootClassPath() {
        return computeIfAbsent(7, () -> {
            if (platform.hasFirst()) {
                return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(
                        createSourceLevelSelector(
                                ()->ClassPathFactory.createClassPath(
                                    ClassPathSupportFactory.createBootClassPathImplementation(
                                        evaluator,
                                        project,
                                        getEndorsedClasspath(),
                                        platform.first())),
                                ()->ClassPathFactory.createClassPath(ModuleClassPaths.createModuleInfoBasedPath(
                                    getModuleBootPath(),
                                    sourceRoots,
                                    null))));
            } else {
                assert platform.hasSecond();
                return org.netbeans.spi.java.classpath.support.ClassPathSupport.createProxyClassPath(
                    getEndorsedClasspath(),
                    ClassPathFactory.createClassPath(
                        ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                            projectDirectory,
                            evaluator,
                            platform.second())));
            }
        });
    }

    @NonNull
    private ClassPath getModuleBootPath() {
        return computeIfAbsent(11, () -> {
            if (platform.hasFirst()) {
                return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(
                        createSourceLevelSelector(
                                ()->ClassPathFactory.createClassPath(
                                    ClassPathSupportFactory.createBootClassPathImplementation(
                                        evaluator,
                                        getEndorsedClasspath(),
                                        platform.first())),
                                ()->ClassPathFactory.createClassPath(ModuleClassPaths.createPlatformModulePath(
                                    evaluator,
                                    platform.first()))));
            } else {
                return ClassPath.EMPTY;
            }
        });
    }

    @CheckForNull
    private ClassPath getModuleCompilePath(@NonNull final FileObject fo) {
        final int type = getType(fo);
        if (type < 0 || type > 1) {
            return null;
        }
        return getModuleCompilePath(type);
    }

    @NonNull
    private ClassPath getModuleCompilePath(final int type) {
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(12+type, () -> {
                    return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(createSourceLevelSelector(
                            ()->ClassPath.EMPTY,
                            ()->ClassPathFactory.createClassPath(ModuleClassPaths.createPropertyBasedModulePath(
                                projectDirectory,
                                evaluator,
                                type == 0 ? modulePath : testModulePath))));
                });
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
    }

    @CheckForNull
    private ClassPath getModuleLegacyClassPath(@NonNull final FileObject fo) {
        final int type = getType(fo);
        if (type < 0 || type > 1) {
            return null;
        }
        return getModuleLegacyClassPath(type);
    }

    @NonNull
    private ClassPath getModuleLegacyClassPath(final int type) {
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(14+type, () -> {
                    return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(createSourceLevelSelector(
                            ()->ClassPath.EMPTY,
                            ()->getJava8ClassPath(type)));
                });
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
    }

    @CheckForNull
    private ClassPath getModuleExecutePath(@NonNull final FileObject fo) {
        final int type = getType(fo);
        if (type < 0 || type > 4) {
            return null;
        }
        return getModuleExecutePath(type);
    }
    
    @NonNull
    private ClassPath getModuleExecutePath(final int type) {
        int index;
        switch (type) {
            case 0:
            case 2:
                index = 16;
                break;
            case 1:
            case 3:
                index = 17;
                break;
            case 4:
                index = 18;
                break;
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
        Supplier<ClassPathImplementation> provider;
        switch (index) {
            case 16:
                provider = () -> {
                    final String[] props = new String[moduleExecutePath.length+1];
                    props[0] = buildClassesDir;
                    System.arraycopy(moduleExecutePath, 0, props, 1, moduleExecutePath.length);
                    return ModuleClassPaths.createPropertyBasedModulePath(
                        projectDirectory,
                        evaluator,
                        props);
                };
                break;
            case 17:
                provider = () -> {
                    final String[] props = new String[testModuleExecutePath.length+1];
                    props[0] = buildTestClassesDir;
                    System.arraycopy(testModuleExecutePath, 0, props, 1, testModuleExecutePath.length);
                    return ModuleClassPaths.createPropertyBasedModulePath(
                        projectDirectory,
                        evaluator,
                        props);
                };
                break;
            case 18:
                provider = () -> {
                    String[] props = new String[moduleExecutePath.length+1];
                    props[0] = distJar;
                    System.arraycopy(moduleExecutePath, 0, props, 1, moduleExecutePath.length);
                    return ModuleClassPaths.createPropertyBasedModulePath(
                        projectDirectory,
                        evaluator,
                        new Filter(null, buildClassesDir),
                        props);
                };
                break;
            default:
                throw new IllegalStateException(Integer.toString(index));
        }
        return computeIfAbsent(index, () -> {
            return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(createSourceLevelSelector(
                    ()->ClassPath.EMPTY,
                    ()->ClassPathFactory.createClassPath(provider.get())));
        });
    }

    @CheckForNull
    private ClassPath getModuleLegacyExecuteClassPath(@NonNull final FileObject fo) {
        final int type = getType(fo);
        if (type < 0 || type > 4) {
            return null;
        }
        return getModuleLegacyExecuteClassPath(type);
    }
    
    @NonNull
    private ClassPath getModuleLegacyExecuteClassPath(final int type) {
        int index;
        switch (type) {
            case 0:
            case 2:
                index = 19;
                break;
            case 1:
            case 3:
                index = 20;
                break;
            case 4:
                index = 21;
                break;
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
        return computeIfAbsent(index, () -> {
            return org.netbeans.spi.java.classpath.support.ClassPathSupport.createMultiplexClassPath(createSourceLevelSelector(
                    ()->ClassPath.EMPTY,
                    ()->getJava8RunTimeClassPath(type)));
        });
    }

    @NonNull
    private ClassPath getJava8ClassPath(int type) {
        assert type >= 0 && type <=1;
        switch (type) {
            case 0:
            case 1:
                return computeIfAbsent(22+type, () -> {
                    return ClassPathFactory.createClassPath(ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                            projectDirectory, evaluator, type == 0 ? javacClasspath : javacTestClasspath));
                });
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
    }

    @NonNull
    private ClassPath getJava8RunTimeClassPath(final int type) {
        int index;
        switch (type) {
            case 0:
            case 2:
                index = 24;
                break;
            case 1:
            case 3:
                index = 25;
                break;
            case 4:
                index = 26;
                break;
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
        Supplier<ClassPath> provider;
        switch (index) {
            case 24:
            case 25:
                provider = () -> {
                    return ClassPathFactory.createClassPath(ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                            projectDirectory, evaluator, index == 24 ? runClasspath : runTestClasspath));
                };
                break;
            case 26:
                provider = () -> {
                    final String[] props = new String[runClasspath.length+1];
                    System.arraycopy(runClasspath, 0, props, 1, runClasspath.length);
                    props[0] = distJar;
                    return ClassPathFactory.createClassPath(ProjectClassPathSupport.createPropertyBasedClassPathImplementation(
                        projectDirectory, evaluator, props));
                };
                break;
            default:
                throw new IllegalStateException(Integer.toString(index));
        }
        return computeIfAbsent(index, provider);
    }

    @Override
    public ClassPath findClassPath(FileObject file, String type) {
        if (type.equals(ClassPath.COMPILE)) {
            return getCompileTimeClasspath(file);
        } else if (type.equals(JavaClassPathConstants.PROCESSOR_PATH)) {
            return getProcessorClasspath(file);
        } else if (type.equals(ClassPath.EXECUTE)) {
            return getRunTimeClasspath(file);
        } else if (type.equals(ClassPath.SOURCE)) {
            return getSourcepath(file);
        } else if (type.equals(ClassPath.BOOT)) {
            return getBootClassPath();
        } else if (type.equals(ClassPathSupport.ENDORSED)) {
            return getEndorsedClasspath();
        } else if (type.equals(JavaClassPathConstants.MODULE_BOOT_PATH)) {
            return getModuleBootPath();
        } else if (type.equals(JavaClassPathConstants.MODULE_COMPILE_PATH)) {
            return getModuleCompilePath(file);
        } else if (type.equals(JavaClassPathConstants.MODULE_CLASS_PATH)) {
            return getModuleLegacyClassPath(file);
        } else if (type.equals(JavaClassPathConstants.MODULE_EXECUTE_PATH)) {
            return getModuleExecutePath(file);
        } else if (type.equals(JavaClassPathConstants.MODULE_EXECUTE_CLASS_PATH)) {
            return getModuleLegacyExecuteClassPath(file);
        } else {
            return null;
        }
    }
    
    /**
     * Returns array of all classpaths of the given type in the project.
     * The result is used for example for GlobalPathRegistry registrations.
     */
    public ClassPath[] getProjectClassPaths(final String type) {
        return ProjectManager.mutex().readAccess(new Mutex.Action<ClassPath[]>() {
            public ClassPath[] run() {
                if (ClassPath.BOOT.equals(type)) {
                    return new ClassPath[]{getBootClassPath()};
                }
                if (ClassPath.COMPILE.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getCompileTimeClasspath(0);
                    l[1] = getCompileTimeClasspath(1);
                    return l;
                }
                if (ClassPath.SOURCE.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getSourcepath(0);
                    l[1] = getSourcepath(1);
                    return l;
                }
                if (ClassPath.EXECUTE.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getRunTimeClasspath(0);
                    l[1] = getRunTimeClasspath(1);
                    return l;
                }
                if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getProcessorClasspath(0);
                    l[1] = getProcessorClasspath(1);
                    return l;
                }
                if (JavaClassPathConstants.MODULE_BOOT_PATH.equals(type)) {
                    return new ClassPath[] {getModuleBootPath()};
                }
                if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getModuleCompilePath(0);
                    l[1] = getModuleCompilePath(1);
                    return l;
                }
                if (JavaClassPathConstants.MODULE_CLASS_PATH.equals(type)) {
                    ClassPath[] l = new ClassPath[2];
                    l[0] = getModuleLegacyClassPath(0);
                    l[1] = getModuleLegacyClassPath(1);
                    return l;
                }
                assert false;
                return null;
            }});
    }

    /**
     * Returns the given type of the classpath for the project sources
     * (i.e., excluding tests roots).
     */
    public ClassPath getProjectSourcesClassPath(String type) {
        if (ClassPath.BOOT.equals(type)) {
            return getBootClassPath();
        }
        if (ClassPath.COMPILE.equals(type)) {
            return getCompileTimeClasspath(0);
        }
        if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
            return getProcessorClasspath(0);
        }
        if (ClassPath.SOURCE.equals(type)) {
            return getSourcepath(0);
        }
        if (ClassPath.EXECUTE.equals(type)) {
            return getRunTimeClasspath(0);
        }
        if (JavaClassPathConstants.MODULE_BOOT_PATH.equals(type)) {
            return getModuleBootPath();
        }
        if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
            return getModuleCompilePath(0);
        }
        if (JavaClassPathConstants.MODULE_CLASS_PATH.equals(type)) {
            return getModuleLegacyClassPath(0);
        }
        if (JavaClassPathConstants.MODULE_EXECUTE_PATH.equals(type)) {
            return getModuleExecutePath(0);
        }
        if (JavaClassPathConstants.MODULE_EXECUTE_CLASS_PATH.equals(type)) {
            return getModuleLegacyExecuteClassPath(0);
        }
        assert false : "Unknown classpath type: " + type;   //NOI18N
        return null;
    }

    public String[] getPropertyName (final SourceRoots roots, final String type) {
        if (ClassPathSupport.ENDORSED.equals(type)) {
            return endorsedClasspath;
        }
        if (roots.isTest()) {
            if (ClassPath.COMPILE.equals(type)) {
                return javacTestClasspath;
            }
            else if (ClassPath.EXECUTE.equals(type)) {
                return runTestClasspath;
            }
            else if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
                return processorTestClasspath;
            }
            else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                return testModulePath;
            }
            else {
                return null;
            }
        }
        else {
            if (ClassPath.COMPILE.equals(type)) {
                return javacClasspath;
            }
            else if (ClassPath.EXECUTE.equals(type)) {
                return runClasspath;
            }
            else if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
                return processorClasspath;
            }
            else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                return modulePath;
            }
            else {
                return null;
            }
        }
    }
    
    public String[] getPropertyName (SourceGroup sg, String type) {
        if (ClassPathSupport.ENDORSED.equals(type)) {
            return endorsedClasspath;
        }
        FileObject root = sg.getRootFolder();
        FileObject[] path = getPrimarySrcPath();
        for (int i=0; i<path.length; i++) {
            if (root.equals(path[i])) {
                if (ClassPath.COMPILE.equals(type)) {
                    return javacClasspath;
                }
                else if (ClassPath.EXECUTE.equals(type)) {
                    return runClasspath;
                }
                else if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
                    return processorClasspath;
                }
                else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                    return modulePath;
                }
                else {
                    return null;
                }
            }
        }
        path = getTestSrcDir();
        for (int i=0; i<path.length; i++) {
            if (root.equals(path[i])) {
                if (ClassPath.COMPILE.equals(type)) {
                    return javacTestClasspath;
                }
                else if (ClassPath.EXECUTE.equals(type)) {
                    return runTestClasspath;
                }
                else if (JavaClassPathConstants.PROCESSOR_PATH.equals(type)) {
                    return processorTestClasspath;
                }
                else if (JavaClassPathConstants.MODULE_COMPILE_PATH.equals(type)) {
                    return testModulePath;
                }
                else {
                    return null;
                }
            }
        }
        return null;
    }
    
    @NonNull
    private Function<URL,Boolean> getFilter(final int type) {
        switch (type) {
            case 0:
            case 2:
                return new Filter(buildClassesDir);
            case 1:
            case 3:
                return new Filter(buildTestClassesDir);
            case 4:
                return new Filter(distJar);
            default:
                throw new IllegalArgumentException(Integer.toString(type));
        }
    }

    @NonNull
    private org.netbeans.spi.java.classpath.support.ClassPathSupport.Selector createSourceLevelSelector(
            @NonNull final Supplier<? extends ClassPath> preJdk9,
            @NonNull final Supplier<? extends ClassPath> jdk9) {
        final List<Supplier<? extends ClassPath>> cps =
                new ArrayList<>(2);
        cps.add(preJdk9);
        cps.add(jdk9);
        return new SourceLevelSelector(
                evaluator,
                javacSource,
                cps);
    }

    private ClassPath computeIfAbsent(
            final int cacheIndex,
            final Supplier<ClassPath> provider) {
        synchronized (this) {
            ClassPath cp = cache[cacheIndex];
            if (cp != null) {
                return cp;
            }
        }
        return ProjectManager.mutex().readAccess(()-> {
            synchronized(this) {
                ClassPath cp = cache[cacheIndex];
                if (cp == null) {
                    cp = provider.get();
                    cache[cacheIndex] = cp;
                }
                return cp;
            }
        });
    }
    
    private class Filter implements Function<URL, Boolean>{        
        private final String includeProp;
        private final String excludeProp;
        
        Filter(@NullAllowed final String includeProp) {            
            this(includeProp, null);
        }
        
        Filter(
                @NullAllowed final String includeProp,
                @NullAllowed final String excludeProp) {
            this.includeProp = includeProp;
            this.excludeProp = excludeProp;            
        }

        @Override
        public Boolean apply(@NonNull final URL url) {
            final URL aurl = FileUtil.isArchiveArtifact(url) ?
                    FileUtil.getArchiveFile(url) :
                    url;
            if (Optional.ofNullable(includeProp)
                    .map((p) -> getDir(p))
                    .map((fo) -> Objects.equals(fo.toURL(),aurl))
                    .orElse(Boolean.FALSE)) {
                return Boolean.TRUE;
            }            
            if(Optional.ofNullable(excludeProp)
                    .map((p) -> getDir(p))
                    .map((fo) -> Objects.equals(fo.toURL(),aurl))
                    .orElse(Boolean.FALSE)) {
                return Boolean.FALSE;
            }
            return null;
        }
    }

    /*test*/ static final class SourceLevelSelector implements org.netbeans.spi.java.classpath.support.ClassPathSupport.Selector, PropertyChangeListener {

        private static final SpecificationVersion JDK9 = new SpecificationVersion("1.9");   //NOI18N

        private final PropertyEvaluator eval;
        private final String sourceLevelPropName;
        private final List<? extends Supplier<? extends ClassPath>> cpfs;
        private final PropertyChangeSupport listeners;
        private volatile ClassPath active;
        private volatile ClassPath[] cps;


        SourceLevelSelector(
                @NonNull final PropertyEvaluator eval,
                @NonNull final String sourceLevelPropName,
                @NonNull final List<? extends Supplier<? extends ClassPath>> cpFactories) {
            Parameters.notNull("eval", eval);   //NOI18N
            Parameters.notNull("sourceLevelPropName", sourceLevelPropName); //NOI18N
            Parameters.notNull("cpFactories", cpFactories); //NOI18N
            if (cpFactories.size() != 2) {
                throw new IllegalArgumentException("Invalid classpaths: " + cpFactories);  //NOI18N
            }
            for (Supplier<?> f : cpFactories) {
                if (f == null) {
                    throw new NullPointerException("Classpaths contain null: " + cpFactories);  //NOI18N
                }
            }
            this.eval = eval;
            this.sourceLevelPropName = sourceLevelPropName;
            this.cpfs = cpFactories;
            this.listeners = new PropertyChangeSupport(this);
            this.cps = new ClassPath[2];
            this.eval.addPropertyChangeListener(WeakListeners.propertyChange(this, this.eval));
        }

        @Override
        @NonNull
        public ClassPath getActiveClassPath() {
            ClassPath res = active;
            if (res == null) {
                int index = 0;
                final String sl = eval.getProperty(this.sourceLevelPropName);
                if (sl != null) {
                    try {
                        if (JDK9.compareTo(new SpecificationVersion(sl)) <= 0) {
                            index = 1;
                        }
                    } catch (NumberFormatException e) {
                        //pass
                    }
                }
                res = cps[index];   //RB
                if (res == null) {
                    res = cpfs.get(index).get();
                    if (res == null) {
                        throw new IllegalStateException(String.format(
                                "ClassPathFactory: %s returned null",   //NOI18N
                                cpfs.get(index)));
                    }
                    cps[index] = res;
                    cps = cps;  //WB
                }
                active = res;
            }
            return res;
        }

        @Override
        public void addPropertyChangeListener(@NonNull final PropertyChangeListener listener) {
            Parameters.notNull("listener", listener);   //NOI18N
            this.listeners.addPropertyChangeListener(listener);
        }

        @Override
        public void removePropertyChangeListener(@NonNull final PropertyChangeListener listener) {
            Parameters.notNull("listener", listener);   //NOI18N
            this.listeners.removePropertyChangeListener(listener);
        }

        @Override
        public void propertyChange(@NonNull final PropertyChangeEvent evt) {
            final String propName = evt.getPropertyName();
            if (propName == null || sourceLevelPropName.equals(propName)) {
                this.active = null;
                this.listeners.firePropertyChange(PROP_ACTIVE_CLASS_PATH, null, null);
            }
        }
    }
}
