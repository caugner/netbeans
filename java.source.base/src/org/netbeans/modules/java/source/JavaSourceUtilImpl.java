/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2010 Oracle and/or its affiliates. All rights reserved.
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
 * 
 * Contributor(s):
 * 
 * Portions Copyrighted 2008 Sun Microsystems, Inc.
 */

package org.netbeans.modules.java.source;


import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.ModuleTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import com.sun.tools.javac.code.ClassFinder;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.code.Symtab;
import com.sun.tools.javac.util.Name;
import com.sun.tools.javac.api.JavacTaskImpl;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;
import javax.lang.model.element.ModuleElement;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.StandardLocation;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.annotations.common.NullAllowed;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.classpath.JavaClassPathConstants;
import org.netbeans.api.java.platform.JavaPlatform;
import org.netbeans.api.java.queries.SourceLevelQuery;
import org.netbeans.api.java.source.ClasspathInfo;
import org.netbeans.api.java.source.CompilationController;
import org.netbeans.api.java.source.JavaSource;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.modules.java.source.classpath.AptSourcePath;
import org.netbeans.modules.java.source.classpath.CacheClassPath;
import org.netbeans.modules.java.source.indexing.APTUtils;
import org.netbeans.modules.java.source.indexing.TransactionContext;
import org.netbeans.modules.java.source.parsing.CachingArchiveProvider;
import org.netbeans.modules.java.source.parsing.CachingFileManager;
import org.netbeans.modules.java.source.parsing.FileManagerTransaction;
import org.netbeans.modules.java.source.parsing.FileObjects;
import org.netbeans.modules.java.source.parsing.InferableJavaFileObject;
import org.netbeans.modules.java.source.parsing.JavacParser;
import org.netbeans.modules.java.source.parsing.ProcessorGenerated;
import org.netbeans.modules.java.source.usages.ClasspathInfoAccessor;
import org.netbeans.modules.parsing.api.Source;
import org.netbeans.modules.parsing.spi.ParseException;
import org.netbeans.spi.java.classpath.ClassPathFactory;
import org.netbeans.spi.java.classpath.ClassPathImplementation;
import org.netbeans.spi.java.classpath.support.ClassPathSupport;
import org.openide.filesystems.FileObject;
import org.openide.filesystems.FileUtil;
import org.openide.util.Parameters;

/**
 *
 * @author Tomas Zezula
 */
@org.openide.util.lookup.ServiceProvider(service=org.netbeans.modules.java.preprocessorbridge.spi.JavaSourceUtilImpl.class)

public final class JavaSourceUtilImpl extends org.netbeans.modules.java.preprocessorbridge.spi.JavaSourceUtilImpl {
    private static final Logger LOGGER = Logger.getLogger(JavaSourceUtilImpl.class.getName());

    @Override
    protected long createTaggedCompilationController(FileObject file, long currenTag, Object[] out) throws IOException {
        assert file != null;
        final JavaSource js = JavaSource.forFileObject(file);
        if (js == null) {
            throw new FileNotFoundException(
                    String.format("No java source for %s, exists: %b, file: %b",    //NOI18N
                        FileUtil.getFileDisplayName(file),
                        file.isValid(),
                        file.isData()
                    ));
        }
        return JavaSourceAccessor.getINSTANCE().createTaggedCompilationController(js, currenTag, out);
    }

