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

package com.sun.fortress.interpreter.evaluator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import edu.rice.cs.plt.tuple.Option;

import com.sun.fortress.interpreter.env.BetterEnv;
import com.sun.fortress.interpreter.evaluator.types.BottomType;
import com.sun.fortress.interpreter.evaluator.types.FTraitOrObjectOrGeneric;
import com.sun.fortress.interpreter.evaluator.types.FType;
import com.sun.fortress.interpreter.evaluator.types.FTypeGeneric;
import com.sun.fortress.interpreter.evaluator.types.GenericTypeInstance;
import com.sun.fortress.interpreter.evaluator.types.TypeLatticeOps;
import com.sun.fortress.interpreter.evaluator.values.FGenericFunction;
import com.sun.fortress.interpreter.evaluator.values.FTuple;
import com.sun.fortress.interpreter.evaluator.values.FValue;
import com.sun.fortress.interpreter.evaluator.values.Fcn;
import com.sun.fortress.interpreter.evaluator.values.GenericFunctionOrMethod;
import com.sun.fortress.interpreter.evaluator.values.GenericFunctionalMethod;
import com.sun.fortress.interpreter.evaluator.values.Simple_fcn;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.NodeUtil;
import com.sun.fortress.useful.ABoundingMap;
import com.sun.fortress.useful.BoundingMap;
import com.sun.fortress.useful.HasAt;
import com.sun.fortress.useful.LatticeIntervalMap;
import com.sun.fortress.useful.StringComparer;
import com.sun.fortress.useful.Useful;

import static com.sun.fortress.interpreter.evaluator.ProgramError.errorMsg;
import static com.sun.fortress.interpreter.evaluator.ProgramError.error;
import static com.sun.fortress.interpreter.evaluator.InterpreterBug.bug;

public class EvaluatorBase<T> extends NodeAbstractVisitor<T>  {

    protected static final boolean DUMP_INFERENCE = false;

    final public BetterEnv e;

    protected EvaluatorBase(BetterEnv e) {
        this.e = e;
    }

    protected FValue functionInvocation(FValue arg, FValue foo,
                                        AbstractNode loc) {
        return functionInvocation(Useful.list(arg), foo, loc);
    }

    protected FValue functionInvocation(FValue arg, Fcn foo, AbstractNode loc) {
        return functionInvocation(Useful.list(arg), foo, loc);
    }

    protected FValue functionInvocation(List<FValue> args, FValue foo,
                                        AbstractNode loc) {
        if (foo instanceof Fcn) {
            return functionInvocation(args, (Fcn) foo, loc);
        } else {
            return bug(loc, errorMsg("Not a Fcn: ", foo));
        }
    }

    protected FValue functionInvocation(List<FValue> args, Fcn foo, AbstractNode loc) {
        try {
            // We used to do redundant checks for genericity here, but
            // now we reply on foo.apply to do type inference if necessary.
            return foo.apply(args, loc, e);
        } catch (FortressException ex) {
            throw ex.setContext(loc,e);
        } catch (StackOverflowError soe) {
            return error(loc,e,errorMsg("Stack overflow on ",foo));
        }
    }

