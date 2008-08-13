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
package com.sun.fortress.interpreter.evaluator.values;

import java.util.List;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.Environment;
import com.sun.fortress.interpreter.evaluator.types.FTraitOrObjectOrGeneric;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeGeneric;
import com.sun.fortress.nodes.FnAbsDeclOrDecl;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.WhereClause;
import com.sun.fortress.nodes_util.ErrorMsgMaker;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.parser_util.FortressUtil;
import com.sun.fortress.useful.Useful;

public class GenericFunctionalMethod extends FGenericFunction implements HasSelfParameter {


    int selfParameterIndex;
    private FTypeGeneric selfParameterType;

    public GenericFunctionalMethod(Environment e, FnAbsDeclOrDecl fndef, int self_parameter_index, FTypeGeneric self_parameter_type) {
        super(e, fndef);
        this.selfParameterIndex = self_parameter_index;
        this.selfParameterType = self_parameter_type;
    }

    @Override
    protected Simple_fcn newClosure(Environment clenv, List<FType> args) {
        // BUG IS HERE, NEED TO instantiate the selfParameterType! ;

        FTraitOrObjectOrGeneric instantiatedSelfType = ((FTypeGeneric) selfParameterType).make(args, getFnDefOrDecl());

        FunctionalMethod cl = FType.anyAreSymbolic(args) ?
                new FunctionalMethodInstance(clenv, fndef, args, this, selfParameterIndex, instantiatedSelfType) :
            new FunctionalMethod(clenv, fndef, args, selfParameterIndex, instantiatedSelfType);
         cl.finishInitializing();
         return cl;
    }

    @Override
    public  List<StaticParam> getStaticParams() {
        return selfParameterType.getDef().getStaticParams();
    }

    protected Option<WhereClause> getWhere() {
        // TODO need to get where clause from generics, in general.
        return Option.<WhereClause>none();
    }

    public int hashCode() {
        return getDef().hashCode() + selfParameterType.hashCode();
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o.getClass().equals(this.getClass())) {
            GenericFunctionalMethod oc = (GenericFunctionalMethod) o;
            return getDef() == oc.getDef() &&
            selfParameterType.equals(oc.selfParameterType);
        }
        return false;
    }

    public int getSelfParameterIndex() {
        return selfParameterIndex;
    }

    public FTraitOrObjectOrGeneric getSelfParameterType() {
        return selfParameterType;
    }
    public FTypeGeneric getSelfParameterTypeAsGeneric() {
        return selfParameterType;
    }


    public String toString() {
        FnAbsDeclOrDecl node = fndef;
        // Code lifted from ErrorMsgMaker.forFnAbsDeclOrDecl
        return selfParameterType.toString() + Useful.listInOxfords(ErrorMsgMaker.ONLY.mapSelf(getStaticParams())) + "." + NodeUtil.nameString(node.getName())
        //+ Useful.listInOxfords(ErrorMsgMaker.ONLY.mapSelf(getStaticParams()))
        + Useful.listInParens(ErrorMsgMaker.ONLY.mapSelf(node.getParams()))
        + (node.getReturnType().isSome() ? (":" + node.getReturnType().unwrap().accept(ErrorMsgMaker.ONLY)) : "") + fndef.at();
    }

}