    @Override
    @NonNull
    protected Map<String, byte[]> generate(
            @NonNull final FileObject srcRoot,
            @NonNull final FileObject file,
            @NullAllowed CharSequence content,
            @NullAllowed final DiagnosticListener<? super JavaFileObject> diagnostics) throws IOException {
        Parameters.notNull("srcRoot", srcRoot); //NOI18N
        Parameters.notNull("file", file);   //NOI18N        
        final String path = FileUtil.getRelativePath(srcRoot, file);
        if (path == null) {
            throw new IllegalArgumentException(String.format(
                    "File: %s not in root: %s", //NOI18N
                    file,
                    srcRoot));
        }        
        final String[] ncs = FileObjects.getPackageAndName(
            FileObjects.convertFolder2Package(FileObjects.stripExtension(path)));        
        final JavaFileObject toCompile = FileObjects.memoryFileObject(
                ncs[0],
                ncs[1]+'.'+file.getExt(),
                content);
        boolean success = false;
        final TransactionContext ctx = TransactionContext.beginTrans()
                .register(FileManagerTransaction.class, FileManagerTransaction.writeThrough())
                .register(ProcessorGenerated.class, ProcessorGenerated.create(srcRoot.toURL()));
        try {
            ClassPath src = ClassPath.getClassPath(srcRoot, ClassPath.SOURCE);
            if (src == null) {
                src = ClassPathSupport.createClassPath(srcRoot);
            }
            ClassPath boot = ClassPath.getClassPath(srcRoot, ClassPath.BOOT);
            if (boot == null) {
                boot = JavaPlatform.getDefault().getBootstrapLibraries();
            }   
            ClassPath moduleBoot = ClassPath.getClassPath(srcRoot, JavaClassPathConstants.MODULE_BOOT_PATH);
            if (moduleBoot == null) {
                moduleBoot = ClassPath.EMPTY;
            }
            ClassPath compile = ClassPath.getClassPath(srcRoot, ClassPath.COMPILE);
            if (compile == null) {
                compile = ClassPath.EMPTY;
            }
            ClassPath moduleCompile = ClassPath.getClassPath(srcRoot, JavaClassPathConstants.MODULE_COMPILE_PATH);
            if (moduleCompile == null) {
                moduleCompile = ClassPath.EMPTY;
            }
            ClassPath moduleClass = ClassPath.getClassPath(srcRoot, JavaClassPathConstants.MODULE_CLASS_PATH);
            if (moduleClass == null) {
                moduleClass = ClassPath.EMPTY;
            }
            final ClassPath srcFin = src;
            final SourceLevelQuery.Result r = SourceLevelQuery.getSourceLevel2(file);
            final com.sun.tools.javac.code.Source sourceLevel = JavacParser.validateSourceLevel(
                    r.getSourceLevel(),
                    boot,
                    compile,
                    src,
                    FileObjects.MODULE_INFO.equals(file.getName()));
            final Function<JavaFileManager.Location,JavaFileManager> jfmProvider =
                    (loc) -> {
                        return loc == StandardLocation.CLASS_OUTPUT ?
                                new OutputFileManager(
                                        CachingArchiveProvider.getDefault(),
                                        srcFin,
                                        sourceLevel) :
                                null;
                    };
            
            final ClasspathInfo cpInfo = ClasspathInfoAccessor.getINSTANCE().create(
                    boot,
                    moduleBoot,
                    compile,
                    moduleCompile,
                    moduleClass,
                    src,
                    null,
                    true,
                    true,
                    false,
                    false,
                    jfmProvider);
            final APTUtils aptUtils = APTUtils.get(srcRoot);
            final JavacTaskImpl  jt = JavacParser.createJavacTask(
                    cpInfo,
                    diagnostics != null ?
                            diagnostics :
                            new Diags(),
                    r.getSourceLevel(),
                    r.getProfile(),
                    null,
                    null,
                    null,
                    aptUtils,
                    null);
            final Iterable<? extends JavaFileObject> generated = jt.generate(
                    StreamSupport.stream(jt.analyze(jt.enter(jt.parse(toCompile))).spliterator(), false)
                            .filter((e) -> e.getKind().isClass() || e.getKind().isInterface())
                            .map((e) -> (TypeElement)e)
                            .collect(Collectors.toList()));
            final Map<String,byte[]> result = new HashMap<>();
            for (JavaFileObject jfo : generated) {
                if (jfo instanceof OutputFileManager.MemOutFileObject) {
                    final OutputFileManager.MemOutFileObject mout = (OutputFileManager.MemOutFileObject) jfo;
                    result.put(mout.inferBinaryName(), mout.toByteArray());
                } else {
                    throw new IOException(String.format(
                            "Unexpected JavaFileObject: %s",  //NOI18N
                            jfo));
                }
            }
            success = true;
            return Collections.unmodifiableMap(result);
        } finally {
            if (success) {
                ctx.commit();
            } else {
                ctx.rollBack();
            }
        }
    }

