/*******************************************************************************
    Copyright 2008 Sun Microsystems, Inc.,
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
package com.sun.fortress.interpreter.evaluator.types;

import java.util.Collections;

import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.nodes.AbsDeclOrDecl;
import com.sun.fortress.nodes.AbstractNode;

public class SymbolicOprType extends SymbolicType {

    public SymbolicOprType(String name, Environment interior, AbstractNode decl) {
        super(name, interior, Collections.<AbsDeclOrDecl>emptyList(), decl);
        isSymbolic = true;
    }

}
