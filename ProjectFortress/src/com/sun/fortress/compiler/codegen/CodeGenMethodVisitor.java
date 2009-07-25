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
package com.sun.fortress.compiler.codegen;

import com.sun.fortress.compiler.nativeInterface.SignatureParser;

// This class allows us to wrap MethodVisitor.visitMaxs Methods to
// dump bytecodes.  It is generally used with CodeGenClassWriter.

import java.util.*;

import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

import com.sun.fortress.useful.Debug;
import com.sun.fortress.compiler.NamingCzar;

public class CodeGenMethodVisitor extends TraceMethodVisitor {

    public HashMap<String, Integer> localVariableTable;
    public HashMap<String, String> localVariableTypeTable;
    public SignatureParser signatureParser;
    private int access;
    private String name;
    private String desc;
    private String signature;
    private String[] exceptions;
    private List<String> argumentTypes;
    private String resultType;
    int localVariableCount;

    public CodeGenMethodVisitor(int access, String name, String desc,
                                String signature, String[] exceptions,
                                MethodVisitor mvisitor) {
        super(mvisitor);
        this.access = access;
        this.name = name;
        this.desc = desc;
        this.signature = signature;
        this.exceptions = exceptions;
        this.argumentTypes = NamingCzar.parseArgs(desc);
        this.resultType = NamingCzar.parseResult(desc);

        this.localVariableTable = new HashMap<String, Integer>();
        this.localVariableTypeTable = new HashMap<String, String>();
        this.localVariableCount = 0;

        if ((access & Opcodes.ACC_STATIC) != Opcodes.ACC_STATIC) {
            createLocalVariable("instance", name);
        }

        Debug.debug(Debug.Type.CODEGEN, 1,
                    "MethodVisitor: name = " + name + " desc = " + desc +
                    " argumentTypes = " + argumentTypes + " resultType " + resultType);

        int i = 0;

        for (String argumentType : argumentTypes) {
            createLocalVariable("arg" + i++, argumentType);
        }

        createLocalVariable("result", resultType);

    }

    public void visitMaxs(int maxStack, int maxLocals) {
        dumpBytecodes();
        super.visitMaxs(maxStack, maxLocals);
    }

    public void dumpBytecodes() {
        Debug.debug(Debug.Type.CODEGEN, 1, getText());
    }

    public int createLocalVariable(String name) {
        int result = localVariableCount++;
        localVariableTable.put(name, new Integer(result));
        return result;
    }

    public int createLocalVariable(String name, String type) {
        int result = localVariableCount++;
        localVariableTable.put(name, new Integer(result));
        localVariableTypeTable.put(name, type);
        return result;
    }

    public int getLocalVariable(String name) {
        if (localVariableTable.containsKey(name)) {
            Debug.debug(Debug.Type.CODEGEN, 1,
                        "GetLocalVariable: " + name + "=" + localVariableTable.get(name).intValue());
            return localVariableTable.get(name).intValue();
        }
        else
            throw new RuntimeException("Trying to retrieve a non-existent local variable " + name);
    }

}