    @Override
    @CheckForNull
    protected ModuleInfoHandle getModuleInfoHandle(@NonNull final Object javaSource) throws IOException {
        if (!(javaSource instanceof JavaSource)) {
            throw new IllegalArgumentException("The javaSource parameter must be an instance of JavaSource"); //NOI18N
        }
        final Collection<FileObject> fileObjects = ((JavaSource) javaSource).getFileObjects();
        int size = fileObjects.size();
        if (size > 1) {
            throw new IllegalArgumentException("The javaSource parameter cannot represent multiple FileObjects"); //NOI18N
        }        
        final Source s = size > 0 ? Source.create(fileObjects.iterator().next()) : null;
        try {
            final CompilationController cc = JavaSourceAccessor.getINSTANCE().createCompilationController(s, ((JavaSource) javaSource).getClasspathInfo());
            if (cc != null) {
                return new ModuleInfoHandle() {
                    @Override
                    @CheckForNull
                    public String parseModuleName() throws IOException {
                        cc.toPhase(JavaSource.Phase.PARSED);
                        final CompilationUnitTree cu = cc.getCompilationUnit();
                        for (Tree decl : cu.getTypeDecls()) {
                            if (decl.getKind() == Tree.Kind.MODULE) {
                                return ((ModuleTree) decl).getName().toString();
                            }
                        }
                        return null;
                    }
                    
                    @Override
                    @CheckForNull
                    public ModuleElement parseModule() throws IOException {
                        cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                        final Trees trees = cc.getTrees();
                        final TreePathScanner<ModuleElement, Void> scanner = new TreePathScanner<ModuleElement, Void>() {
                            @Override
                            public ModuleElement visitModule(ModuleTree node, Void p) {
                                return (ModuleElement) trees.getElement(getCurrentPath());
                            }
                        };
                        return scanner.scan(new TreePath(cc.getCompilationUnit()), null);
                    }
                    
                    @Override
                    @CheckForNull
                    public ModuleElement resolveModule(String moduleName) throws IOException {
                        cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                        return cc.getElements().getModuleElement(moduleName);
                    }

                    @Override
                    public TypeElement readClassFile() throws IOException {
                        cc.toPhase(JavaSource.Phase.ELEMENTS_RESOLVED);
                        final JavacTaskImpl jt = JavaSourceAccessor.getINSTANCE()
                                .getCompilationInfoImpl(cc).getJavacTask();
                        final ClassFinder finder = ClassFinder.instance(jt.getContext());
                        final Symtab syms = Symtab.instance(jt.getContext());
                        final Symbol.ClassSymbol sym;
                        if (FileObjects.MODULE_INFO.equals(cc.getFileObject().getName())) {
                            final String moduleName = SourceUtils.getModuleName(cc.getFileObject().getParent().toURL());
                            if (moduleName != null) {
                                final Symbol.ModuleSymbol msym = syms.enterModule((Name)cc.getElements().getName(moduleName));
                                sym = msym.module_info;
                                if (sym.classfile == null) {
                                    sym.classfile = FileObjects.fileObjectFileObject(cc.getFileObject(), cc.getFileObject().getParent(), null, null);
                                    sym.owner = msym;
                                    msym.owner = syms.noSymbol;
                                    sym.completer = finder.getCompleter();
                                    msym.classLocation = StandardLocation.CLASS_PATH;
                                }
                                msym.complete();
                            } else {
                                sym = null;
                            }
                        } else {
                            throw new UnsupportedOperationException("Not supported yet.");  //NOI18N
                        }
                        return sym;
                    }
                };
            }
            return null;            
        } catch (ParseException pe) {
            throw new IOException(pe);
        }
    }

