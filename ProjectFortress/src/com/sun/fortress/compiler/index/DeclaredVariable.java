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

package com.sun.fortress.compiler.index;

import com.sun.fortress.nodes.LValue;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.VarDecl;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import edu.rice.cs.plt.lambda.SimpleBox;
import edu.rice.cs.plt.lambda.Thunk;
import edu.rice.cs.plt.tuple.Option;

public class DeclaredVariable extends Variable {

    protected final LValue _lvalue;
    protected int _position;

    public DeclaredVariable(LValue lvalue, VarDecl decl) {
        _lvalue = lvalue;

        // Figure out my position in the decl.
        int i = 0;
        for (LValue lv : decl.getLhs()) {
            // lvalue came from decl in the first place, so referential equality
            // works just fine.
            if (lv == lvalue) {
                _position = i;
                break;
            }
            ++i;
        }

        Option<Type> idType = _lvalue.getIdType();
        if (idType.isSome()) {
            _thunk = Option.<Thunk<Option<Type>>>some(SimpleBox.make(idType));
        }
    }

    public LValue ast() {
        return _lvalue;
    }

    public Modifiers modifiers() {
        return _lvalue.getMods();
    }

    public boolean mutable() {
        return _lvalue.isMutable();
    }

    public int position() {
        return _position;
    }

    @Override
    public boolean hasExplicitType() {
        return _lvalue.getIdType().isSome();
    }

    @Override
    public String toString() {
        return _lvalue.toString();
    }

    @Override
    public Span getSpan() {
        return NodeUtil.getSpan(_lvalue);
    }
}
