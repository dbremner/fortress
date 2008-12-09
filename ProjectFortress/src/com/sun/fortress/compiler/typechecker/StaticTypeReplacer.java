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

package com.sun.fortress.compiler.typechecker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.sun.fortress.exceptions.InterpreterBug;
import com.sun.fortress.nodes.KindType;
import com.sun.fortress.nodes.KindInt;
import com.sun.fortress.nodes.KindNat;
import com.sun.fortress.nodes.KindBool;
import com.sun.fortress.nodes.KindDim;
import com.sun.fortress.nodes.KindUnit;
import com.sun.fortress.nodes.KindOp;
import com.sun.fortress.nodes.BoolRef;
import com.sun.fortress.nodes.DimRef;
import com.sun.fortress.nodes.NodeDepthFirstVisitor;
import com.sun.fortress.nodes.UnitRef;
import com.sun.fortress.nodes.Id;
import com.sun.fortress.nodes.VarType;
import com.sun.fortress.nodes.TraitType;
import com.sun.fortress.nodes.IntRef;
import com.sun.fortress.nodes.Node;
import com.sun.fortress.nodes.NodeAbstractVisitor;
import com.sun.fortress.nodes.NodeUpdateVisitor;
import com.sun.fortress.nodes.OpArg;
import com.sun.fortress.nodes.IdOrOpOrAnonymousName;
import com.sun.fortress.nodes.StaticArg;
import com.sun.fortress.nodes.TypeArg;
import com.sun.fortress.nodes.UnitArg;
import com.sun.fortress.nodes.DimArg;
import com.sun.fortress.nodes.IntArg;
import com.sun.fortress.nodes.BoolArg;
import com.sun.fortress.nodes.StaticParam;
import com.sun.fortress.nodes.TupleType;
import com.sun.fortress.nodes.Type;
import com.sun.fortress.nodes.VarRef;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.useful.NI;

import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.lambda.Lambda;
import edu.rice.cs.plt.tuple.Option;
import edu.rice.cs.plt.tuple.Pair;

import static com.sun.fortress.nodes_util.NodeFactory.*;
import static edu.rice.cs.plt.tuple.Option.*;

/**
 * This class will replace all occurrences of a static parameter in a given
 * type with its instantiated static argument. A StaticTypeReplacer is created with
 * some static parameters and corresponding static arguments. Each type P_i in
 * the parameters will be replaced with the corresponding type A_i in the
 * arguments, for every occurrence of P_i in some outer type T.
 *
 * For example, for static parameters [\U, V\], static arguments
 * [\ZZ32, String\], and outer type Pair<V,U>, the type replacer will return
 * type Pair<String, ZZ32>.
 */
public class StaticTypeReplacer extends NodeUpdateVisitor {

    /** Map parameter name to the static argument bound to it. */
    private final Map<IdOrOpOrAnonymousName, StaticArg> parameterMap;

    /** Assume params.size() == args.size() */
    public StaticTypeReplacer(List<StaticParam> params, List<StaticArg> args) {
        assert(params.size() == args.size());
        int n = params.size();
        parameterMap = new HashMap<IdOrOpOrAnonymousName, StaticArg>(n);
        for (int i=0; i<n; ++i) {
            parameterMap.put(params.get(i).getName(), args.get(i));
        }
    }

    public Type replaceIn(Type t) {
        return (Type)t.accept(this); // TODO safe?
    }

    private Node updateNode(Node that, Id name) {
        if (name.getApiName().isSome()) { return that; }
        StaticArg arg = parameterMap.get(name);
        if (arg == null) { return that; }
        else {
            // unwrap the StaticArg
            return arg.accept(new NodeAbstractVisitor<Node>() {
                @Override public Node forTypeArg(TypeArg arg) { return arg.getTypeArg(); }
                @Override public Node forIntArg(IntArg arg) { return arg.getIntVal(); }
                @Override public Node forBoolArg(BoolArg arg) { return arg.getBoolArg(); }
                @Override public Node forOpArg(OpArg arg) { return arg.getName(); }
                @Override public Node forDimArg(DimArg arg) { return arg.getDimArg(); }
                @Override public Node forUnitArg(UnitArg arg) { return arg.getUnitArg(); }
            });
        }
    }

