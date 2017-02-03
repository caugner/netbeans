/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 2015 Oracle and/or its affiliates. All rights reserved.
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
 * Portions Copyrighted 2015 Sun Microsystems, Inc.
 */
package org.netbeans.modules.java.source.parsing;

import com.sun.tools.javac.code.Source;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import javax.tools.FileObject;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import org.netbeans.api.annotations.common.CheckForNull;
import org.netbeans.api.annotations.common.NonNull;
import org.netbeans.api.annotations.common.NullAllowed;
import org.netbeans.api.annotations.common.NullUnknown;
import org.netbeans.api.java.classpath.ClassPath;
import org.netbeans.api.java.source.SourceUtils;
import org.netbeans.modules.java.source.util.Iterators;
import org.openide.filesystems.FileUtil;
import org.openide.util.Exceptions;
import org.openide.util.Pair;

/**
 *
 * @author Tomas Zezula
 */
final class ModuleFileManager implements JavaFileManager {

    private static final Logger LOG = Logger.getLogger(ModuleFileManager.class.getName());
    private static final Pattern MATCHER_PATCH =
                Pattern.compile("(.+)=(.+)");  //NOI18N

    private final CachingArchiveProvider cap;
    private final ClassPath modulePath;
    private final Function<URL,Collection<? extends URL>> peers;
    private final Source sourceLevel;
    private final boolean cacheFile;
    private final Map<String,List<URL>> patches;
    private Set<ModuleLocation> moduleLocations;
    private Location forLocation;


    public ModuleFileManager(
            @NonNull final CachingArchiveProvider cap,
            @NonNull final ClassPath modulePath,
            @NonNull final Function<URL,Collection<? extends URL>> peers,
            @NullAllowed final Source sourceLevel,
            final boolean cacheFile) {
        assert cap != null;
        assert modulePath != null;
        assert peers != null;
        this.cap = cap;
        this.modulePath = modulePath;
        this.peers = peers;
        this.sourceLevel = sourceLevel;
        this.cacheFile = cacheFile;
        this.patches = new HashMap<>();
    }

    // FileManager implementation ----------------------------------------------

    @Override
    public Iterable<JavaFileObject> list(
            @NonNull final Location l,
            @NonNull final String packageName,
            @NonNull final Set<JavaFileObject.Kind> kinds,
            final boolean recursive ) {
        final ModuleLocation ml = asModuleLocation(l);
        final String folderName = FileObjects.convertPackage2Folder(packageName);
        try {
            final List<Iterable<JavaFileObject>> res = new ArrayList<>();
            List<? extends String> prefixes = null;
            final boolean supportsMultiRelease = sourceLevel != null && sourceLevel.compareTo(Source.JDK1_9) >= 0;
            for (URL root : ml.getModuleRoots()) {
                final Archive archive = cap.getArchive(root, cacheFile);
                if (archive != null) {
                    final Iterable<JavaFileObject> entries;
                    if (supportsMultiRelease && archive.isMultiRelease()) {
                        if (prefixes == null) {
                            prefixes = multiReleaseRelocations();
                        }
                        final java.util.Map<String,JavaFileObject> fqn2f = new HashMap<>();
                        final Set<String> seenPackages = new HashSet<>();
                        for (String prefix : prefixes) {
                            Iterable<JavaFileObject> fos = archive.getFiles(
                                    join(prefix, folderName),
                                    null,
                                    kinds,
                                    null,
                                    recursive);
                            for (JavaFileObject fo : fos) {
                                final boolean base = prefix.isEmpty();
                                if (!base) {
                                    fo = new MultiReleaseJarFileObject((InferableJavaFileObject)fo, prefix);    //Always inferable in this branch
                                }
                                final String fqn = inferBinaryName(l, fo);
                                final String pkg = FileObjects.getPackageAndName(fqn)[0];
                                if (base) {
                                    seenPackages.add(pkg);
                                    fqn2f.put(fqn, fo);
                                } else if (seenPackages.contains(pkg)) {
                                    fqn2f.put(fqn, fo);
                                }
                            }
                        }
                        entries = fqn2f.values();
                    } else {
                        entries = archive.getFiles(folderName, null, kinds, null, recursive);
                    }
                    res.add(entries);
                    if (LOG.isLoggable(Level.FINEST)) {
                        logListedFiles(l,packageName, kinds, entries);
                    }
                } else if (LOG.isLoggable(Level.FINEST)) {
                    LOG.log(
                        Level.FINEST,
                        "No archive for: {0}",               //NOI18N
                        ml.getModuleRoots());
                }
            }
            return Iterators.chained(res);
        } catch (final IOException e) {
            Exceptions.printStackTrace(e);
        }
        return Collections.emptySet();
    }

    @Override
    public FileObject getFileForInput(
            @NonNull final Location l,
            @NonNull final String pkgName,
            @NonNull final String relativeName ) {
        return findFile(asModuleLocation(l), pkgName, relativeName);
    }

