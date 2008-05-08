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

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.Evaluator;
import com.sun.fortress.interpreter.evaluator.types.FTraitOrObjectOrGeneric;
import com.sun.fortress.interpreter.evaluator.types.FTypeTrait;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.nodes.Applicable;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.AssignedList;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.Useful;

import static com.sun.fortress.interpreter.evaluator.ProgramError.error;
import static com.sun.fortress.interpreter.evaluator.ProgramError.errorMsg;
import static com.sun.fortress.interpreter.evaluator.InterpreterBug.bug;

public class FunctionalMethod extends Closure implements HasSelfParameter {

    final private int selfParameterIndex;
    final private FTraitOrObjectOrGeneric selfParameterType;

    /* (non-Javadoc)
     * @see com.sun.fortress.interpreter.evaluator.values.Closure#applyInner(java.util.List, com.sun.fortress.interpreter.useful.HasAt, com.sun.fortress.interpreter.env.BetterEnv)
     */
    @Override
    public FValue applyInner(List<FValue> args, HasAt loc, BetterEnv envForInference) {
        FValue selfVal = args.get(selfParameterIndex);
        args = Useful.removeIndex(selfParameterIndex, args);
        String mname = NodeUtil.nameAsMethod(getDef());
        return Evaluator.invokeMethod(selfVal, s(def), mname, args,
                                      loc, envForInference);
    }

    public FunctionalMethod(BetterEnv e, Applicable fndef, int self_parameter_index, FTraitOrObjectOrGeneric self_parameter_type) {
        super(e, fndef, true);
        selfParameterIndex = self_parameter_index;
        selfParameterType = self_parameter_type;
        // TODO Auto-generated constructor stub
    }

    protected FunctionalMethod(BetterEnv e, Applicable fndef, List<FType> static_args, int self_parameter_index, FTraitOrObjectOrGeneric self_parameter_type) {
        super(e, fndef, static_args);
         selfParameterIndex = self_parameter_index;
         selfParameterType = self_parameter_type;
        // TODO Auto-generated constructor stub
    }


    @Override
    public List<Parameter> getParameters() {
        Parameter selfParam = new Parameter("self", selfParameterType, false, false);
        return new AssignedList<Parameter>(super.getParameters(), selfParameterIndex, selfParam);
    }

    public int hashCode() {
        return def.hashCode() + selfParameterType.hashCode() +
        (instArgs == null ? 0 : instArgs.hashCode());
    }

    public boolean equals(Object o) {
        if (this == o) return true;
        if (o.getClass().equals(this.getClass())) {
            FunctionalMethod oc = (FunctionalMethod) o;
            return def == oc.def &&
            selfParameterType.equals(oc.selfParameterType) &&
            (instArgs == null ? (oc.instArgs == null) :
                oc.instArgs == null ? false : instArgs.equals(oc.instArgs));
        }
        return false;
    }

    public int getSelfParameterIndex() {
        return selfParameterIndex;
    }

    public FTraitOrObjectOrGeneric getSelfParameterType() {
        return selfParameterType;
    }

    public String toString() {
        String res = s(def)+
                     "fn meth(self "+selfParameterIndex+")";
        if (instArgs != null)
            res += Useful.listInOxfords(instArgs);
        if (type() != null) {
            res += ":" + type();
        } else {
            res += " [no type]";
        }
        return res+" ("+def.at()+")";
    }

}