    /**
     * Given args, infers the appropriate instantiation of a generic function.
     *
     * @throws ProgramError
     */
    public static Simple_fcn inferAndInstantiateGenericFunction(
            List<FValue> args, GenericFunctionOrMethod appliedThing, HasAt loc,
            BetterEnv e) throws ProgramError {

        if (DUMP_INFERENCE)
            System.err.println("IAIGF " + appliedThing + " with " + args);

        GenericTypeInstance selfType = null; // initialized if generic functional method

        if (appliedThing instanceof GenericFunctionalMethod) {
            GenericFunctionalMethod gfm = (GenericFunctionalMethod)  appliedThing;
            int spi = gfm.getSelfParameterIndex();
            FTypeGeneric declaredSelfType = gfm.getSelfParameterTypeAsGeneric();
            FValue selfArg = args.get(spi);

            if (selfArg.type() instanceof GenericTypeInstance) {
                selfType = (GenericTypeInstance) selfArg.type();
                // Find the supertype that exactly matches "self"
                for (FType ft : selfType.getTransitiveExtends()) {
                    if (ft instanceof GenericTypeInstance &&
                            ((GenericTypeInstance) ft).getGeneric().equals(declaredSelfType)) {
                        selfType = (GenericTypeInstance) ft;
                        break;
                    }
                }
                e = selfType.getWithin();
            } else
                return error(loc,
                        errorMsg(
                                "Non-generic-instance type for self argument ",
                                selfArg, " to generic functional method ",
                                appliedThing));
        }

        GenericFunctionOrMethod bar = (GenericFunctionOrMethod) appliedThing;
        // FnAbsDeclOrDecl fndod = bar.getFnDefOrDecl();
        List<StaticParam> tparams = bar.getStaticParams();
        List<Param> params = bar.getParams();
        EvalType et = new EvalType(e);
        // The types of the actual parameters ought to unify with the
        // types of the formal parameters.
        BoundingMap<String, FType, TypeLatticeOps> abm = new
        // ABoundingMap
        LatticeIntervalMap<String, FType, TypeLatticeOps>(TypeLatticeOps.V,
                StringComparer.V);
        Param p = null;
        Set<String> tp_set = new HashSet<String>();
        List<TypeParam> rechecks = null;
        for (StaticParam sp : tparams) {
            boolean rechecked = false;
            String name = NodeUtil.getName(sp);
            tp_set.add(name);
            if (sp instanceof TypeParam) {
                if (DUMP_INFERENCE)
                    System.err.println("TypeParam "+sp);
                TypeParam stp = (TypeParam) sp;
                for (Type tr : stp.getExtendsClause()) {
                    // Preinstall bounds in the boundingmap
                    try {
                        FType tt = et.evalType(tr);
                        if (DUMP_INFERENCE)
                            System.err.println("    extends "+tr+" = "+tt);
                        abm.meetPut(name, tt);
                    } catch (FortressException pe) {
                        if (DUMP_INFERENCE)
                            System.err.println("    extends with failed evalType "+tr);
                        if (!rechecked) {
                            rechecked = true;
                            if (rechecks == null) {
                                rechecks = new ArrayList<TypeParam>();
                            }
                            rechecks.add(stp);
                        }
                    }
                }
            } else if (DUMP_INFERENCE)
                System.err.println("Non-simple StaticParam "+sp);
        }
        /* FIX FOR #62 */
        if (params.size()==1 && args.size() != 1) {
            Iterator<Param> pit = params.iterator();
            Param pa = pit.next();

            if ( ! (pa instanceof VarargsParam)) {
                /* Tuple (or even re-tuple) arguments when inferring type
                 * if passing different # of args to 1-arg function. */
                if (DUMP_INFERENCE) {
                    System.err.println("Tupling args to match single-arg context.");
                }
                args = Useful.<FValue>list(FTuple.make(args));
            }
        }
        Iterator<Param> pit = params.iterator();
        for (FValue a : args) {
            FType at = a.type();
            if (at == null) {
                if (DUMP_INFERENCE)
                    System.err.println("Argument "+a+" without type info.");
                return error(loc, errorMsg("Argument ", a,
                        " has no type information"));
            }
            if (pit.hasNext()) {
                p = pit.next();
            } else if (p == null) {
                if (DUMP_INFERENCE)
                    System.err.println("Arguments "+args+" to 0-arg function.");
                error(loc, errorMsg(" Arguments ", args,
                        " given to 0-argument generic function ", appliedThing));
            }
            try {
                if (p instanceof NormalParam) {
                    Option<Type> t = ((NormalParam) p).getType();
                    // why can't we just skip if missing?
                    if (t.isNone()) {
                        /*
                         * Fake the type for a generic functional method
                         * invocation.
                         */
                        if (p.getName().toString().equals("self")
                                && bar instanceof GenericFunctionalMethod) {
                            // Use precomputed selfType that will match declared
                            GenericTypeInstance gi = (GenericTypeInstance) selfType;

                            at.unify(e,
                                     tp_set,
                                     abm,
                                     gi.getGeneric()
                                     .getInstantiationForFunctionalMethodInference());// instantiationAST());
                        } else {
                            if (DUMP_INFERENCE)
                                System.err.println("Parameter lacks type.");
                            error(loc,
                                    "Parameter needs type for generic resolution");
                        }
                    } else {
                        Type ty = t.unwrap();
                        if (DUMP_INFERENCE)
                            System.err.println("Unifying "+at+" and "+ty);
                        at.unify(e, tp_set, abm, ty);
                    }
                } else { // p instanceof VarargsParam
                    Type ty = ((VarargsParam) p).getType();
                    if (DUMP_INFERENCE)
                        System.err.println("Unifying "+at+" and vararg type "+ty);
                    at.unify(e, tp_set, abm, ty);
                }
            } catch (FortressException ex) {
                /* Give decent feedback when unification fails. */
                throw ex.setContext(loc, e);
            }
        }

        if (DUMP_INFERENCE)
            System.err.println("ABM 0={" + abm + "}");

        /*
         * Filter the inference through the result type, making it more specific
         * (which is less-specific, for arrow domain types).
         */
        MakeInferenceSpecific mis = new MakeInferenceSpecific(abm);

        // TODO: There is still a lurking error in inference, probably in arrow
        // types.

        // for (Param param : params) {
        // Option<Type> t = param.getType();
        // t.getVal().accept(mis);
        // }
        // if (DUMP_INFERENCE)
        // System.err.println("ABM 1={" + abm + "}");

        Option<Type> opt_rt = bar.getReturnType();

        if (opt_rt.isSome())
            opt_rt.unwrap().accept(mis);

        if (DUMP_INFERENCE)
            System.err.println("ABM 2={" + abm + "}");

        /* Enforce upper bounds that we could not enforce up front.
         * We're worried here about self-typing idioms.  What we have to do
         * is find the (unique) occurrence of the type stem of the bounding type
         * in the supertypes of the bounded type.
         *
         * What do we do with variable upper bounds?  We should impose the bounds
         * derived for the given type variable.
         */
        if (rechecks != null) {
            for (TypeParam tp : rechecks) {
                FType t = abm.get(NodeUtil.getName(tp));
                if (t == null) {
                    if (DUMP_INFERENCE) {
                        System.err.println("Can't constrain the type "+tp+
                                           "\n    enough to enforce its upper bounds "+
                                           tp.getExtendsClause() +
                                           "\n    Choosing to erase to bottom.");
                    }
                    t = BottomType.ONLY;
                }
                for (Type tr : tp.getExtendsClause()) {
                    if (DUMP_INFERENCE) {
                        System.err.println("Unifying "+tp+" lower bound "+t+
                                           "\n    with its bound "+tr+
                                           " "+tr.getClass());
                    }
                    t.unify(e, tp_set, abm, tr);
                }
            }
        }

        /*
         * Iterate over static parameters, choosing least-general binding for
         * each one.
         */
        ArrayList<FType> tl = new ArrayList<FType>(tparams.size());
        for (StaticParam tp : tparams) {
            FType t = abm.get(NodeUtil.getName(tp));
            if (t == null)
                t = BottomType.ONLY;
            tl.add(t);
        }
        Simple_fcn sfcn = bar.typeApply(loc, tl);
        if (DUMP_INFERENCE)
            System.err.println("Result " + sfcn);
        return sfcn;
    }


}