    @Override
    public JavaFileObject getJavaFileForInput (
            @NonNull final Location l,
            @NonNull final String className,
            @NonNull final JavaFileObject.Kind kind) {
        final ModuleLocation ml = asModuleLocation(l);
        final String[] namePair = FileObjects.getParentRelativePathAndName(className);
        final boolean supportsMultiRelease = sourceLevel != null && sourceLevel.compareTo(Source.JDK1_9)>= 0;
        List<? extends String> reloc = null;
        for (URL root : ml.getModuleRoots()) {
            try {
                final Archive  archive = cap.getArchive (root, cacheFile);
                if (archive != null) {
                    final List<? extends String> prefixes;
                    if (supportsMultiRelease && archive.isMultiRelease()) {
                        if (reloc == null) {
                            reloc = multiReleaseRelocations();
                        }
                        prefixes = reloc;
                    } else {
                        prefixes = Collections.singletonList("");   //NOI18N
                    }
                    for (int i = prefixes.size() - 1; i >=0; i--) {
                        final String prefix = prefixes.get(i);
                        Iterable<JavaFileObject> files = archive.getFiles(
                                join(prefix,namePair[0]),
                                null,
                                null,
                                null,
                                false);
                        for (JavaFileObject e : files) {
                            final String ename = e.getName();
                            if (namePair[1].equals(FileObjects.stripExtension(ename)) &&
                                kind == FileObjects.getKind(FileObjects.getExtension(ename))) {
                                return prefix.isEmpty() ?
                                        e :
                                        new MultiReleaseJarFileObject((InferableJavaFileObject)e, prefix);  //Always inferable
                            }
                        }
                    }
                }
            } catch (IOException e) {
                Exceptions.printStackTrace(e);
            }
        }
        return null;
    }


    @Override
    public FileObject getFileForOutput(
            @NonNull final Location l,
            @NonNull final String pkgName,
            @NonNull final String relativeName,
            @NullAllowed final FileObject sibling ) throws IOException {
        throw new UnsupportedOperationException("Output is unsupported.");  //NOI18N
    }

    @Override
    public JavaFileObject getJavaFileForOutput( Location l, String className, JavaFileObject.Kind kind, FileObject sibling )
        throws IOException, UnsupportedOperationException, IllegalArgumentException {
        throw new UnsupportedOperationException ("Output is unsupported.");
    }

    @Override
    public void flush() throws IOException {
    }

    @Override
    public void close() throws IOException {
    }

    @Override
    public int isSupportedOption(String string) {
        return -1;
    }

    @Override
    public boolean handleOption (final String head, final Iterator<String> tail) {
        if (JavacParser.OPTION_PATCH_MODULE.equals(head)) {
            final Pair<String,List<URL>> modulePatches = parseModulePatches(tail);
            if (modulePatches != null) {
                if (patches.putIfAbsent(modulePatches.first(), modulePatches.second()) != null) {
                    //Don't abort compilation by Abort
                    //Log error into javac Logger doe not help - no source to attach to.
                    LOG.log(
                            Level.WARNING,
                            "Duplicate " +JavacParser.OPTION_PATCH_MODULE+ " option, ignoring: {0}",    //NOI18N
                            modulePatches.second());
                }
                return true;
            }            
        }
        return false;
    }

    @Override
    public boolean hasLocation(Location location) {
        return true;
    }

    @Override
    public ClassLoader getClassLoader (final Location l) {
        return null;
    }

    @Override
    public String inferBinaryName (Location l, JavaFileObject javaFileObject) {
        if (javaFileObject instanceof InferableJavaFileObject) {
            return ((InferableJavaFileObject)javaFileObject).inferBinaryName();
        }
        return null;
    }

    @Override
    public boolean isSameFile(FileObject a, FileObject b) {
        return a instanceof FileObjects.FileBase
               && b instanceof FileObjects.FileBase
               && ((FileObjects.FileBase)a).getFile().equals(((FileObjects.FileBase)b).getFile());
    }

    @Override
    @NonNull
    public Iterable<Set<Location>> listLocationsForModules(Location location) throws IOException {
        return moduleLocations(location).stream()
                .map((ml) -> Collections.<Location>singleton(ml))
                .collect(Collectors.toList());
    }

    @Override
    @NullUnknown
    public String inferModuleName(@NonNull final Location location) throws IOException {
        final ModuleLocation ml = asModuleLocation(location);
        return ml.getModuleName();
    }

    @Override
    @CheckForNull
    public Location getLocationForModule(Location location, JavaFileObject fo, String pkgName) throws IOException {
        //todo: Only for Source Module Path & Output Path
        return null;
    }

    @Override
    @CheckForNull
    public Location getLocationForModule(Location location, String moduleName) throws IOException {
        return moduleLocations(location).stream()
                .filter((ml) -> moduleName != null && moduleName.equals(ml.getModuleName()))
                .findFirst()
                .orElse(null);
    }

