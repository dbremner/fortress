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

import com.sun.fortress.compiler.Types;
import com.sun.fortress.compiler.typechecker.StaticTypeReplacer;
import com.sun.fortress.compiler.typechecker.TypesUtil;
import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.Modifiers;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.nodes_util.Span;
import edu.rice.cs.plt.lambda.SimpleBox;
import edu.rice.cs.plt.lambda.Thunk;
import edu.rice.cs.plt.tuple.Option;

import java.util.Collections;
import java.util.List;

public class FieldSetterMethod extends FieldGetterOrSetterMethod {

    private final Param _param;

    public FieldSetterMethod(Binding b, TraitObjectDecl traitDecl) {
        super(b, traitDecl);
        _param = NodeFactory.makeParam(
            NodeUtil.getSpan(b),
            Modifiers.None,
            NodeFactory.makeId(NodeUtil.getSpan(b), "fakeParamForImplicitSetter"),
            b.getIdType());

        // Return type is always VOID.
        _thunk = Option.<Thunk<Option<Type>>>some(SimpleBox.make(Option.<Type>some(Types.VOID)));
    }

    /** Create an implicit setter from a variable binding. */
    public FieldSetterMethod(FnDecl f, TraitObjectDecl traitDecl) {
        super(f, makeBinding(f), traitDecl);
        _param = NodeUtil.getParams(f).get(0);

        // Return type is always VOID.
        _thunk = Option.<Thunk<Option<Type>>>some(SimpleBox.make(Option.<Type>some(Types.VOID)));
    }

    /**
     * Copy another FieldSetterMethod, performing a substitution with the visitor.
     */
    public FieldSetterMethod(FieldSetterMethod that, NodeUpdateVisitor visitor) {
        super(that, visitor);
        _param = (Param) that._param.accept(visitor);
    }
    
    /** Make a Binding for this setter from the given function. */
    private static Binding makeBinding(FnDecl f) {
        Param p = NodeUtil.getParams(f).get(0);
        Modifiers mods = NodeUtil.getMods(f);
        return new LValue(f.getInfo(),
                          (Id) NodeUtil.getName(f),
                          mods,
                          p.getIdType(),
                          mods.isMutable());
    }

    @Override
    public List<Param> parameters() {
        return Collections.singletonList(_param);
    }

    @Override
    public FieldSetterMethod instantiate(List<StaticParam> params, List<StaticArg> args) {
        StaticTypeReplacer replacer = new StaticTypeReplacer(params, args);
        return new FieldSetterMethod(this, replacer);
    }

    @Override
    public FieldSetterMethod acceptNodeUpdateVisitor(NodeUpdateVisitor visitor) {
        return new FieldSetterMethod(this, visitor);
    }
}
