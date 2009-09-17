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

import com.sun.fortress.compiler.typechecker.StaticTypeReplacer;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.nodes_util.NodeUtil;
import edu.rice.cs.plt.lambda.SimpleBox;
import edu.rice.cs.plt.lambda.Thunk;
import edu.rice.cs.plt.tuple.Option;

import java.util.Collections;
import java.util.List;

public class FieldGetterMethod extends FieldGetterOrSetterMethod {

    /** Create an implicit getter from a variable binding. */
    public FieldGetterMethod(Binding b, TraitObjectDecl traitDecl) {
        super(b, traitDecl);

        // If the Binding has a declared type, use it for the thunk.
        if (_ast.getIdType().isSome())
            _thunk = Option.<Thunk<Option<Type>>>some(SimpleBox.make(_ast.getIdType()));
    }
    
    /** Create an explicit getter from a function. */
    public FieldGetterMethod(FnDecl f, TraitObjectDecl traitDecl) {
        super(f, makeBinding(f), traitDecl);

        // If the FnDecl has a declared return type, use it for the thunk.
        if (NodeUtil.getReturnType(f).isSome())
            _thunk = Option.<Thunk<Option<Type>>>some(SimpleBox.make(NodeUtil.getReturnType(f)));
    }

    /**
     * Copy another FieldGetterMethod, performing a substitution with the visitor.
     */
    public FieldGetterMethod(FieldGetterMethod that, NodeUpdateVisitor visitor) {
        super(that, visitor);
    }

    /** Make a Binding for this setter from the given function. */
    private static Binding makeBinding(FnDecl f) {
        Modifiers mods = NodeUtil.getMods(f);
        return new LValue(f.getInfo(),
                          (Id) NodeUtil.getName(f),
                          mods,
                          NodeUtil.getReturnType(f),
                          mods.isMutable());
    }

    @Override
    public List<Param> parameters() {
        return Collections.emptyList();
    }

    @Override
    public FieldGetterMethod instantiate(List<StaticParam> params, List<StaticArg> args) {
        StaticTypeReplacer replacer = new StaticTypeReplacer(params, args);
        return new FieldGetterMethod(this, replacer);
    }

    @Override
    public FieldGetterMethod acceptNodeUpdateVisitor(NodeUpdateVisitor visitor) {
        return new FieldGetterMethod(this, visitor);
    }

    @Override
    public boolean hasDeclaredReturnType() {
      return _ast.getIdType().isSome();
    }
}