    private Set<ModuleLocation> moduleLocations(final Location baseLocation) {
        if (moduleLocations == null) {
            final Set<ModuleLocation> moduleRoots = new HashSet<>();
            final Set<URL> seen = new HashSet<>();
            for (ClassPath.Entry e : modulePath.entries()) {
                final URL root = e.getURL();
                if (!seen.contains(root)) {
                    final String moduleName = SourceUtils.getModuleName(root);
                    if (moduleName != null) {
                        Collection<? extends URL> p = peers.apply(root);
                        final List<? extends URL> x = patches.get(moduleName);
                        if (x != null) {
                            final List<URL> tmp = new ArrayList(x.size() + p.size());
                            tmp.addAll(x);
                            tmp.addAll(p);
                            p = tmp;
                        }
                        moduleRoots.add(ModuleLocation.create(baseLocation, p, moduleName));
                        seen.addAll(p);
                    }
                }
            }
            moduleLocations = moduleRoots;
            forLocation = baseLocation;
        } else if (!forLocation.equals(baseLocation)) {
                throw new IllegalStateException(String.format(
                        "Locations computed for: %s, but queried for: %s",  //NOI18N
                        forLocation,
                        baseLocation));
        }
        return moduleLocations;
    }

    private JavaFileObject findFile(
            @NonNull final ModuleLocation ml,
            @NonNull final String pkgName,
            @NonNull final String relativeName) {
        assert ml != null;
        assert pkgName != null;
        assert relativeName != null;
        final String resourceName = FileObjects.resolveRelativePath(pkgName,relativeName);
        final boolean supportsMultiRelease = sourceLevel != null && sourceLevel.compareTo(Source.JDK1_9) >= 0;
        List<? extends String> reloc = null;
        for (URL root : ml.getModuleRoots()) {
            try {
                final Archive  archive = cap.getArchive (root, cacheFile);
                if (archive != null) {
                    final List<? extends String> prefixes;
                    if (supportsMultiRelease && archive.isMultiRelease()) {
                        if (reloc == null) {
                            reloc = multiReleaseRelocations();
                        }
                        prefixes = reloc;
                    } else {
                        prefixes = Collections.singletonList("");   //NOI18N
                    }
                    for (int i = prefixes.size() - 1; i >= 0; i--) {
                        final String prefix = prefixes.get(i);
                        final JavaFileObject file = archive.getFile(join(prefix, resourceName));
                        if (file != null) {
                            return prefix.isEmpty() ?
                                    file :
                                    new MultiReleaseJarFileObject((InferableJavaFileObject)file, prefix);   //Always inferable
                        }
                    }
                }
            } catch (IOException e) {
                Exceptions.printStackTrace(e);
            }
        }
        return null;
    }

    @NonNull
    private static ModuleLocation asModuleLocation (@NonNull final Location l) {
        if (l.getClass() != ModuleLocation.class) {
            throw new IllegalArgumentException (String.valueOf(l));
        }
        return (ModuleLocation) l;
    }
    
    @CheckForNull
    private static Pair<String,List<URL>> parseModulePatches(@NonNull final Iterator<? extends String> tail) {
        if (tail.hasNext()) {
            //<module>=<file>(:<file>)*
            final Matcher m = MATCHER_PATCH.matcher(tail.next());
            if (m.matches() && m.groupCount() == 2) {
                final String module = m.group(1);
                final List<URL> patches = Arrays.stream(m.group(2).split(File.pathSeparator))
                        .map((p) -> FileUtil.normalizeFile(new File(p)))
                        .map(FileUtil::urlForArchiveOrDir)
                        .collect(Collectors.toList());
                return Pair.of(module, patches);
            }
        }        
        return null;
    }

    private static void logListedFiles(
            @NonNull final Location l,
            @NonNull final String packageName,
            @NullAllowed final Set<? extends JavaFileObject.Kind> kinds,
            @NonNull final Iterable<? extends JavaFileObject> entries) {
        final StringBuilder urls = new StringBuilder ();
        for (JavaFileObject jfo : entries) {
            urls.append(jfo.toUri().toString());
            urls.append(", ");  //NOI18N
        }
        LOG.log(
            Level.FINEST,
            "Filesfor {0} package: {1} type: {2} files: [{3}]",   //NOI18N
            new Object[] {
                l,
                packageName,
                kinds,
                urls
            });
    }

    @NonNull
    private List<? extends String> multiReleaseRelocations() {
        final List<String> prefixes = new ArrayList<>();
        prefixes.add("");   //NOI18N
        final Source[] sources = Source.values();
        for (int i=0; i< sources.length; i++) {
            if (sources[i].compareTo(Source.JDK1_9) >=0 && sources[i].compareTo(sourceLevel) <=0) {
                prefixes.add(String.format(
                        "META-INF/versions/%s",    //NOI18N
                        normalizeSourceLevel(sources[i].name)));
            }
        }
        return prefixes;
    }

    @NonNull
    private static String normalizeSourceLevel(@NonNull final String sl) {
        final int index = sl.indexOf('.');  //NOI18N
        return index < 0 ?
                sl :
                sl.substring(index+1);
    }

    @NonNull
    private static String join(
            @NonNull final String prefix,
            @NonNull final String path) {
        return prefix.isEmpty() ?
            path:
            path.isEmpty() ?
                prefix :
                prefix + FileObjects.NBFS_SEPARATOR_CHAR + path;
    }
}