    private static final class Diags implements DiagnosticListener<JavaFileObject> {
        @Override
        public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
            LOGGER.log(
                    Level.FINE,
                    "{0}",  //NOI18N
                    diagnostic);
        }        
    }
    
    private static final class OutputFileManager implements JavaFileManager {
        private final JavaFileManager readDelegate;

        OutputFileManager(
                @NonNull final CachingArchiveProvider cap,
                @NonNull final ClassPath srcPath,
                @NullAllowed final com.sun.tools.javac.code.Source sourceLevel) {
            final ClassPathImplementation srcNoApt = AptSourcePath.sources(srcPath);
            final ClassPath out = CacheClassPath.forSourcePath (
                    ClassPathFactory.createClassPath(srcNoApt),
                    true);
            readDelegate = new CachingFileManager(cap, out, sourceLevel, false, true);
        }

        @Override
        public Iterable<JavaFileObject> list(Location location, String packageName, Set<JavaFileObject.Kind> kinds, boolean recurse) throws IOException {
            assertLocation(location, false);
            return readDelegate.list(location, packageName, kinds, recurse);
        }

        @Override
        public JavaFileObject getJavaFileForInput(Location location, String className, JavaFileObject.Kind kind) throws IOException {
            assertLocation(location, false);
            return readDelegate.getJavaFileForInput(location, className, kind);
        }
        
        @Override
        public javax.tools.FileObject getFileForInput(Location location, String packageName, String relativeName) throws IOException {
            assertLocation(location, false);
            return readDelegate.getFileForInput(location, packageName, relativeName);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(Location location, String className, JavaFileObject.Kind kind, javax.tools.FileObject sibling) throws IOException {
            assertLocation(location, true);
            final String[] ncs = FileObjects.getPackageAndName(className);
            return new MemOutFileObject(
                    ncs[0],
                    String.format("%s%s", ncs[1], kind.extension));
        }

        @Override
        public javax.tools.FileObject getFileForOutput(Location location, String packageName, String relativeName, javax.tools.FileObject sibling) throws IOException {
            assertLocation(location, true);
            final String resourceName = FileObjects.resolveRelativePath(packageName, relativeName);
            final String[] ncs = FileObjects.getFolderAndBaseName(resourceName, FileObjects.NBFS_SEPARATOR_CHAR);
            return new MemOutFileObject(
                    FileObjects.convertFolder2Package(ncs[0]),
                    ncs[1]);
        }
        
        @Override
        public boolean isSameFile(javax.tools.FileObject a, javax.tools.FileObject b) {
            if (a == b) {
                return true;
            }
            return a.toUri().equals(b.toUri());
        }
        
        @Override
        public String inferBinaryName(Location location, JavaFileObject file) {
            if (hasLocation(location)) {
                return ((InferableJavaFileObject)file).inferBinaryName();
            }
            return null;
        }

        @Override
        public void flush() throws IOException {
        }

        @Override
        public void close() throws IOException {
        }
        
        @Override
        public boolean hasLocation(Location location) {
            return location == StandardLocation.CLASS_OUTPUT;
        }

        @Override
        public int isSupportedOption(String string) {
            return -1;
        }

        @Override
        public boolean handleOption (final String head, final Iterator<String> tail) {
            return false;
        }
        
        @Override
        public ClassLoader getClassLoader(Location location) {
            assertLocation(location, false);
            return null;
        }
        
        private static void assertLocation(
                Location l,
                boolean write) { 
            if (l instanceof StandardLocation)  {
                switch ((StandardLocation)l) {
                    case CLASS_OUTPUT:
                        return;
                    case CLASS_PATH:
                        if (!write) {
                            return;
                        }
                }
            }
            throw new IllegalStateException(String.valueOf(l));
        }
        
        private static final class MemOutFileObject extends FileObjects.Base {            
            private final ByteArrayOutputStream out;
            private long modified;
            
            MemOutFileObject(
                    @NonNull final String pkg,
                    @NonNull final String name) {
                super(pkg, name, null, true);
                modified = -1;
                out = new ByteArrayOutputStream();
            }

            @Override
            public URI toUri() {
                return URI.create(String.format(
                        "%s/%s",    //NOI18N
                        FileObjects.convertPackage2Folder(getPackage()),
                        getName()));
            }

            @Override
            public InputStream openInputStream() throws IOException {
                return new ByteArrayInputStream(out.toByteArray());
            }

            @Override
            public OutputStream openOutputStream() throws IOException {
                modified = System.currentTimeMillis();
                out.reset();
                return out;
            }

            @Override
            public CharSequence getCharContent(boolean ignoreEncodingErrors) throws IOException {
                return encoding == null ?
                        new String(out.toByteArray()) :
                        new String(out.toByteArray(), encoding);
            }

            @Override
            public long getLastModified() {
                return modified;
            }

            @Override
            public boolean delete() {
                out.reset();
                return true;
            }
            
            byte[] toByteArray() {
                return out.toByteArray();
            }
        }
    }
}
