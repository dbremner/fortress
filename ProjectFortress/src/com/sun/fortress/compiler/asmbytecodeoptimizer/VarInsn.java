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
package com.sun.fortress.compiler.asmbytecodeoptimizer;

import org.objectweb.asm.*;
import org.objectweb.asm.util.*;

public class VarInsn extends Insn {
    int opcode;
    int var;

    VarInsn(String name, int opcode, int var) {
        this.name = name;
        this.opcode = opcode;
        this.var = var;
    }
    public String toString() { 
        return "VarInsn:" +  name;
    }
    
    public void toAsm(MethodVisitor mv) { 
        mv.visitVarInsn(opcode, var);
    }
}
