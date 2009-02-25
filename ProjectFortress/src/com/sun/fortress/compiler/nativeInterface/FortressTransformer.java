/*******************************************************************************
    Copyright 2009 Sun Microsystems, Inc.,
    4150 Network Circle, Santa Clara, California 95054, U.S.A.
    All rights reserved.

    U.S. Government Rights - Commercial software.
    Government users are subject to the Sun Microsystems, Inc. standard
    license agreement and applicable provisions of the FAR and its supplements.

    Use is subject to license terms.

    This distribution may include materials developed by third parties.

    Sun, Sun Microsystems, the Sun logo and Java are trademarks or registered
    trademarks of Sun Microsystems, Inc. in the U.S. and other countries.
******************************************************************************/
package com.sun.fortress.compiler.nativeInterface;

import java.lang.reflect.Method;
import java.io.FileOutputStream;
import org.objectweb.asm.*;
import com.sun.fortress.compiler.codegen.Compile;

public class FortressTransformer {
    @SuppressWarnings("unchecked")

        public static void transform(String inputClassName) {

        String outputClassName;
        // If we are reading in a java.* class, we need to write it out as a
        // com.sun.fortress.java.* class.  If it is already a com.sun.fortress.* class
        // then we leave it alone.
//         if (inputClassName.startsWith("java"))
//             outputClassName = "com.sun.fortress." + inputClassName;
//         else outputClassName = inputClassName;

        outputClassName = inputClassName;
        try {
            ClassReader cr = new ClassReader(inputClassName);
            ClassWriter cw = new ClassWriter(1);
            FortressMethodAdapter fa = new FortressMethodAdapter(cw, outputClassName);
            cr.accept(fa, 0);
            byte[] b2 = cw.toByteArray();
            Compile compile = new Compile(outputClassName);
            compile.writeClass(b2);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

}
