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

package com.sun.fortress.compiler.index;

import com.sun.fortress.nodes.FnDecl;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.LValue;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.nodes_util.NodeUtil;

import java.util.List;

/**
 * Comprises DeclaredMethod, FieldGetterMethod, and FieldSetterMethod.
 */
public abstract class Method extends Functional implements HasSelfType {
    public abstract Node ast();
    public abstract Method originalMethod();
    
    /**
     * Returns a version of this Functional, with params replaced with args.
     * The contract of this method requires
     * that all implementing subtypes must return their own type, rather than a supertype.
     */
    public abstract Method instantiate(List<StaticParam> params, List<StaticArg> args);

    public Modifiers mods() {
        Node _ast = ast();
        if (_ast instanceof FnDecl)
            return NodeUtil.getMods((FnDecl)ast());
        else if (_ast instanceof LValue)
            return NodeUtil.getMods((LValue)ast());
        else
            return Modifiers.None;
    }

    @Override
    public String toString() {
        String receiver;
        if (selfType().isSome()) {
            receiver = selfType().unwrap().toString();
        } else {
            receiver = declaringTrait().getText();
        }
        return String.format("%s.%s", receiver, super.toString());
    }

    public int selfPosition() {
        return -1;
    }
}
