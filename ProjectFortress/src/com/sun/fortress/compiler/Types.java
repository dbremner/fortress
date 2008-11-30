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

package com.sun.fortress.compiler;

import com.sun.fortress.nodes.*;
import com.sun.fortress.nodes_util.NodeFactory;
import com.sun.fortress.nodes_util.Span;
import com.sun.fortress.useful.NI;

import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.LinkedHashMap;
import edu.rice.cs.plt.lambda.Lambda;
import edu.rice.cs.plt.iter.IterUtil;
import edu.rice.cs.plt.collect.CollectUtil;
import edu.rice.cs.plt.tuple.Option;

import static com.sun.fortress.nodes_util.NodeFactory.*;
import static com.sun.fortress.interpreter.glue.WellKnownNames.*;

/**
 * General-purpose type constants and constructors/destructors for types.
 * Unlike the methods in {@link NodeFactory}, these pay no attention to the
 * {@code span} and {@code parenthesized} AST fields, and are more "aware"
 * of the semantics of Fortress types.
 */
public final class Types {

    private Types() {}

    private static Span span = NodeFactory.makeSpan("If you see this, it is a bug.");

    public static final Id ANY_NAME = makeId("AnyType", "Any");
    public static final Id ARRAY_NAME = makeId(fortressLibrary,"Array");
    // TODO: Replace ImmutableArray with ImmutableHeapSequence when
    //       ImmutableHeapSequence is put into the libraries.
    public static final Id IMMUTABLE_HEAP_SEQ_NAME = makeId(fortressLibrary, "ImmutableArray");

    public static final AnyType ANY = new AnyType(span);
    public static final BottomType BOTTOM = new BottomType(span);
    public static final TraitType OBJECT = makeTraitType(fortressBuiltin, "Object");

    public static final Type BOTTOM_DOMAIN = BOTTOM;

    public static final VoidType VOID = new VoidType(span);
    public static final TraitType FLOAT_LITERAL = makeTraitType(fortressBuiltin, "FloatLiteral");
    public static final TraitType INT_LITERAL = makeTraitType(fortressBuiltin, "IntLiteral");
    public static final TraitType ZZ32 = makeTraitType(fortressLibrary, "ZZ32");
    public static final TraitType BOOLEAN = makeTraitType(fortressBuiltin, "Boolean");
    public static final TraitType CHAR = makeTraitType(fortressBuiltin, "Char");
    public static final TraitType STRING = makeTraitType(fortressLibrary, "String");
    public static final TraitType REGION = makeTraitType(fortressLibrary, "Region");
    public static final TraitType EXCEPTION = makeTraitType(fortressLibrary, "Exception");
    public static final TraitType CHECKED_EXCEPTION = makeTraitType(fortressLibrary, "CheckedException");


    public static final LabelType LABEL = new LabelType(span);

    public static final TraitType makeVarargsParamType(Type varargsType) {
        return makeTraitType(IMMUTABLE_HEAP_SEQ_NAME, makeTypeArg(varargsType), makeTypeArg(ZZ32));
    }

    public static TraitType makeThreadType(Type typeArg) {
        return makeTraitType(makeId(fortressBuiltin, "Thread"),
                             makeTypeArg(typeArg));
    }

    public static Id getArrayKName(int k){
     String name = "Array"+k;
     return makeId(fortressLibrary,name);
    }

    public static TraitType makeArrayType(Type elem, Type indexed){
     return makeTraitType(ARRAY_NAME, makeTypeArg(elem),makeTypeArg(indexed));
    }

    public static TraitType makeArrayKType(int k, List<StaticArg> args){
     return  makeTraitType(getArrayKName(k),args);
    }

    /**
     * Create a type {@code FortressLibrary.Generator[\typeArg\]}.
     */
    public static TraitType makeGeneratorType(Type typeArg) {
        return makeTraitType(makeId(fortressLibrary, "Generator"),
                             makeTypeArg(typeArg));
    }

    /**
     * Create a type {@code FortressLibrary.Condition[\typeArg\]}.
     */
    public static TraitType makeConditionType(Type typeArg) {
        return makeTraitType(makeId(fortressLibrary, "Condition"),
                             makeTypeArg(typeArg));
    }