    // ----------- VISITOR METHODS ---------------



    @Override
    public Node forVarType(VarType that) {
        return updateNode(that, that.getName());
    }

    @Override
    public Node forOpArg(OpArg that) {
        StaticArg arg = parameterMap.get(that.getName());
        //        System.err.printf("forOpArg lookup: %s\n", arg);
        return arg == null ? that : arg;
    }

    @Override
    public Node forIntRef(IntRef that) {
        return updateNode(that, that.getName());
    }

    @Override
    public Node forBoolRef(BoolRef that) {
        return updateNode(that, that.getName());
    }

    @Override
    public Node forDimRef(DimRef that) {
        return updateNode(that, that.getName());
    }

    @Override
    public Node forUnitRef(UnitRef that) {
        return updateNode(that, that.getName());
    }

//    @Override
//    public Node forVarRef(VarRef that) {
//        return updateNode(that, that.getVar());
//    }

    /**
     * Do the static arguments and parameters "match" in the limited sense that there
     * are the same number and their kinds match (e.g., TypeArgs match TypeParams,
     * nats match NatParams, etc.)
     * @param static_args
     * @param staticParams
     * @return
     */
    public static Option<ConstraintFormula> argsMatchParams(List<StaticArg> static_args,
                                                            List<StaticParam> static_params, final TypeAnalyzer subtype_checker) {
        if( static_args.size() != static_params.size() ) {
            return Option.none();
        }
        else {
            ConstraintFormula valid= ConstraintFormula.TRUE;

            Iterable<Pair<StaticParam,StaticArg>> zip = IterUtil.zip(static_params, static_args);
            for(Pair<StaticParam,StaticArg> temp : zip){
                final StaticParam  param = temp.first();
                final StaticArg arg = temp.second();
                NodeDepthFirstVisitor<Option<ConstraintFormula>> outer = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                    @Override
                    public Option<ConstraintFormula> defaultCase(Node that) {
                        return InterpreterBug.bug("Static param has been extended since argMatchParams was written");
                    }

                    @Override
                    public Option<ConstraintFormula> forStaticParam(StaticParam that) {
                        return that.getKind().accept( new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                @Override
                                public Option<ConstraintFormula> defaultCase(Node n) { return Option.none(); }

                                @Override
                                public Option<ConstraintFormula> forKindOp(KindOp that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forOpArg(OpArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindBool(KindBool k) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forBoolArg(BoolArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindDim(KindDim that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forDimArg(DimArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindInt(KindInt that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forIntArg(IntArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindNat(KindNat that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forIntArg(IntArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindType(final KindType that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forTypeArg(TypeArg arg) {
                                            Type upperbound = NodeFactory.makeIntersectionType(CollectUtil.asSet(param.getExtendsClause()));
                                            return Option.some(subtype_checker.subtype(arg.getTypeArg(),upperbound));
                                        }
                                    };
                                    return arg.accept(inner);
                                }
                                @Override
                                public Option<ConstraintFormula> forKindUnit(KindUnit that) {
                                    NodeDepthFirstVisitor<Option<ConstraintFormula>> inner = new NodeDepthFirstVisitor<Option<ConstraintFormula>>() {
                                        @Override public Option<ConstraintFormula> defaultCase(Node that) {return Option.none();}
                                        @Override public Option<ConstraintFormula> forUnitArg(UnitArg that) {return Option.some(ConstraintFormula.TRUE);}
                                    };
                                    return arg.accept(inner);
                                }
                            });
                    }
                };

                Option<ConstraintFormula> result = param.accept(outer);
                if(result.isSome()){
                    valid = valid.and(result.unwrap(),subtype_checker.new SubtypeHistory());
                }
                else{
                    return Option.none();
                }
            }
            return Option.some(valid);
            }
    }
}
