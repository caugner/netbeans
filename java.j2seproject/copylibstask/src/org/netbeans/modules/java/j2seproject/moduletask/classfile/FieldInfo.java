/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright (c) 2017 Oracle and/or its affiliates. All rights reserved.
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
 */
package org.netbeans.modules.java.j2seproject.moduletask.classfile;

import java.io.IOException;

/**
 *
 * @author Tomas Zezula
 */
public final class FieldInfo {
    private final int accessFlags;
    private final int nameIndex;
    private final int descriptorIndex;
    private final Attribute[] attributes;

    FieldInfo(final Reader in) throws IOException {
        this.accessFlags = in.readUnsignedShort();
        this.nameIndex = in.readUnsignedShort();
        this.descriptorIndex = in.readUnsignedShort();
        this.attributes = new Attribute[in.readUnsignedShort()];
        for (int i = 0; i < this.attributes.length; i++) {
            this.attributes[i] = new Attribute(in);
        }
    }

    void write(final Writer out) throws IOException {
        out.writeUnsignedShort(accessFlags);
        out.writeUnsignedShort(nameIndex);
        out.writeUnsignedShort(descriptorIndex);
        out.writeUnsignedShort(attributes.length);
        for (int i = 0; i < attributes.length; i++) {
            attributes[i].write(out);
        }
    }
}