    /** Construct the appropriate type from a list of union elements. */
    public static Type makeUnion(Iterable<Type> disjuncts) {
        return MAKE_UNION.value(disjuncts);
    }

    /** Construct the appropriate type from a list of union elements. */
    public static final Lambda<Iterable<Type>, Type> MAKE_UNION =
        new Lambda<Iterable<Type>, Type>() {
        public Type value(Iterable<Type> ts) {
            switch (IterUtil.sizeOf(ts, 2)) {
                case 0: return BOTTOM;
                case 1: return IterUtil.first(ts);
                default: {
                    List<Type> l = CollectUtil.makeList(ts);
                    return new UnionType(NodeFactory.makeSpan("impossible", l), l);
                }
            }
        }
    };

    /** Construct the appropriate type from a list of intersection elements. */
    public static Type makeIntersection(Iterable<Type> conjuncts) {
        return MAKE_INTERSECTION.value(conjuncts);
    }

    /** Construct the appropriate type from a list of intersection elements. */
    public static final Lambda<Iterable<Type>, Type> MAKE_INTERSECTION =
        new Lambda<Iterable<Type>, Type>() {
        public Type value(Iterable<Type> ts) {
            switch (IterUtil.sizeOf(ts, 2)) {
                case 0: return ANY;
                case 1: return IterUtil.first(ts);
                default: {
                    List<Type> l = CollectUtil.makeList(ts);
                    return new IntersectionType(NodeFactory.makeSpan("impossible", l), l);
                }
            }
        }
    };

    /** Treat an arbitrary type as a union and enumerate its elements. */
    public static Iterable<Type> disjuncts(Type t) {
        return t.accept(DISJUNCTS);
    }

    /** Treat an arbitrary type as a union and enumerate its elements. */
    public static final NodeVisitorLambda<Iterable<Type>> DISJUNCTS =
        new NodeAbstractVisitor<Iterable<Type>>() {
        @Override public Iterable<Type> forType(Type t) {
            return IterUtil.singleton(t);
        }
        @Override public Iterable<Type> forUnionType(UnionType t) {
            return t.getElements();
        }
        @Override public Iterable<Type> forBottomType(BottomType t) {
            return IterUtil.empty();
        }
    };

    /** Treat an arbitrary type as an intersection and enumerate its elements. */
    public static Iterable<Type> conjuncts(Type t) {
        return t.accept(CONJUNCTS);
    }

    /** Treat an arbitrary type as an intersection and enumerate its elements. */
    public static final NodeVisitorLambda<Iterable<Type>> CONJUNCTS =
        new NodeAbstractVisitor<Iterable<Type>>() {
        @Override public Iterable<Type> forType(Type t) {
            return IterUtil.singleton(t);
        }
        @Override public Iterable<Type> forIntersectionType(IntersectionType t) {
            return t.getElements();
        }
        // TODO: the rules say Any = AND{}, but that allows tuples and arrows
        // containing Any to be equivalent to Any, which isn't what we want.
        // Need to work this out in the rules.
        //@Override public Iterable<Type> forAnyType(AnyType t) {
        //    return IterUtil.empty();
        //}
    };

    /**
     * Construct the appropriate type (void, a tuple, or the type itself) from a list of
     * tuple elements.
     */
    public static Type makeTuple(Iterable<Type> elements) {
        return MAKE_TUPLE.value(elements);
    }

    /** Construct the appropriate type from a list of tuple elements. */
    public static final Lambda<Iterable<Type>, Type> MAKE_TUPLE =
        new Lambda<Iterable<Type>, Type>() {
        public Type value(Iterable<Type> ts) {
            switch (IterUtil.sizeOf(ts, 2)) {
                case 0: return VOID;
                case 1: return IterUtil.first(ts);
                default: {
                    List<Type> l = CollectUtil.makeList(ts);
                    return new TupleType(NodeFactory.makeSpan("impossible", l), l);
                }
            }
        }
    };

