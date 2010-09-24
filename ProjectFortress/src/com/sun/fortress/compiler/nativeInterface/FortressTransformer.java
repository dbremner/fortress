/*******************************************************************************
    Copyright 2010 Sun Microsystems, Inc.,
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
import java.util.Map;
import java.util.Set;
import java.io.FileOutputStream;
import org.objectweb.asm.*;

import com.sun.fortress.compiler.codegen.CodeGenClassWriter;
import com.sun.fortress.compiler.index.Function;
import com.sun.fortress.compiler.OverloadSet;
import com.sun.fortress.scala_src.types.TypeAnalyzer;
import com.sun.fortress.exceptions.InterpreterBug;
import com.sun.fortress.nodes.APIName;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.repository.ProjectProperties;
import com.sun.fortress.runtimeSystem.ByteCodeWriter;
import com.sun.fortress.runtimeSystem.Naming;
import com.sun.fortress.useful.MultiMap;

public class FortressTransformer {
    static String repository = ProjectProperties.NATIVE_WRAPPER_CACHE_DIR + "/";


    @SuppressWarnings("unchecked")
    public static void transform(String inputClassName,
            APIName api_name,
            Map<IdOrOpOrAnonymousName,MultiMap<Integer, Function>> size_partitioned_overloads,
            TypeAnalyzer ta) {
        try {
            ClassReader cr = new ClassReader(inputClassName);
            CodeGenClassWriter cw = new CodeGenClassWriter(ClassWriter.COMPUTE_FRAMES,
                                                           (CodeGenClassWriter)null);
            String outputClassName = Naming.NATIVE_PREFIX_DOT + inputClassName;

            // Note that this will fail if it attempts to create a function that requires a
            // closure (eg a function whose type is a fortress generic).  That is because fortress
            // generics requrie a JarOutputStream to be provided to cw so that they have some way
            // to write out fresh classes for the closures.
            FortressMethodAdapter fa = new FortressMethodAdapter(cw,
                    inputClassName, outputClassName, api_name, size_partitioned_overloads, ta);
            cr.accept(fa, 0);

            // Note: we are not generating a jar, and did not provide
            // a jar file argument when we created cw.  Thus we need
            // to handle cleanup on our own, explicitly, rather than
            // using cw.dumpClass().  Trying to refactor this away was
            // ugly; we ended up with a dependency upon repository in
            // CodeGenClassWriter.  We accept this solution instead
            // for the time being.
            cw.visitEnd();
            byte[] b1 = cw.toByteArray();

            ByteCodeWriter.writeClass(repository, outputClassName, b1);

//            cr = new ClassReader(b1);
//            cw = new ClassWriter(ClassWriter.COMPUTE_FRAMES);
//
//            FortressForeignAdapter ffa = new FortressForeignAdapter(cw,
//                    outputClassName, overloads);
//            cr.accept(ffa, 0);
//            cw.visitEnd();
//            byte[] b2 = cw.toByteArray();

//            ByteCodeWriter.writeClass(repository, outputClassName, b2);
        } catch (Throwable e) {
            e.printStackTrace();
            InterpreterBug.bug(e);
        }
    }


}