    /**
     * Produce the union disjunct of the given vararg tuple at a certain arity.
     * This is the arity+1st element of a union equivalent to the vararg tuple
     * like the following:<ul>
     * <li>(T...) = () | T | (T, T) | (T, T, T) | ...</li>
     * <li>(A, B, C...) = Bottom | Bottom | (A, B) | (A, B, C) | ...</li>
     * </ul>
     * Note that the result is defined for all arities, but may sometimes be
     * Bottom.
     */
    public static Type varargDisjunct(TupleType t, int arity) {
        List<Type> base = t.getElements();
        int baseSize = base.size();
        if (baseSize > arity) { return BOTTOM; }
        else {
            Iterable<Type> rest = IterUtil.copy(t.getVarargs().unwrap(), arity-baseSize);
            return makeTuple(IterUtil.compose(base, rest));
        }
    }

    /**
     * Produce a type representing a Domain with any keyword types removed.
     * May return a TupleType, a VoidType, a TupleType with varargs, or a type
     * representing a singleton argument.
     */
    public static Type stripKeywords(Type d) {
        if ( d instanceof TupleType ) {
            TupleType _d = (TupleType)d;
            if (_d.getVarargs().isSome()) {
                return new TupleType(NodeFactory.makeSpan(_d.getElements(), _d.getVarargs().unwrap()), _d.getElements(), _d.getVarargs());
            }
            else {
                List<Type> args = _d.getElements();
                switch (args.size()) {
                    case 0: return VOID;
                    case 1: return args.get(0);
                    default: return new TupleType(NodeFactory.makeSpan("impossible", args), args);
                }
            }
        } else
            return d;
    }

    /**
     * Produce a map from keyword names to types.  The iteration order of the
     * map is identical to that of the KeywordType list.
     */
    public static Map<Id, Type> extractKeywords(Type d) {
        if ( d instanceof TupleType ) {
            TupleType _d = (TupleType)d;
            // Don't waste time allocating a map if it will be empty (the usual case)
            if (_d.getKeywords().isEmpty()) { return Collections.<Id, Type>emptyMap(); }
            else {
                Map<Id, Type> result = new LinkedHashMap<Id, Type>(8);
                for (KeywordType k : _d.getKeywords()) {
                    result.put(k.getName(), k.getKeywordType());
                }
                return result;
            }
        } else
            return Collections.<Id, Type>emptyMap();
    }

    /**
     * Construct a Domain from a single type representing the required arguments
     * and a keywords map representing the keyword arguments.
     */
    public static Type makeDomain(Type argsType, Map<Id, Type> keywords) {
        List<KeywordType> keywordList = new ArrayList<KeywordType>(keywords.size());
        for (Map.Entry<Id, Type> entry : keywords.entrySet()) {
            keywordList.add(new KeywordType(NodeFactory.makeSpan(entry.getKey(), entry.getValue()), entry.getKey(), entry.getValue()));
        }
        return makeDomain(argsType, keywordList);
    }

    /**
     * Construct a Domain from a single type representing the required arguments
     * and a list of KeywordTypes.  Unlike {@link NodeFactory#makeDomain}, does
     * not assume that {@code argsType} was produced by the parser.
     */
    public static Type makeDomain(Type argsType, final List<KeywordType> keywords) {
        return argsType.accept(new NodeAbstractVisitor<Type>() {
            @Override public Type forVoidType(VoidType t) {
                if ( keywords.isEmpty() )
                    return t;
                else
                    return new TupleType(NodeFactory.makeSpan("Types_bogus_span_for_empty_list", keywords),
                                         Collections.<Type>emptyList(), keywords);
            }
            @Override public Type forTupleType(TupleType t) {
                if ( t.getVarargs().isNone() )
                    return new TupleType(NodeFactory.makeSpan(t, keywords), t.getElements(), keywords);
                else
                    return new TupleType(NodeFactory.makeSpan(t, keywords), t.getElements(), t.getVarargs(),
                                         keywords);
            }
            @Override public Type forType(Type t) {
                if ( keywords.isEmpty() )
                    return t;
                else
                    return new TupleType(NodeFactory.makeSpan(t, keywords), Collections.singletonList(t), keywords);
            }
        });
    }

    /**
     * Given A and Op, returns the type
     * TotalOperatorOrder[\A,<,<=,>=,>,Op]
     */
 public static Type makeTotalOperatorOrder(Type A, OpName op) {
//  NodeFactory.makeTraitType(makeId("TotalOperater"), sargs)
//  NodeFactory.makeOpArg("whoa");

  return NI.nyi();
 }

}
